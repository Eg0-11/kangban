"""Mock, Qwen and DeepSeek adapters preserving the existing chat request shapes."""

import asyncio
import json
from dataclasses import dataclass, field
from typing import Any

import httpx

from .baseline_text import DEEPSEEK_SYSTEM_PROMPT, MOCK_REPLIES, QWEN_SYSTEM_PROMPT
from .budget import ExecutionBudget
from .compat import java_blank, java_wire_text
from .errors import AgentError, deadline_exceeded, no_content, unavailable
from .models import AiToolDefinition, ConversationMessage
from .settings import Settings


def node_text(value, null_default: str = "null") -> str:
    """Jackson JsonNode.asText compatibility for OpenAI-compatible response fields."""
    if value is None:
        return null_default
    if isinstance(value, str):
        return value
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return str(value)
    return ""


@dataclass
class ModelToolCall:
    id: str
    name: str
    arguments: dict[str, Any]


@dataclass
class ModelReply:
    content: str
    tool_calls: list[ModelToolCall] = field(default_factory=list)


def system_prompt(provider: str, patient_data: str | None) -> str:
    prompt = QWEN_SYSTEM_PROMPT if provider == "qwen" else DEEPSEEK_SYSTEM_PROMPT
    if not java_blank(patient_data) and patient_data != "{}":
        prompt += (" 当前患者数据库快照：" if provider == "qwen" else " 患者信息：") + patient_data
    return prompt


def conversation_messages(provider: str, message: str, patient_data: str | None, history: list[ConversationMessage]):
    messages = [{"role": "system", "content": system_prompt(provider, patient_data)}]
    messages.extend({"role": item.role, "content": java_wire_text(item.content)} for item in history
                    if item.role in {"user", "assistant"} and not java_blank(item.content))
    messages.append({"role": "user", "content": message})
    return messages


class MockProvider:
    supports_tool_calling = False

    def __init__(self, delay: float = 1.5):
        self.delay = delay

    async def consult(self, message: str, patient_data: str | None, history: list[ConversationMessage]) -> str:
        await asyncio.sleep(self.delay)
        query = message.lower()
        for words, name in [(('发烧', '发热', '体温'), 'fever'), (('头疼', '头痛'), 'headache'),
                            (('血压', '高血压'), 'blood_pressure'), (('血糖', '糖尿病'), 'blood_glucose'),
                            (('感冒', '咳嗽'), 'cold')]:
            if any(word in query for word in words):
                return MOCK_REPLIES[name]
        return MOCK_REPLIES["general"]


class HttpProvider:
    def __init__(self, client: httpx.AsyncClient, settings: Settings, budget: ExecutionBudget):
        self.client, self.settings, self.budget = client, settings, budget
        self.supports_tool_calling = settings.provider == "qwen"

    async def _send(self, body: dict):
        if java_blank(self.settings.api_key):
            if self.settings.provider == "qwen":
                raise AgentError(503, "PROVIDER_NOT_CONFIGURED", "AI 服务暂未配置，请联系管理员。")
        try:
            raw = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8", errors="replace")
            response = await self.client.post(self.settings.api_url, content=raw,
                headers={"Authorization": "Bearer " + self.settings.api_key, "Content-Type": "application/json"},
                timeout=self.budget.timeout(self.settings.connect_timeout_ms, self.settings.read_timeout_ms))
        except httpx.TimeoutException:
            if self.settings.provider == "deepseek":
                return "[AI服务连接失败] 响应超时，请稍后重试。"
            raise deadline_exceeded() from None
        except httpx.HTTPError:
            if self.settings.provider == "deepseek":
                return "[AI服务连接失败] 连接异常，请稍后重试。"
            raise unavailable() from None
        self.budget.remaining()
        if response.status_code != 200:
            if self.settings.provider == "deepseek":
                return f"[AI服务暂时不可用] 状态码: {response.status_code}，请稍后重试。"
            if response.status_code == 429:
                raise AgentError(503, "PROVIDER_BUSY", "当前请求较多，请稍后重试。", True)
            if response.status_code in {408, 504}:
                raise deadline_exceeded()
            raise unavailable()
        try:
            document = response.json()
            if not isinstance(document, dict):
                return {} if self.settings.provider == "qwen" else "[AI服务连接失败] 响应格式无效，请稍后重试。"
            return document
        except ValueError:
            if self.settings.provider == "deepseek":
                return "[AI服务连接失败] 响应格式无效，请稍后重试。"
            raise unavailable() from None

    async def consult(self, message: str, patient_data: str | None, history: list[ConversationMessage]) -> str:
        payload = {"model": self.settings.model, "messages": conversation_messages(self.settings.provider, message, patient_data, history),
                   "temperature": 0.7, "max_tokens": 1024}
        result = await self._send(payload)
        if isinstance(result, str):
            return result
        choices = result.get("choices")
        if not isinstance(choices, list) or not choices:
            if self.settings.provider == "deepseek":
                return "[AI服务连接失败] 响应格式无效，请稍后重试。"
            content = ""
        else:
            message_node = choices[0].get("message") if isinstance(choices[0], dict) else None
            content = message_node.get("content", "") if isinstance(message_node, dict) else ""
        content = node_text(content)
        if java_blank(content) and self.settings.provider == "qwen":
            raise no_content()
        return content

    async def consult_with_tools(self, message: str, patient_data: str | None, history: list[ConversationMessage],
                                 definitions: list[AiToolDefinition], tool_history: list[dict]) -> ModelReply:
        messages = conversation_messages("qwen", message, patient_data, history) + tool_history
        body = {"model": self.settings.model, "messages": messages,
                "tools": [{"type": "function", "function": item.model_dump()} for item in definitions],
                "tool_choice": "auto", "temperature": 0.2, "max_tokens": 1024}
        result = await self._send(body)
        try:
            choices = result.get("choices")
            message_node = choices[0].get("message", {}) if isinstance(choices, list) and choices and isinstance(choices[0], dict) else {}
            if not isinstance(message_node, dict):
                message_node = {}
            content = node_text(message_node.get("content"), "")
            calls = []
            for call in message_node.get("tool_calls", []) if isinstance(message_node.get("tool_calls"), list) else []:
                function = call.get("function", {})
                name = node_text(function.get("name"), "")
                if java_blank(name):
                    raise AgentError(503, "INVALID_TOOL_CALL", "AI 返回了无效的工具调用。")
                arguments = function.get("arguments", {})
                if isinstance(arguments, str):
                    arguments = json.loads(arguments)
                if arguments is None:
                    arguments = {}
                if not isinstance(arguments, dict) or any(value is None for value in arguments.values()):
                    raise unavailable()
                identifier = node_text(call.get("id"), "")
                calls.append(ModelToolCall(identifier, name, arguments))
        except (KeyError, IndexError, TypeError, ValueError, AttributeError):
            raise unavailable() from None
        if java_blank(content) and not calls:
            raise no_content()
        return ModelReply(content, calls)
