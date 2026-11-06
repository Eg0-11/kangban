package com.kangban.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kangban.agent.internal.InternalAgentProperties;
import com.kangban.client.AiClientException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class PythonAgentGatewayTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentProperties agent = new AgentProperties();
    private final RagProperties rag = new RagProperties();
    private final InternalAgentProperties internal = new InternalAgentProperties();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> reply = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<JsonNode> requestBody = new AtomicReference<>();
    private HttpServer server;
    private ExecutorService executor;
    private PythonAgentGateway gateway;
    private AgentContextSigner signer;
    private AgentRequest request;

    @BeforeEach
    void setUp() throws Exception {
        agent.setContextSecret("test-only-java-signing-secret");
        internal.setEnabled(true);
        internal.setServiceToken("test-only-service-token");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        internal.setPythonBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        signer = new AgentContextSigner(mapper, agent);
        gateway = new PythonAgentGateway(new AgentExecutionContextFactory(agent), signer, agent, rag,
                internal, new AgentMetrics(meters), mapper);
        AgentExecutionContext context = gateway.createContext(9007199254740993L, 9L, null, 3L);
        request = new AgentRequest(context, "我的心率", "{\"synthetic\":true}",
                List.of(new ConversationMessage("user", "上一轮")));
        reply.set(mapper.writeValueAsString(new PythonAgentProtocol.Result("1",
                new AgentResponse("合成回答", context.runId(), List.of(), List.of(), List.of()), "success")));
        server.createContext("/internal/agent/v1/run", exchange -> {
            calls.incrementAndGet();
            requestBody.set(mapper.readTree(exchange.getRequestBody()));
            assertThat(signer.verify(exchange.getRequestHeaders().getFirst("X-Agent-Context"))).isEqualTo(request.context());
            assertThat(exchange.getRequestHeaders().getFirst("X-Agent-Service-Token")).isEqualTo(internal.getServiceToken());
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            long deadline = Long.parseLong(exchange.getRequestHeaders().getFirst("X-Agent-Deadline-Epoch-Ms"));
            assertThat(deadline).isLessThanOrEqualTo(request.context().expiresAtEpochSecond() * 1000);
            if (status.get() == 302) exchange.getResponseHeaders().set("Location", "/redirect-target");
            send(exchange, status.get(), reply.get());
        });
        server.createContext("/health", exchange -> send(exchange, status.get(), reply.get()));
        server.createContext("/redirect-target", exchange -> {
            calls.incrementAndGet();
            send(exchange, 200, reply.get());
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        gateway.close();
        server.stop(0);
        executor.shutdownNow();
        meters.close();
    }

    @Test
    void sendsSignedScopeAndExactOptionsAndCountsRunOnce() throws Exception {
        agent.setModelToolCallingEnabled(true);
        agent.setMaxIterations(3);
        rag.setEnabled(true);
        assertThat(gateway.run(request).content()).isEqualTo("合成回答");
        JsonNode envelope = requestBody.get();
        assertThat(envelope.path("contractVersion").asText()).isEqualTo("1");
        assertThat(envelope.path("request").path("context").path("actorUserId").longValue()).isEqualTo(9007199254740993L);
        assertThat(envelope.path("request").path("patientContextJson").asText()).isEqualTo(request.patientContextJson());
        assertThat(envelope.path("request").path("history").get(0).path("content").asText()).isEqualTo("上一轮");
        assertThat(envelope.path("options")).isEqualTo(mapper.readTree("""
                {"enabled":true,"modelToolCallingEnabled":true,"ragEnabled":true,"maxIterations":3,
                 "maxHistoryMessages":12,"maxHistoryTokens":6000,"maxHistoryMessageCharacters":4000}
                """));
        assertThat(calls.get()).isEqualTo(1);
        assertThat(meters.counter("kangban.agent.runs", "outcome", "success").count()).isEqualTo(1);
        assertThat(meters.find("kangban.agent.tools.calls").counter()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-version", "wrong-run", "bad-outcome", "empty-content", "content-number",
            "null-citations", "null-actions", "missing-field", "extra-field", "negative-trace", "bad-status",
            "page-overflow", "invalid-action", "malformed", "duplicate", "trailing"})
    void rejectsInvalidRepliesWithoutRetryOrLeakingBody(String scenario) throws Exception {
        ObjectNode root = (ObjectNode) mapper.readTree(reply.get());
        ObjectNode response = (ObjectNode) root.get("response");
        switch (scenario) {
            case "wrong-version" -> root.put("contractVersion", "2");
            case "wrong-run" -> response.put("runId", "another-run");
            case "bad-outcome" -> root.put("outcome", "arbitrary-tag");
            case "empty-content" -> response.put("content", " ");
            case "content-number" -> response.put("content", 42);
            case "null-citations" -> response.putNull("citations");
            case "null-actions" -> response.putNull("actions");
            case "missing-field" -> response.remove("toolTraces");
            case "extra-field" -> root.put("secret", "private-response-canary");
            case "negative-trace", "bad-status" -> response.withArray("toolTraces").add(mapper.readTree("""
                    {"toolName":"t","status":"SUCCESS","iteration":1,"elapsedMs":0,"errorCode":""}
                    """));
            case "page-overflow" -> response.withArray("citations").add(mapper.readTree("""
                    {"documentId":"d","title":"t","version":null,"pageNumber":2147483648,
                    "section":null,"source":null,"updatedAt":null,"scope":"PUBLIC"}
                    """));
            case "invalid-action" -> response.withArray("actions").add(mapper.readTree("""
                    {"id":"a","type":"x","status":"NOT_A_STATUS","parameters":{},"payloadHash":null,"expiresAt":null}
                    """));
        }
        if (scenario.equals("negative-trace")) ((ObjectNode) response.get("toolTraces").get(0)).put("iteration", -1);
        if (scenario.equals("bad-status")) ((ObjectNode) response.get("toolTraces").get(0)).put("status", "surprise");
        reply.set(mapper.writeValueAsString(root));
        if (scenario.equals("malformed")) reply.set("private-response-canary not JSON");
        if (scenario.equals("duplicate")) reply.set(reply.get().replace("\"contractVersion\":\"1\"", "\"contractVersion\":\"1\",\"contractVersion\":\"1\""));
        if (scenario.equals("trailing")) reply.set(reply.get() + " {}");
        assertThatThrownBy(() -> gateway.run(request)).isInstanceOf(AiClientException.class)
                .hasMessage("AI 服务暂时不可用，请稍后重试。");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(meters.counter("kangban.agent.runs", "outcome", "failure").count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "safety_emergency", "action_handled"})
    void preservesOutcomeMetricBoundary(String outcome) throws Exception {
        ObjectNode root = (ObjectNode) mapper.readTree(reply.get());
        root.put("outcome", outcome);
        reply.set(root.toString());
        gateway.run(request);
        if (outcome.equals("action_handled")) assertThat(meters.find("kangban.agent.runs").counter()).isNull();
        else assertThat(meters.counter("kangban.agent.runs", "outcome", outcome).count()).isEqualTo(1);
    }

    @Test
    void errorsUseLocalNoticeAndNeverRemoteMessage() {
        status.set(504);
        reply.set("{\"contractVersion\":\"1\",\"code\":\"DEADLINE_EXCEEDED\",\"message\":\"private-error-canary\",\"runId\":null,\"retryable\":true}");
        assertThatThrownBy(() -> gateway.run(request)).hasMessage("AI 响应超时，请重试。");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void redirectsAreNotFollowedAndRawErrorBodyIsHidden() {
        status.set(302);
        reply.set("private-error-canary");
        assertThatThrownBy(() -> gateway.run(request)).hasMessage("AI 服务暂时不可用，请稍后重试。");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void refusesUnavailableEndpointWithoutLegacyFallback() {
        server.stop(0);
        assertThatThrownBy(() -> gateway.run(request)).hasMessage("AI 服务暂时不可用，请稍后重试。");
        assertThat(calls.get()).isZero();
    }

    @Test
    void wholeCallDeadlineBoundsSlowReply() {
        server.removeContext("/internal/agent/v1/run");
        server.createContext("/internal/agent/v1/run", exchange -> {
            calls.incrementAndGet();
            try { Thread.sleep(1500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        internal.setMaxRequestMilliseconds(150);
        long started = System.nanoTime();
        assertThatThrownBy(() -> gateway.run(request)).hasMessage("AI 响应超时，请重试。");
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1200);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void cancellationStopsInFlightCallAndCanHappenBeforeAttachment() throws Exception {
        server.removeContext("/internal/agent/v1/run");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        server.createContext("/internal/agent/v1/run", exchange -> {
            calls.incrementAndGet(); entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        AgentRunCancellation cancellation = new AgentRunCancellation();
        Future<?> pending = executor.submit(() -> assertThatThrownBy(() -> gateway.run(request, cancellation)).isInstanceOf(AiClientException.class));
        try {
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            cancellation.cancel();
            pending.get(2, TimeUnit.SECONDS);
            assertThat(calls.get()).isEqualTo(1);
        } finally { release.countDown(); }
        AgentRunCancellation before = new AgentRunCancellation();
        before.cancel();
        assertThatThrownBy(() -> gateway.run(request, before)).hasMessage("本次请求已结束。");
        AtomicInteger invoked = new AtomicInteger();
        assertThatThrownBy(() -> before.attach(invoked::incrementAndGet)).hasMessage("本次请求已结束。");
        assertThat(invoked.get()).isEqualTo(1);
        assertThat(calls.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://127.0.0.1", "http://user:secret@localhost", "http://localhost?q=x", "http://localhost/#x", "http://localhost/arbitrary"})
    void rejectsInvalidConfiguredAddress(String address) {
        internal.setPythonBaseUrl(address);
        assertThat(gateway.isConfigured()).isFalse();
        assertThatThrownBy(() -> gateway.run(request)).hasMessage("AI 服务暂时不可用，请稍后重试。");
        assertThat(calls.get()).isZero();
    }

    @Test
    void disabledOrUnconfiguredInternalBoundaryDoesNotCallNetwork() {
        internal.setEnabled(false);
        assertThat(gateway.isPythonHealthy()).isFalse();
        assertThatThrownBy(() -> gateway.run(request)).hasMessage("AI 服务暂时不可用，请稍后重试。");
        internal.setEnabled(true);
        internal.setServiceToken(agent.getContextSecret());
        assertThat(gateway.isConfigured()).isFalse();
        assertThat(calls.get()).isZero();
    }

    @Test
    void healthProbesPythonWithoutSendingPatientIdentityAndRequiresExactContract() {
        reply.set("{\"status\":\"ok\",\"contractVersion\":\"1\"}");
        assertThat(gateway.isPythonHealthy()).isTrue();
        reply.set("{\"status\":\"ok\",\"contractVersion\":1}");
        assertThat(gateway.isPythonHealthy()).isFalse();
        AgentHealthIndicator health = new AgentHealthIndicator(agent, rag, gateway);
        assertThat(health.health().getStatus().getCode()).isEqualTo("DOWN");
        agent.setEnabled(false);
        assertThat(health.health().getStatus().getCode()).isEqualTo("OUT_OF_SERVICE");
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
