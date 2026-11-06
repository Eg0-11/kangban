"""Authenticated Python run API with fully simulated Java/model HTTP services."""

import asyncio
import base64
import copy
import hashlib
import hmac
import json
import time

import httpx
import pytest
from jsonschema import Draft202012Validator

from kangban_agent.errors import AgentError
from kangban_agent.main import create_app, with_disconnect_cancellation
from kangban_agent.settings import Settings

from test_service_skeleton import CONTRACT

SERVICE = "test-only-service-token"
KEY = b"test-only-java-signing-key"
SETTINGS = Settings(service_token=SERVICE, provider="qwen", java_base_url="http://java.test:8080",
                    api_url="https://model.test/chat", api_key="test-only-model-key", model="test-model")


def envelope():
    body = copy.deepcopy(CONTRACT["$defs"]["RunEnvelope"]["examples"][0])
    now = int(time.time())
    body["request"]["context"].update(issuedAtEpochSecond=now - 1, expiresAtEpochSecond=now + 300)
    return body


def encode_token(context):
    encoded = base64.urlsafe_b64encode(json.dumps(context, ensure_ascii=False).encode()).rstrip(b"=").decode()
    unsigned = "v1." + encoded
    signature = base64.urlsafe_b64encode(hmac.new(KEY, unsigned.encode(), hashlib.sha256).digest()).rstrip(b"=").decode()
    return unsigned + "." + signature


def headers(body, budget_ms=30_000):
    return {"X-Agent-Service-Token": SERVICE, "X-Agent-Context": encode_token(body["request"]["context"]),
            "X-Agent-Deadline-Epoch-Ms": str(int(time.time()*1000) + budget_ms)}


class Backend:
    def __init__(self, mode="normal", replies=None):
        self.mode = mode
        self.requests = []
        self.model_requests = []
        self.replies = replies or [{"content": "模拟回答"}]
        self.cancelled = False

    async def handle(self, request):
        self.requests.append(request)
        if request.url.host == "java.test":
            assert request.headers["X-Agent-Service-Token"] == SERVICE
            token = request.headers["X-Agent-Context"]
            unsigned, signature = token.rsplit(".", 1)
            expected = base64.urlsafe_b64encode(hmac.new(KEY, unsigned.encode(), hashlib.sha256).digest()).rstrip(b"=").decode()
            if not hmac.compare_digest(signature, expected):
                return httpx.Response(403, json={"contractVersion": "1", "code": "INVALID_CONTEXT", "message": "签名无效", "runId": None, "retryable": False})
            if self.mode == "slow":
                try:
                    await asyncio.sleep(10)
                except asyncio.CancelledError:
                    self.cancelled = True
                    raise
            if self.mode == "redirect":
                return httpx.Response(302, headers={"location": "https://untrusted.test/"})
            if self.mode == "bad_java":
                return httpx.Response(200, json={"content": "test-canary-private-response"})
            path = request.url.path
            if path.endswith("/actions/propose"):
                assert set(json.loads(request.content)) == {"message"}
                return httpx.Response(200, json={"handled": False, "content": None, "action": None})
            if path.endswith("/tools/execute"):
                call = json.loads(request.content)
                assert set(call) == {"name", "arguments"}
                blocked = self.mode == "blocked"
                return httpx.Response(200, json={"result": {"toolName": call["name"], "status": "BLOCKED" if blocked else "SUCCESS",
                    "content": "{\"records\":[]}", "errorCode": "ACCESS_DENIED" if blocked else ""}, "elapsedMs": 1})
            if path.endswith("/tools"):
                return httpx.Response(200, json=[{"name": "get_health_metrics", "description": "模拟工具", "parameters": {"type": "object", "properties": {}}}])
            if path.endswith("/rag/search"):
                return httpx.Response(200, json={"context": "", "hits": []})
            raise AssertionError(f"Unexpected Java path: {path}")
        assert request.url.host == "model.test", "Only simulated hosts may be called"
        assert request.headers["Authorization"] == "Bearer test-only-model-key"
        assert "X-Agent-Service-Token" not in request.headers and "X-Agent-Context" not in request.headers
        self.model_requests.append(json.loads(request.content))
        if self.mode == "provider_error":
            return httpx.Response(500, text="test-canary-sensitive-error")
        return httpx.Response(200, json={"choices": [{"message": self.replies[min(len(self.model_requests)-1, len(self.replies)-1)]}]})


