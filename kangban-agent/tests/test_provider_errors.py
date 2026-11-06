"""Provider error compatibility, safe notices and tool-call parsing boundaries."""

import asyncio
import time

import httpx
import pytest

from kangban_agent.budget import ExecutionBudget
from kangban_agent.errors import AgentError
from kangban_agent.models import AiToolDefinition
from kangban_agent.providers import HttpProvider
from kangban_agent.settings import Settings


def run_provider(provider_name, response=None, failure=None, tools=False, api_key="test-only-key"):
    async def run():
        calls = []
        def respond(request):
            calls.append(request)
            if failure:
                raise failure
            return response
        settings = Settings(provider=provider_name, api_key=api_key)
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            provider = HttpProvider(client, settings, ExecutionBudget(int(time.time()*1000)+30_000))
            if tools:
                value = await provider.consult_with_tools("模拟问题", "{}", [], [AiToolDefinition(name="get_health_metrics", description="", parameters={})], [])
            else:
                value = await provider.consult("模拟问题", "{}", [])
        return value, calls
    return asyncio.run(run())


@pytest.mark.parametrize("status,code", [(429, "PROVIDER_BUSY"), (408, "DEADLINE_EXCEEDED"), (504, "DEADLINE_EXCEEDED"), (500, "AGENT_UNAVAILABLE")])
def test_qwen_http_errors_are_safe(status, code):
    with pytest.raises(AgentError) as error:
        run_provider("qwen", httpx.Response(status, text="test-canary-upstream-body"))
    assert error.value.code == code and "test-canary" not in error.value.message


@pytest.mark.parametrize("failure,code", [(httpx.ReadTimeout("test-canary-timeout"), "DEADLINE_EXCEEDED"),
                                         (httpx.ConnectError("test-canary-connect"), "AGENT_UNAVAILABLE")])
def test_qwen_network_errors_are_redacted(failure, code):
    with pytest.raises(AgentError) as error:
        run_provider("qwen", failure=failure)
    assert error.value.code == code and "test-canary" not in error.value.message


def test_qwen_missing_key_never_sends_request():
    with pytest.raises(AgentError) as error:
        run_provider("qwen", api_key="")
    assert error.value.code == "PROVIDER_NOT_CONFIGURED"


@pytest.mark.parametrize("tools", [False, True])
@pytest.mark.parametrize("payload", [{"choices": []}, {}, {"choices": [{"message": {"content": ""}}]}])
def test_qwen_empty_responses_preserve_empty_notice(tools, payload):
    with pytest.raises(AgentError) as error:
        run_provider("qwen", httpx.Response(200, json=payload), tools=tools)
    assert error.value.code == "EMPTY_RESPONSE"


@pytest.mark.parametrize("provider", ["qwen", "deepseek"])
def test_plain_null_response_matches_jackson_text(provider):
    value, _ = run_provider(provider, httpx.Response(200, json={"choices": [{"message": {"content": None}}]}))
    assert value == "null"


def test_qwen_parses_object_arguments_and_null_content():
    payload = {"choices": [{"message": {"content": None, "tool_calls": [
        {"id": "call-1", "function": {"name": "get_health_metrics", "arguments": {"limit": 2}}}]}}]}
    reply, _ = run_provider("qwen", httpx.Response(200, json=payload), tools=True)
    assert reply.content == "" and reply.tool_calls[0].arguments == {"limit": 2}


@pytest.mark.parametrize("arguments", ["{invalid", "[]", 42])
def test_invalid_tool_arguments_fail_without_echo(arguments):
    payload = {"choices": [{"message": {"content": "", "tool_calls": [
        {"id": "call-1", "function": {"name": "get_health_metrics", "arguments": arguments}}]}}]}
    with pytest.raises(AgentError):
        run_provider("qwen", httpx.Response(200, json=payload), tools=True)


def test_missing_tool_name_uses_original_notice():
    payload = {"choices": [{"message": {"content": "", "tool_calls": [{"function": {"arguments": "{}"}}]}}]}
    with pytest.raises(AgentError) as error:
        run_provider("qwen", httpx.Response(200, json=payload), tools=True)
    assert error.value.message == "AI 返回了无效的工具调用。"


@pytest.mark.parametrize("status", [401, 429, 500])
def test_deepseek_http_failure_keeps_existing_text_semantics(status):
    value, _ = run_provider("deepseek", httpx.Response(status, text="test-canary-sensitive-error"))
    assert value == f"[AI服务暂时不可用] 状态码: {status}，请稍后重试。"


@pytest.mark.parametrize("failure", [httpx.ReadTimeout("test-canary-timeout"), httpx.ConnectError("test-canary-connect")])
def test_deepseek_connection_failure_keeps_prefix_without_private_detail(failure):
    value, _ = run_provider("deepseek", failure=failure)
    assert value.startswith("[AI服务连接失败]") and "test-canary" not in value


@pytest.mark.parametrize("provider", ["qwen", "deepseek"])
def test_invalid_provider_json_is_safe(provider):
    if provider == "qwen":
        with pytest.raises(AgentError):
            run_provider(provider, httpx.Response(200, text="test-canary-json"))
    else:
        value, _ = run_provider(provider, httpx.Response(200, text="test-canary-json"))
        assert value.startswith("[AI服务连接失败]") and "test-canary" not in value


@pytest.mark.parametrize("provider", ["qwen", "deepseek"])
@pytest.mark.parametrize("payload", [[], "test-canary-root", {"choices": [{"message": None}]}])
def test_invalid_or_empty_message_structure_is_not_exposed_as_answer(provider, payload):
    if provider == "qwen":
        with pytest.raises(AgentError) as error:
            run_provider(provider, httpx.Response(200, json=payload))
        assert error.value.code == "EMPTY_RESPONSE"
    else:
        value, _ = run_provider(provider, httpx.Response(200, json=payload))
        assert value == "" or value.startswith("[AI服务连接失败]")
        assert "test-canary" not in value
