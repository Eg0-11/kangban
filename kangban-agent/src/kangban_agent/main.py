"""Python Agent HTTP entry point; Java remains the authority for patient data."""

import asyncio
import base64
import binascii
import hmac

import httpx
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.datastructures import Headers
from starlette.exceptions import HTTPException

from .budget import ExecutionBudget
from .callbacks import JavaCallbacks
from .errors import AgentError, deadline_exceeded, unavailable
from .models import AgentExecutionContext, HealthResponse, InternalError, RunEnvelope, RunResultEnvelope
from .orchestrator import AgentOrchestrator
from .providers import HttpProvider, MockProvider
from .settings import Settings


def require_service_identity(provided: str, settings: Settings):
    if not settings.service_token or settings.service_token.isspace():
        raise AgentError(503, "INTERNAL_AGENT_UNAVAILABLE", "内部 Agent 服务暂未配置。")
    if not hmac.compare_digest(provided.encode(), settings.service_token.encode()):
        raise AgentError(401, "INVALID_SERVICE_CREDENTIAL", "内部 Agent 服务身份无效。")


class ServiceIdentityMiddleware:
    """Authenticate internal requests before parsing their body, without consuming ASGI receive."""
    def __init__(self, app, settings: Settings):
        self.app, self.settings = app, settings

    async def __call__(self, scope, receive, send):
        path = scope.get("path", "")
        if scope["type"] == "http" and (path == "/internal/agent/v1" or path.startswith("/internal/agent/v1/")):
            try:
                require_service_identity(Headers(scope=scope).get("X-Agent-Service-Token", ""), self.settings)
            except AgentError as error:
                payload = InternalError(code=error.code, message=error.message, runId=None, retryable=error.retryable)
                await JSONResponse(payload.model_dump(), status_code=error.status, headers={"Cache-Control": "no-store"})(scope, receive, send)
                return
        await self.app(scope, receive, send)


def authorize(request: Request, envelope: RunEnvelope, settings: Settings):
    require_service_identity(request.headers.get("X-Agent-Service-Token", ""), settings)
    token = request.headers.get("X-Agent-Context", "")
    try:
        version, encoded, signature = token.split(".")
        if version != "v1" or not signature or len(token) > 8192:
            raise ValueError("Invalid context token")
        payload = base64.b64decode(encoded + "=" * (-len(encoded) % 4), altchars=b"-_", validate=True)
        decoded = AgentExecutionContext.model_validate_json(payload)
        if decoded != envelope.request.context:
            raise ValueError("Context mismatch")
    except (ValueError, binascii.Error):
        raise AgentError(403, "INVALID_CONTEXT", "内部 Agent 上下文无效。") from None
    # Java verifies the signature on callbacks before any patient access/model call.
    deadline = request.headers.get("X-Agent-Deadline-Epoch-Ms", "")
    if not deadline.isascii() or not deadline.isdecimal() or len(deadline) > 19:
        raise AgentError(400, "INVALID_DEADLINE", "内部 Agent 截止时间无效。")
    budget = ExecutionBudget(int(deadline), settings.max_request_ms)
    request.state.run_id = envelope.request.context.runId
    return token, budget


async def with_disconnect_cancellation(request: Request, awaitable):
    stopped = asyncio.Event()

    async def watch():
        while not stopped.is_set():
            if await request.is_disconnected():
                return
            if stopped.is_set():
                return
            await asyncio.sleep(0.05)

    task = asyncio.create_task(awaitable)
    watcher = asyncio.create_task(watch())
    try:
        done, _ = await asyncio.wait({task, watcher}, return_when=asyncio.FIRST_COMPLETED)
        if task in done:
            return await task
        raise AgentError(499, "CLIENT_DISCONNECTED", "本次请求已结束。", True)
    finally:
        stopped.set()
        for pending in (task, watcher):
            if not pending.done():
                pending.cancel()
        await asyncio.gather(task, watcher, return_exceptions=True)


def create_app(settings: Settings | None = None, *, transport: httpx.AsyncBaseTransport | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    app = FastAPI(title="Kangban Agent", version="0.1.0", docs_url=None, redoc_url=None, openapi_url=None)
    app.add_middleware(ServiceIdentityMiddleware, settings=settings)

    @app.exception_handler(AgentError)
    async def agent_error(request: Request, error: AgentError):
        payload = InternalError(code=error.code, message=error.message,
                                runId=getattr(request.state, "run_id", None), retryable=error.retryable)
        return JSONResponse(payload.model_dump(), status_code=error.status, headers={"Cache-Control": "no-store"})

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request: Request, error: RequestValidationError):
        return await agent_error(request, AgentError(400, "INVALID_REQUEST", "内部 Agent 请求格式无效。"))

    @app.exception_handler(HTTPException)
    async def http_error(request: Request, error: HTTPException):
        code = "NOT_FOUND" if error.status_code == 404 else "METHOD_NOT_ALLOWED" if error.status_code == 405 else "INVALID_REQUEST"
        return await agent_error(request, AgentError(error.status_code, code, "内部 Agent 请求路径或方法无效。"))

    @app.get("/health", response_model=HealthResponse)
    async def health():
        return HealthResponse()

    @app.post("/internal/agent/v1/run", response_model=RunResultEnvelope)
    async def run(envelope: RunEnvelope, request: Request):
        token, budget = authorize(request, envelope, settings)

        async def execute():
            try:
                async with asyncio.timeout(budget.remaining()):
                    async with httpx.AsyncClient(transport=transport, trust_env=False, follow_redirects=False) as callback_client, \
                               httpx.AsyncClient(transport=transport, trust_env=False, follow_redirects=False) as model_client:
                        callbacks = JavaCallbacks(callback_client, settings, token, budget)
                        provider = MockProvider() if settings.provider == "mock" else HttpProvider(model_client, settings, budget)
                        return await AgentOrchestrator(callbacks, provider).run(envelope.request, envelope.options)
            except TimeoutError:
                raise deadline_exceeded() from None
            except AgentError:
                raise
            except Exception:
                raise unavailable() from None

        return await with_disconnect_cancellation(request, execute())

    return app
