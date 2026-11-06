"""Single orchestration entry point ported from Java AgentOrchestrator."""

import json
import time

from .baseline_text import EVIDENCE_PREFIX, EVIDENCE_RULES_SUFFIX, NO_EVIDENCE_SUFFIX, PATIENT_FACTS_PREFIX, PATIENT_FACTS_SUFFIX
from .compat import java_blank, java_trim
from .errors import AgentError, no_content, unavailable
from .memory import prepare_history
from .models import AgentRequest, AgentResponse, AgentToolCall, AgentToolTrace, RagSearchResult, RunResultEnvelope, RuntimeOptions
from .planner import contains_any, plan_tools
from .providers import ModelToolCall
from .safety import assess, guard_response


def patient_data_question(message: str) -> bool:
    return contains_any(java_trim(message).lower(), "当前患者", "患者", "家属", "本人", "我的", "当前", "最近", "近30", "近7", "过去",
                        "健康数据", "健康指标", "健康变化", "异常项", "最近一次", "用药记录", "病历记录", "个人记录", "指标趋势", "健康趋势")


def fallback_to_evidence(content: str | None, rag: RagSearchResult) -> str | None:
    if content is None or not rag.hits or not contains_any(java_trim(content).lower(),
            "暂无足够依据", "没有足够依据", "知识库没有足够", "无法提供相关信息", "无法回答该问题", "无法为您提供具体"):
        return content
    output = "根据已发布知识库资料：\n"
    for index, hit in enumerate(rag.hits, 1):
        output += f"[资料{index}] {hit.content}\n"
    return output + "\n以上内容仅来自已发布知识库资料；如资料未覆盖你的具体药品或用法，请以药品说明书及医生或药师意见为准。"


class AgentOrchestrator:
    def __init__(self, callbacks, provider):
        self.callbacks, self.provider = callbacks, provider

    async def run(self, request: AgentRequest, options: RuntimeOptions) -> RunResultEnvelope:
        notice = assess(request.message)
        if notice:
            return self._response(request, notice, "safety_emergency")
        if not options.enabled:
            raise unavailable()
        if int(time.time()) >= request.context.expiresAtEpochSecond:
            raise AgentError(410, "CONTEXT_EXPIRED", "本次问诊上下文已失效，请重新发送。")

        decision = await self.callbacks.propose(request.message)
        if decision.handled:
            if java_blank(decision.content):
                raise AgentError(502, "INVALID_JAVA_RESPONSE", "内部业务服务返回无效响应。", True)
            return self._response(request, decision.content, "action_handled", actions=[decision.action] if decision.action else [])

        traces = []
        history = prepare_history(request.history, options)
        model_tools = options.modelToolCallingEnabled and self.provider.supports_tool_calling
        tool_context = ""
        if not model_tools:
            fragments = []
            for index, call in enumerate(plan_tools(request.message, options.maxIterations), 1):
                result = await self._execute(call, index, traces)
                fragments.append(f"[工具{index}：{call.name}]\n{result.content}\n\n")
            tool_context = java_trim("".join(fragments))

        provider_message = request.message
        if not java_blank(tool_context):
            provider_message += PATIENT_FACTS_PREFIX + tool_context + PATIENT_FACTS_SUFFIX
        rag = RagSearchResult()
        if options.ragEnabled:
            rag = await self.callbacks.search(request.message)
            if not rag.hits:
                if not patient_data_question(request.message) and java_blank(tool_context):
                    raise AgentError(503, "NO_EVIDENCE", "知识库没有足够依据回答该问题，请换个问法或联系专业医生。")
                provider_message += NO_EVIDENCE_SUFFIX
            else:
                provider_message += EVIDENCE_PREFIX + rag.context + EVIDENCE_RULES_SUFFIX

        if model_tools:
            content = await self._model_loop(request, options, provider_message, history, traces)
        else:
            content = await self.provider.consult(provider_message, request.patientContextJson, history)
        content = guard_response(fallback_to_evidence(content, rag))
        if java_blank(content):
            raise no_content()
        return self._response(request, content, "success", citations=[hit.citation for hit in rag.hits], toolTraces=traces)

    async def _execute(self, call: AgentToolCall, iteration: int, traces: list):
        execution = await self.callbacks.execute(call)
        result = execution.result
        traces.append(AgentToolTrace(toolName=call.name, status=result.status, iteration=iteration,
                                     elapsedMs=execution.elapsedMs, errorCode=result.errorCode))
        if result.status == "SUCCESS":
            return result
        if result.status == "BLOCKED":
            raise AgentError(403, result.errorCode, "当前账号无权读取该患者健康数据，请重新选择患者或检查家庭权限。")
        if result.errorCode in {"MCP_UNAVAILABLE", "MCP_PUBLIC_DISABLED", "MCP_TOOL_FAILED", "MCP_PUBLIC_INPUT_REJECTED"}:
            raise AgentError(503, result.errorCode, "天津公共医疗信息服务暂时不可用，无法可靠回答该问题。", True)
        if result.errorCode == "MCP_EMPTY_RESULT":
            raise AgentError(503, result.errorCode, "天津公共医疗目录暂无可靠数据，无法可靠回答该问题。")
        raise AgentError(503, result.errorCode or "TOOL_EXECUTION_FAILED", "患者健康数据工具暂时不可用，请稍后重试。", True)

    async def _model_loop(self, request, options, message, history, traces):
        definitions = await self.callbacks.definitions()
        if not definitions:
            return await self.provider.consult(message, request.patientContextJson, history)
        tool_history = []
        for iteration in range(1, max(1, options.maxIterations) + 1):
            reply = await self.provider.consult_with_tools(message, request.patientContextJson, history, definitions, tool_history)
            if reply is None:
                raise no_content()
            if not reply.tool_calls:
                return reply.content
            if iteration == max(1, options.maxIterations):
                raise AgentError(503, "MAX_ITERATIONS", "AI 工具调用次数已达上限，请缩小问题范围后重试。")
            normalized = []
            tool_messages = []
            for index, ai_call in enumerate(reply.tool_calls):
                call_id = ai_call.id if not java_blank(ai_call.id) else f"kangban-tool-{iteration}-{index}"
                normalized.append(ModelToolCall(call_id, ai_call.name, ai_call.arguments))
                result = await self._execute(AgentToolCall(name=ai_call.name, arguments=ai_call.arguments), iteration, traces)
                tool_messages.append({"role": "tool", "content": result.content, "tool_call_id": call_id})
            tool_history.append({"role": "assistant", "content": reply.content or "", "tool_calls": [
                {"id": call.id, "type": "function", "function": {"name": call.name,
                 "arguments": json.dumps(call.arguments, ensure_ascii=False, separators=(",", ":"))}} for call in normalized]})
            tool_history.extend(tool_messages)
        raise AgentError(503, "MAX_ITERATIONS", "AI 工具调用次数已达上限，请缩小问题范围后重试。")

    def _response(self, request, content, outcome, **values):
        return RunResultEnvelope(response=AgentResponse(content=content, runId=request.context.runId, **values), outcome=outcome)
