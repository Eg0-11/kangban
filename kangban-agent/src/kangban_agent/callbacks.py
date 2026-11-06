"""Typed calls to Java capabilities. Patient identities never enter callback bodies."""

from typing import TypeVar

import httpx
from pydantic import TypeAdapter, ValidationError

from .budget import ExecutionBudget
from .errors import AgentError, deadline_exceeded, unavailable
from .models import AiToolDefinition, AgentToolCall, InternalError, ProposalDecision, RagSearchResult, ToolExecutionResponse
from .settings import Settings

T = TypeVar("T")


class JavaCallbacks:
    def __init__(self, client: httpx.AsyncClient, settings: Settings, context_token: str, budget: ExecutionBudget):
        self.client = client
        self.settings = settings
        self.budget = budget
        self.headers = {
            "X-Agent-Service-Token": settings.service_token,
            "X-Agent-Context": context_token,
            "X-Agent-Deadline-Epoch-Ms": str(budget.deadline_epoch_ms),
        }

    async def _request(self, method: str, path: str, response_type: type[T], body=None) -> T:
        self.settings.validate_run_configuration()
        try:
            response = await self.client.request(method, self.settings.java_base_url.rstrip("/") + "/internal/agent/v1/" + path,
                headers=self.headers, json=body, timeout=self.budget.timeout(self.settings.connect_timeout_ms, self.settings.read_timeout_ms))
        except httpx.TimeoutException:
            raise deadline_exceeded() from None
        except httpx.HTTPError:
            raise unavailable() from None
        self.budget.remaining()
        try:
            if response.status_code != 200:
                error = InternalError.model_validate_json(response.content)
                if response.status_code not in {400, 401, 403, 410, 500, 503, 504}:
                    raise ValueError("Unexpected status")
                # Java emits controlled error text. Keep codes, but select safe local notices.
                notices = {
                    "CONTEXT_EXPIRED": "本次问诊上下文已失效，请重新发送。",
                    "PATIENT_SCOPE_DENIED": "当前账号无权读取该患者健康数据，请重新选择患者或检查家庭权限。",
                    "RAG_UNAVAILABLE": "知识库检索暂时不可用，请稍后重试。",
                    "RAG_DISABLED": "知识库检索暂未启用。",
                    "DEADLINE_EXCEEDED": "AI 响应超时，请重试。",
                }
                raise AgentError(response.status_code, error.code, notices.get(error.code, "AI 服务暂时不可用，请稍后重试。"), error.retryable)
            return TypeAdapter(response_type).validate_json(response.content, strict=True)
        except (ValidationError, ValueError):
            raise AgentError(502, "INVALID_JAVA_RESPONSE", "内部业务服务返回无效响应。", True) from None

    async def definitions(self) -> list[AiToolDefinition]:
        return await self._request("GET", "tools", list[AiToolDefinition])

    async def execute(self, call: AgentToolCall) -> ToolExecutionResponse:
        response = await self._request("POST", "tools/execute", ToolExecutionResponse, call.model_dump())
        if response.result.toolName != call.name:
            raise AgentError(502, "INVALID_JAVA_RESPONSE", "内部业务服务返回无效响应。", True)
        return response

    async def search(self, message: str) -> RagSearchResult:
        return await self._request("POST", "rag/search", RagSearchResult, {"query": message})

    async def propose(self, message: str) -> ProposalDecision:
        return await self._request("POST", "actions/propose", ProposalDecision, {"message": message})
