package com.kangban.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Set;

/** Typed v1 request and validation before legacy records can normalize invalid response values. */
final class PythonAgentProtocol {
    record Options(boolean enabled, boolean modelToolCallingEnabled, boolean ragEnabled, int maxIterations,
                   int maxHistoryMessages, int maxHistoryTokens, int maxHistoryMessageCharacters) { }
    record RunEnvelope(String contractVersion, AgentRequest request, Options options) { }
    record Result(String contractVersion, AgentResponse response, String outcome) { }

    static Result result(ObjectMapper mapper, JsonNode root, String runId) throws IOException {
        fields(root, "contractVersion", "response", "outcome");
        text(root, "contractVersion", false);
        text(root, "outcome", false);
        require("1".equals(root.get("contractVersion").textValue()));
        require(Set.of("success", "safety_emergency", "action_handled").contains(root.get("outcome").textValue()));
        JsonNode response = root.get("response");
        fields(response, "content", "runId", "citations", "actions", "toolTraces");
        text(response, "content", false);
        text(response, "runId", false);
        require(!response.get("content").textValue().isBlank() && runId.equals(response.get("runId").textValue()));
        for (String key : new String[]{"citations", "actions", "toolTraces"}) require(response.get(key).isArray());
        for (JsonNode citation : response.get("citations")) {
            fields(citation, "documentId", "title", "version", "pageNumber", "section", "source", "updatedAt", "scope");
            for (String key : new String[]{"documentId", "title", "scope"}) text(citation, key, false);
            for (String key : new String[]{"version", "section", "source", "updatedAt"}) text(citation, key, true);
            JsonNode page = citation.get("pageNumber");
            require(page.isNull() || page.isIntegralNumber() && page.canConvertToInt());
            require(Set.of("PUBLIC", "PRIVATE").contains(citation.get("scope").textValue()));
        }
        for (JsonNode action : response.get("actions")) {
            fields(action, "id", "type", "status", "parameters", "payloadHash", "expiresAt");
            for (String key : new String[]{"id", "type", "status"}) text(action, key, false);
            text(action, "payloadHash", true);
            text(action, "expiresAt", true);
            require(action.get("parameters").isObject());
        }
        for (JsonNode trace : response.get("toolTraces")) {
            fields(trace, "toolName", "status", "iteration", "elapsedMs", "errorCode");
            for (String key : new String[]{"toolName", "status", "errorCode"}) text(trace, key, false);
            JsonNode iteration = trace.get("iteration"), elapsed = trace.get("elapsedMs");
            require(iteration.isIntegralNumber() && iteration.canConvertToInt() && iteration.intValue() >= 1);
            require(elapsed.isIntegralNumber() && elapsed.canConvertToLong() && elapsed.longValue() >= 0);
        }
        return mapper.treeToValue(root, Result.class);
    }

    static void fields(JsonNode node, String... names) throws IOException {
        require(node != null && node.isObject() && node.size() == names.length);
        for (String name : names) require(node.has(name));
    }

    static void text(JsonNode node, String name, boolean nullable) throws IOException {
        JsonNode value = node.get(name);
        require(value != null && (value.isTextual() || nullable && value.isNull()));
    }

    static void require(boolean condition) throws IOException {
        if (!condition) throw new IOException("Invalid internal Agent response");
    }
}
