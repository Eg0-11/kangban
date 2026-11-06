package com.kangban.agent.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public record InternalAgentError(String contractVersion, String code, String message,
                                 String runId, boolean retryable) {
    public static InternalAgentError from(InternalAgentException error, String runId) {
        return new InternalAgentError("1", error.code(), error.getMessage(), runId, error.retryable());
    }

    public static void write(HttpServletResponse response, ObjectMapper mapper,
                             InternalAgentException error, String runId) throws IOException {
        response.setStatus(error.status());
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(response.getWriter(), from(error, runId));
    }
}
