"""Differential checks against responses exported by the unchanged Java implementation."""

import asyncio
import copy
import json
import time
from pathlib import Path

import httpx
import pytest

from kangban_agent import models
from kangban_agent.budget import ExecutionBudget
from kangban_agent.errors import AgentError
from kangban_agent.memory import prepare_history
from kangban_agent.orchestrator import AgentOrchestrator
from kangban_agent.planner import plan_tools
from kangban_agent.providers import HttpProvider, MockProvider, ModelReply, ModelToolCall
from kangban_agent.safety import assess, guard_response
from kangban_agent.settings import Settings

BASELINE = json.loads((Path(__file__).parent / "fixtures/java-baseline.json").read_text(encoding="utf-8"))


def options(**updates):
    values = {"enabled": True, "modelToolCallingEnabled": False, "ragEnabled": False, "maxIterations": 5,
              "maxHistoryMessages": 12, "maxHistoryTokens": 6000, "maxHistoryMessageCharacters": 4000}
    return models.RuntimeOptions(**(values | updates))


@pytest.mark.parametrize("case", BASELINE["plans"])
def test_rule_planner_matches_java(case):
    assert [call.model_dump() for call in plan_tools(case["message"], case["limit"])] == case["expected"]


@pytest.mark.parametrize("case", BASELINE["memories"])
def test_memory_matches_java_utf16_units(case):
    history = [models.ConversationMessage.model_construct(**item) for item in case["history"]]
    count, tokens, characters = case["limits"]
    actual = prepare_history(history, options(maxHistoryMessages=count, maxHistoryTokens=tokens, maxHistoryMessageCharacters=characters))
    rows = []
    for message in actual:
        raw = message.content.encode("utf-16-le", errors="surrogatepass")
        rows.append({"role": message.role, "utf16": [int.from_bytes(raw[i:i+2], "little") for i in range(0, len(raw), 2)]})
    assert rows == case["expected"]


@pytest.mark.parametrize("case", BASELINE["safety"])
def test_safety_matches_java(case):
    assert (assess(case["text"]) or "") == case["assessment"]["notice"]
    assert guard_response(case["text"]) == case["guarded"]


@pytest.mark.parametrize("case", BASELINE["mockReplies"])
def test_mock_replies_match_java(case):
    assert asyncio.run(MockProvider(delay=0).consult(case["message"], "{}", [])) == case["expected"]


@pytest.mark.parametrize("case", BASELINE["wireRequests"])
def test_provider_requests_match_actual_java_http(case):
    async def check():
        captured = []
        def respond(request):
            captured.append(json.loads(request.content))
            assert request.headers["Authorization"] == "Bearer test-only-provider-key"
            assert "X-Agent-Context" not in request.headers
            return httpx.Response(200, json={"choices": [{"message": {"content": "模拟回答"}}]})
        settings = Settings(provider=case["provider"], model="fixture-model", api_key="test-only-provider-key")
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            provider = HttpProvider(client, settings, ExecutionBudget(int(time.time()*1000)+30_000))
            history = [models.ConversationMessage(**item) for item in case["history"]]
            if "historyUtf16" in case:
                history = [models.ConversationMessage.model_construct(role=item["role"], content=b"".join(
                    unit.to_bytes(2, "little") for unit in item["units"]).decode("utf-16-le", errors="surrogatepass"))
                           for item in case["historyUtf16"]]
            if case["mode"] == "chat":
                await provider.consult(case["message"], case["patientData"], history)
            else:
                definitions = [models.AiToolDefinition(**item) for item in case["definitions"]]
                await provider.consult_with_tools(case["message"], case["patientData"], history, definitions, case["toolHistory"])
        assert captured == [case["expected"]]
    asyncio.run(check())


class FakeCallbacks:
    def __init__(self, data):
        self.data = data
        self.calls = []

    async def propose(self, message):
        return models.ProposalDecision(**self.data["decision"])

    async def search(self, message):
        return models.RagSearchResult(**self.data["rag"])

    async def definitions(self):
        return [models.AiToolDefinition(**item) for item in self.data["definitions"]]

    async def execute(self, call):
        self.calls.append(call.model_dump())
        result = models.AgentToolResult(**self.data["toolResult"]).model_copy(update={"toolName": call.name})
        return models.ToolExecutionResponse(result=result, elapsedMs=0)


def java_tool_history(wire):
    history = []
    for item in wire:
        calls = [{"id": call["id"], "name": call["function"]["name"], "arguments": json.loads(call["function"]["arguments"])}
                 for call in item.get("tool_calls", [])]
        history.append({"role": item["role"], "content": item["content"],
                        "toolCallId": item.get("tool_call_id", ""), "toolCalls": calls})
    return history


class CaptureProvider:
    def __init__(self, data):
        self.data = data
        self.supports_tool_calling = data["options"]["modelToolCallingEnabled"]
        self.calls = []

    async def consult(self, message, patient_data, history):
        self.calls.append({"mode": "chat", "message": message, "patientData": patient_data,
                           "history": [item.model_dump() for item in history]})
        return self.data["answer"]

    async def consult_with_tools(self, message, patient_data, history, definitions, tool_history):
        self.calls.append({"mode": "tools", "message": message, "patientData": patient_data,
                           "history": [item.model_dump() for item in history], "toolHistory": java_tool_history(copy.deepcopy(tool_history))})
        requested = [ModelToolCall(**item) for item in self.data["requestedCalls"]] if not tool_history else []
        return ModelReply("" if requested else self.data["answer"], requested)


@pytest.mark.parametrize("case", BASELINE["runs"], ids=lambda case: case["name"])
def test_orchestrator_matches_java(case):
    async def check():
        data = case["input"]
        expected = case["expected"]
        callbacks = FakeCallbacks(data)
        provider = CaptureProvider(data)
        orchestrator = AgentOrchestrator(callbacks, provider)
        try:
            actual = (await orchestrator.run(models.AgentRequest(**data["request"]), models.RuntimeOptions(**data["options"]))).response.model_dump()
        except AgentError as error:
            assert error.message == expected["error"]
        else:
            reference = copy.deepcopy(expected["response"])
            for value in (actual, reference):
                for trace in value["toolTraces"]:
                    trace.pop("elapsedMs")
            assert actual == reference
        assert callbacks.calls == expected["toolCalls"]
        assert provider.calls == expected["modelCalls"]
    asyncio.run(check())
