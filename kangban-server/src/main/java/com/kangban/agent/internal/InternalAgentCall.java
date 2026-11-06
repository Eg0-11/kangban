package com.kangban.agent.internal;

import com.kangban.agent.AgentExecutionContext;

/** 仅由内部认证边界创建，不从 HTTP 正文反序列化。 */
public record InternalAgentCall(AgentExecutionContext context, long deadlineEpochMillis) {
    public void ensureActive() {
        long now = System.currentTimeMillis();
        if (context.expiredAt(now / 1000)) {
            throw new InternalAgentException(410, "CONTEXT_EXPIRED", "本次问诊上下文已失效，请重新发送。", false);
        }
        if (now >= deadlineEpochMillis) {
            throw new InternalAgentException(504, "DEADLINE_EXCEEDED", "AI 响应超时，请重试。", true);
        }
    }
}