async def request_run(body, backend, *, settings=SETTINGS, request_headers=None):
    app = create_app(settings, transport=httpx.MockTransport(backend.handle))
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://agent.test", trust_env=False) as client:
        return await client.post("/internal/agent/v1/run", json=body, headers=headers(body) if request_headers is None else request_headers)


def validate_response(response, definition):
    schema = {"$schema": CONTRACT["$schema"], "$defs": CONTRACT["$defs"], "$ref": "#/$defs/" + definition}
    Draft202012Validator(schema).validate(response.json())


def test_complete_deterministic_run_forwards_scope_and_preserves_wire_contract():
    backend = Backend()
    body = envelope()
    response = asyncio.run(request_run(body, backend))
    assert response.status_code == 200
    validate_response(response, "RunResultEnvelope")
    assert response.json()["response"]["content"] == "模拟回答"
    assert response.json()["response"]["runId"] == body["request"]["context"]["runId"]
    assert [request.url.path for request in backend.requests] == ["/internal/agent/v1/actions/propose", "/internal/agent/v1/tools/execute", "/chat"]
    for request in backend.requests[:2]:
        assert request.headers["X-Agent-Context"] == encode_token(body["request"]["context"])
        assert request.extensions["timeout"]["read"] <= 30


def test_model_tool_loop_and_call_id_history():
    backend = Backend(replies=[{"content": "", "tool_calls": [{"id": "", "function": {"name": "get_health_metrics", "arguments": "{\"limit\":2}"}}]}, {"content": "工具完成"}])
    body = envelope()
    body["options"]["modelToolCallingEnabled"] = True
    response = asyncio.run(request_run(body, backend))
    assert response.status_code == 200
    assert response.json()["response"]["toolTraces"][0]["iteration"] == 1
    history = backend.model_requests[1]["messages"]
    assert history[-2]["role"] == "assistant"
    assert history[-2]["tool_calls"][0]["id"] == "kangban-tool-1-0"
    assert history[-1]["tool_call_id"] == "kangban-tool-1-0"


@pytest.mark.parametrize("kind,status", [("missing_service", 401), ("wrong_service", 401), ("bad_token", 403),
    ("scope_mismatch", 403), ("missing_deadline", 400), ("bad_deadline", 400), ("expired_deadline", 504), ("excessive_deadline", 400)])
def test_invalid_transport_credentials_or_budget_never_call_dependencies(kind, status):
    body = envelope()
    request_headers = headers(body)
    if kind == "missing_service": request_headers.pop("X-Agent-Service-Token")
    if kind == "wrong_service": request_headers["X-Agent-Service-Token"] = "wrong"
    if kind == "bad_token": request_headers["X-Agent-Context"] = "invalid-token"
    if kind == "scope_mismatch": body["request"]["context"]["subjectUserId"] = 999
    if kind == "missing_deadline": request_headers.pop("X-Agent-Deadline-Epoch-Ms")
    if kind == "bad_deadline": request_headers["X-Agent-Deadline-Epoch-Ms"] = "tomorrow"
    if kind == "expired_deadline": request_headers["X-Agent-Deadline-Epoch-Ms"] = "1"
    if kind == "excessive_deadline": request_headers["X-Agent-Deadline-Epoch-Ms"] = str(int(time.time()*1000)+300_000)
    backend = Backend()
    response = asyncio.run(request_run(body, backend, request_headers=request_headers))
    assert response.status_code == status
    validate_response(response, "InternalError")
    assert not backend.requests


@pytest.mark.parametrize("kind", ["boolean_id", "float_id", "overflow_id", "string_option", "object_snapshot", "system_role", "extra_url", "version"])
def test_invalid_body_is_a_safe_400(kind):
    body = envelope()
    if kind == "boolean_id": body["request"]["context"]["actorUserId"] = True
    if kind == "float_id": body["request"]["context"]["actorUserId"] = 101.0
    if kind == "overflow_id": body["request"]["context"]["actorUserId"] = 2**63
    if kind == "string_option": body["options"]["maxIterations"] = "5"
    if kind == "object_snapshot": body["request"]["patientContextJson"] = {"private": "test-canary-body"}
    if kind == "system_role": body["request"]["history"][0]["role"] = "system"
    if kind == "extra_url": body["callbackUrl"] = "https://test-canary-body/"
    if kind == "version": body["contractVersion"] = "2"
    backend = Backend()
    response = asyncio.run(request_run(body, backend))
    assert response.status_code == 400
    assert "test-canary-body" not in response.text
    validate_response(response, "InternalError")
    assert not backend.requests


