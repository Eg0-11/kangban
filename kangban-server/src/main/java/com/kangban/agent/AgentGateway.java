package com.kangban.agent;

/** Consultation's runtime boundary; production uses the Python HTTP implementation. */
public interface AgentGateway {
    AgentExecutionContext createContext(Long actorUserId, Long subjectUserId, Long memberId, Long sessionId);

    AgentResponse run(AgentRequest request, AgentRunCancellation cancellation);

    default AgentResponse run(AgentRequest request) {
        return run(request, new AgentRunCancellation());
    }
}
