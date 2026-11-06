package com.kangban.agent;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kangban.agent.internal.InternalAgentProperties;
import com.kangban.client.AiClientException;
import jakarta.annotation.PreDestroy;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.Proxy;
import java.net.URI;
import java.util.concurrent.TimeUnit;

/** Production consultation gateway. There is no legacy fallback or automatic HTTP retry. */
@Component
public class PythonAgentGateway implements AgentGateway {
    private static final Logger log = LoggerFactory.getLogger(PythonAgentGateway.class);
    private static final String UNAVAILABLE = "AI 服务暂时不可用，请稍后重试。";
    private static final String TIMEOUT = "AI 响应超时，请重试。";
    private final AgentExecutionContextFactory contextFactory;
    private final AgentContextSigner signer;
    private final AgentProperties agent;
    private final RagProperties rag;
    private final InternalAgentProperties internal;
    private final AgentMetrics metrics;
    private final ObjectMapper mapper;
    private final OkHttpClient client;

    public PythonAgentGateway(AgentExecutionContextFactory contextFactory, AgentContextSigner signer,
                              AgentProperties agent, RagProperties rag, InternalAgentProperties internal,
                              AgentMetrics metrics, ObjectMapper mapper) {
        this.contextFactory = contextFactory;
        this.signer = signer;
        this.agent = agent;
        this.rag = rag;
        this.internal = internal;
        this.metrics = metrics;
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.client = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                // The Python server can close idle sockets before the next Agent request.
                .connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS))
                .connectTimeout(Math.max(1, Math.min(170_000, internal.getConnectTimeoutMilliseconds())), TimeUnit.MILLISECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS).writeTimeout(0, TimeUnit.MILLISECONDS).build();
    }

    @Override
    public AgentExecutionContext createContext(Long actor, Long subject, Long member, Long session) {
        return contextFactory.create(actor, subject, member, session);
    }

    @Override
    public AgentResponse run(AgentRequest request, AgentRunCancellation cancellation) {
        long started = System.nanoTime();
        String outcome = "failure";
        try {
            cancellation.checkActive();
            if (!isConfigured()) throw new AiClientException(UNAVAILABLE);
            long now = System.currentTimeMillis();
            if (request.context().expiredAt(now / 1000)) {
                throw new AiClientException("本次问诊上下文已失效，请重新发送。");
            }
            long deadline = Math.min(now + Math.min(170_000, internal.getMaxRequestMilliseconds()),
                    Math.multiplyExact(request.context().expiresAtEpochSecond(), 1000));
            var options = new PythonAgentProtocol.Options(agent.isEnabled(), agent.isModelToolCallingEnabled(),
                    rag.isEnabled(), agent.getMaxIterations(), agent.getMaxHistoryMessages(),
                    agent.getMaxHistoryTokens(), agent.getMaxHistoryMessageCharacters());
            byte[] body = mapper.writeValueAsBytes(new PythonAgentProtocol.RunEnvelope("1", request, options));
            Request httpRequest = new Request.Builder().url(endpoint("/internal/agent/v1/run"))
                    .header("X-Agent-Service-Token", internal.getServiceToken())
                    .header("X-Agent-Context", signer.sign(request.context()))
                    .header("X-Agent-Deadline-Epoch-Ms", Long.toString(deadline))
                    .post(RequestBody.create(body, MediaType.get("application/json; charset=utf-8"))).build();
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) throw new AiClientException(TIMEOUT);
            Call call = client.newCall(httpRequest);
            call.timeout().timeout(remaining, TimeUnit.MILLISECONDS);
            cancellation.attach(call::cancel);
            try (Response response = call.execute()) {
                if (response.body() == null) throw new IOException("Missing response");
                JsonNode json = mapper.readTree(response.body().bytes());
                if (response.code() != 200) throw remoteFailure(json, request.context().runId());
                PythonAgentProtocol.Result result = PythonAgentProtocol.result(mapper, json, request.context().runId());
                outcome = result.outcome();
                log.info("Python Agent run done: runId={}, traceId={}, outcome={}",
                        request.context().runId(), request.context().traceId(), outcome);
                return result.response();
            }
        } catch (InterruptedIOException exception) {
            throw new AiClientException(TIMEOUT);
        } catch (AiClientException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            // Never log an upstream body, URL, signature, patient content or exception message.
            throw new AiClientException(UNAVAILABLE);
        } finally {
            cancellation.detach();
            if (!"action_handled".equals(outcome)) {
                metrics.recordAgentRun(outcome, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }
        }
    }

    private AiClientException remoteFailure(JsonNode error, String runId) throws IOException {
        PythonAgentProtocol.fields(error, "contractVersion", "code", "message", "runId", "retryable");
        for (String key : new String[]{"contractVersion", "code", "message"}) PythonAgentProtocol.text(error, key, false);
        PythonAgentProtocol.text(error, "runId", true);
        PythonAgentProtocol.require("1".equals(error.get("contractVersion").textValue()) && error.get("retryable").isBoolean());
        PythonAgentProtocol.require(error.get("runId").isNull() || runId.equals(error.get("runId").textValue()));
        // Messages are selected locally. Untrusted error bodies cannot become SSE content.
        String message = switch (error.get("code").textValue()) {
            case "CONTEXT_EXPIRED" -> "本次问诊上下文已失效，请重新发送。";
            case "DEADLINE_EXCEEDED" -> TIMEOUT;
            case "CLIENT_DISCONNECTED" -> "本次请求已结束。";
            case "EMPTY_RESPONSE" -> "AI 服务暂未返回有效内容，请稍后重试。";
            case "PROVIDER_BUSY" -> "当前请求较多，请稍后重试。";
            case "PROVIDER_NOT_CONFIGURED" -> "AI 服务暂未配置，请联系管理员。";
            case "NO_EVIDENCE" -> "知识库没有足够依据回答该问题，请换个问法或联系专业医生。";
            case "RAG_UNAVAILABLE" -> "知识库检索暂时不可用，请稍后重试。";
            case "RAG_DISABLED" -> "知识库检索暂未启用。";
            case "PATIENT_SCOPE_DENIED", "FORBIDDEN", "WRITE_TOOL_NOT_ALLOWED", "ACCESS_DENIED", "PATIENT_SCOPE_INVALID" -> "当前账号无权读取该患者健康数据，请重新选择患者或检查家庭权限。";
            case "MCP_UNAVAILABLE", "MCP_PUBLIC_DISABLED", "MCP_TOOL_FAILED", "MCP_PUBLIC_INPUT_REJECTED" ->
                    "天津公共医疗信息服务暂时不可用，无法可靠回答该问题。";
            case "MCP_EMPTY_RESULT" -> "天津公共医疗目录暂无可靠数据，无法可靠回答该问题。";
            case "MAX_ITERATIONS" -> "AI 工具调用次数已达上限，请缩小问题范围后重试。";
            case "TOOL_EXECUTION_FAILED", "UNKNOWN_TOOL", "EMPTY_TOOL_RESULT", "INVALID_TOOL_CALL", "SNAPSHOT_FORMAT_INVALID" -> "患者健康数据工具暂时不可用，请稍后重试。";
            default -> UNAVAILABLE;
        };
        return new AiClientException(message);
    }

    public boolean isConfigured() {
        try {
            URI base = URI.create(internal.getPythonBaseUrl());
            return internal.isEnabled() && internal.getMaxRequestMilliseconds() > 0
                    && internal.getConnectTimeoutMilliseconds() > 0
                    && internal.getServiceToken() != null && !internal.getServiceToken().isBlank()
                    && agent.getContextSecret() != null && !agent.getContextSecret().isBlank()
                    && !internal.getServiceToken().equals(agent.getContextSecret())
                    && ("http".equals(base.getScheme()) || "https".equals(base.getScheme()))
                    && base.getHost() != null && base.getUserInfo() == null
                    && (base.getPort() == -1 || base.getPort() > 0 && base.getPort() <= 65535)
                    && base.getQuery() == null && base.getFragment() == null
                    && (base.getPath().isEmpty() || "/".equals(base.getPath()));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public boolean isPythonHealthy() {
        if (!isConfigured()) return false;
        Call call = client.newCall(new Request.Builder().url(endpoint("/health")).get().build());
        call.timeout().timeout(Math.min(2_000, internal.getConnectTimeoutMilliseconds()), TimeUnit.MILLISECONDS);
        try (Response response = call.execute()) {
            if (response.code() != 200 || response.body() == null) return false;
            JsonNode body = mapper.readTree(response.body().bytes());
            PythonAgentProtocol.fields(body, "status", "contractVersion");
            PythonAgentProtocol.text(body, "status", false);
            PythonAgentProtocol.text(body, "contractVersion", false);
            return "ok".equals(body.path("status").asText()) && "1".equals(body.path("contractVersion").asText());
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private String endpoint(String path) {
        return internal.getPythonBaseUrl().replaceAll("/+$", "") + path;
    }

    @PreDestroy
    public void close() {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }
}