def test_java_remains_signature_authority_and_failed_signature_never_reaches_model():
    backend = Backend()
    body = envelope()
    request_headers = headers(body)
    request_headers["X-Agent-Context"] = request_headers["X-Agent-Context"].rsplit(".", 1)[0] + ".forged-signature"
    response = asyncio.run(request_run(body, backend, request_headers=request_headers))
    assert response.status_code == 403
    assert len(backend.requests) == 1
    assert not backend.model_requests


@pytest.mark.parametrize("mode,status", [("blocked", 403), ("bad_java", 502), ("redirect", 502), ("provider_error", 503)])
def test_dependency_failures_are_safe_and_not_retried(mode, status, caplog):
    backend = Backend(mode=mode)
    response = asyncio.run(request_run(envelope(), backend))
    assert response.status_code == status
    validate_response(response, "InternalError")
    assert "test-canary" not in response.text + caplog.text
    assert len(backend.model_requests) == (1 if mode == "provider_error" else 0)
    assert sum(request.url.path.endswith("/actions/propose") for request in backend.requests) == 1
    assert all(request.url.host in {"java.test", "model.test"} for request in backend.requests)


def test_total_deadline_cancels_inflight_callback():
    backend = Backend(mode="slow")
    body = envelope()
    response = asyncio.run(request_run(body, backend, request_headers=headers(body, budget_ms=150)))
    assert response.status_code == 504
    assert backend.cancelled
    assert len(backend.requests) == 1 and not backend.model_requests


def test_emergency_precedes_disabled_option_and_uses_no_dependencies():
    body = envelope()
    body["options"]["enabled"] = False
    body["request"]["message"] = "胸痛怎么办"
    backend = Backend()
    response = asyncio.run(request_run(body, backend))
    assert response.status_code == 200 and response.json()["outcome"] == "safety_emergency"
    assert not backend.requests


def test_large_integer_scope_is_preserved_without_float_conversion():
    body = envelope()
    body["request"]["context"].update(actorUserId=2**53+1, subjectUserId=2**53+1)
    backend = Backend()
    response = asyncio.run(request_run(body, backend))
    assert response.status_code == 200
    encoded = backend.requests[0].headers["X-Agent-Context"].split(".")[1]
    assert json.loads(base64.urlsafe_b64decode(encoded+"="*(-len(encoded)%4)))["actorUserId"] == 2**53+1


def test_missing_configuration_keeps_health_available_but_run_disabled():
    response = asyncio.run(request_run(envelope(), Backend(), settings=Settings()))
    assert response.status_code == 503 and response.json()["code"] == "INTERNAL_AGENT_UNAVAILABLE"


def test_service_identity_is_checked_before_malformed_body_is_parsed():
    async def check():
        backend = Backend()
        app = create_app(SETTINGS, transport=httpx.MockTransport(backend.handle))
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://agent.test") as client:
            response = await client.post("/internal/agent/v1/run", content="test-canary-malformed", headers={"Content-Type": "application/json"})
        assert response.status_code == 401
        validate_response(response, "InternalError")
        assert "test-canary" not in response.text and not backend.requests
    asyncio.run(check())


def test_wrong_http_method_uses_internal_error_shape():
    async def check():
        app = create_app(SETTINGS)
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://agent.test") as client:
            response = await client.get("/internal/agent/v1/run", headers={"X-Agent-Service-Token": SERVICE})
        assert response.status_code == 405
        validate_response(response, "InternalError")
    asyncio.run(check())


def test_disconnect_cancels_execution_and_closes_watcher():
    async def check():
        cancelled = False
        class Disconnected:
            async def is_disconnected(self):
                await asyncio.sleep(0.01)
                return True
        async def work():
            nonlocal cancelled
            try:
                await asyncio.sleep(10)
            finally:
                cancelled = True
        with pytest.raises(AgentError) as error:
            await with_disconnect_cancellation(Disconnected(), work())
        assert error.value.code == "CLIENT_DISCONNECTED" and cancelled
    asyncio.run(check())
