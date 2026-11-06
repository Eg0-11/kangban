package com.kangban.agent.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kangban.agent.*;
import com.kangban.client.AiClientException;
import com.kangban.client.AiToolDefinition;
import com.kangban.rag.KnowledgeSearchService;
import com.kangban.rag.PrivateKnowledgeSearchService;
import com.kangban.rag.RagSearchResult;
import com.kangban.rag.VectorStoreUnavailableException;
import com.kangban.service.ActionProposalService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Hidden
@RestController
@RequestMapping("/internal/agent/v1")
@RequiredArgsConstructor
public class InternalAgentController {
    private final AgentToolExecutor toolExecutor;
    private final KnowledgeSearchService knowledgeSearchService;
    private final PrivateKnowledgeSearchService privateKnowledgeSearchService;
    private final RagProperties ragProperties;
    private final ActionProposalService actionProposalService;
    private final AgentMetrics metrics;
    private final ObjectMapper mapper;

    @GetMapping("/tools")
    public List<AiToolDefinition> tools(@AuthenticationPrincipal InternalAgentCall call) {
        call.ensureActive();
        return toolExecutor.definitions();
    }

    @PostMapping("/tools/execute")
    public ToolExecutionResponse execute(@AuthenticationPrincipal InternalAgentCall call, @RequestBody JsonNode body) {
        requireFields(body, Set.of("name", "arguments"));
        String name = requireText(body, "name");
        if (!body.path("arguments").isObject()) throw InternalAgentException.invalidRequest();
        Map<String, Object> arguments = mapper.convertValue(body.get("arguments"), new TypeReference<>() { });
        AgentToolCall toolCall;
        try {
            toolCall = new AgentToolCall(name, arguments);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw InternalAgentException.invalidRequest();
        }
        call.ensureActive();
        long started = System.nanoTime();
        AgentToolResult result = toolExecutor.execute(call.context(), toolCall);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        metrics.recordToolCall("UNKNOWN_TOOL".equals(result.errorCode()) ? "unknown_tool" : name,
                result.status().name(), elapsedMs);
        call.ensureActive();
        return new ToolExecutionResponse(result, elapsedMs);
    }

    @PostMapping("/rag/search")
    public RagSearchResult search(@AuthenticationPrincipal InternalAgentCall call, @RequestBody JsonNode body) {
        requireFields(body, Set.of("query"));
        String query = requireText(body, "query");
        call.ensureActive();
        if (!ragProperties.isEnabled()) {
            throw new InternalAgentException(503, "RAG_DISABLED", "知识库检索暂未启用。", false);
        }
        try {
            RagSearchResult publicResult = knowledgeSearchService.search(query);
            call.ensureActive();
            RagSearchResult privateResult = ragProperties.isPrivateEnabled()
                    ? privateKnowledgeSearchService.search(query, call.context()) : RagSearchResult.empty();
            call.ensureActive();
            return RagSearchResult.merge(ragProperties, publicResult, privateResult);
        } catch (AiClientException | DataAccessException | VectorStoreUnavailableException exception) {
            throw new InternalAgentException(503, "RAG_UNAVAILABLE", "知识库检索暂时不可用，请稍后重试。", true);
        }
    }

    @PostMapping("/actions/propose")
    public ActionProposalService.Decision propose(@AuthenticationPrincipal InternalAgentCall call, @RequestBody JsonNode body) {
        requireFields(body, Set.of("message"));
        String message = requireText(body, "message");
        call.ensureActive();
        ActionProposalService.Decision decision = actionProposalService.propose(call.context(), message);
        call.ensureActive();
        return decision;
    }

    private void requireFields(JsonNode body, Set<String> fields) {
        if (body == null || !body.isObject() || body.size() != fields.size()) throw InternalAgentException.invalidRequest();
        body.fieldNames().forEachRemaining(field -> {
            if (!fields.contains(field)) throw InternalAgentException.invalidRequest();
        });
    }

    private String requireText(JsonNode body, String name) {
        JsonNode value = body.get(name);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw InternalAgentException.invalidRequest();
        return value.asText();
    }

    public record ToolExecutionResponse(AgentToolResult result, long elapsedMs) { }
}
