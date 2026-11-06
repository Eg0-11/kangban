package com.kangban.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kangban.TestCaptchaConfig;
import com.kangban.agent.internal.InternalAgentProperties;
import com.kangban.rag.*;
import com.kangban.security.JwtTokenProvider;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual Java REST/SSE -> child Python -> authenticated Java callbacks -> synthetic model HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.agent.internal.enabled=true",
        "app.agent.internal.service-token=test-only-integration-service-token",
        "app.agent.context-secret=test-only-integration-java-signing-key",
        "app.ai.provider=mock", "app.agent.rag.embedding-provider=local",
        "app.mcp.public.enabled=false", "spring.ai.mcp.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:kangban_python_integration;MODE=MySQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
@ActiveProfiles("test")
@Import(TestCaptchaConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PythonConsultationIntegrationTest {
    private static final long ACTOR = 980001L;
    private static final int PYTHON_PORT = availablePort();
    private static final String SERVICE_TOKEN = "test-only-integration-service-token";
    @DynamicPropertySource
    static void pythonAddress(DynamicPropertyRegistry registry) {
        registry.add("app.agent.internal.python-base-url", () -> "http://127.0.0.1:" + PYTHON_PORT);
    }

    @LocalServerPort private int port;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private AgentProperties agent;
    @Autowired private RagProperties rag;
    @Autowired private InternalAgentProperties internal;
    @Autowired private MeterRegistry meters;
    @Autowired private PythonAgentGateway gateway;
    @Autowired private ApplicationContext application;
    @MockitoBean private KnowledgeSearchService publicSearch;
    @MockitoBean private PrivateKnowledgeSearchService privateSearch;

    private final HttpClient http = HttpClient.newBuilder().proxy(com.kangban.client.DirectProxySelector.INSTANCE)
            .connectTimeout(Duration.ofSeconds(2)).build();
    private final List<JsonNode> modelRequests = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> mode = new AtomicReference<>("ordinary");
    private HttpServer model;
    private ExecutorService modelExecutor;
    private Process python;
    private String auth;

    @BeforeAll
    void startPythonAndSyntheticModel() throws Exception {
        model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        modelExecutor = Executors.newCachedThreadPool();
        model.setExecutor(modelExecutor);
        model.createContext("/chat", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-Agent-Service-Token")).isNull();
            assertThat(exchange.getRequestHeaders().getFirst("X-Agent-Context")).isNull();
            JsonNode body = mapper.readTree(exchange.getRequestBody());
            modelRequests.add(body);
            String current = mode.get();
            if (current.equals("slow")) {
                try { Thread.sleep(1500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            Object reply;
            int status = current.equals("failure") ? 500 : 200;
            boolean toolAnswer = body.path("messages").findValuesAsText("role").contains("tool");
            if ((current.equals("tools") && !toolAnswer) || current.equals("unknown")) {
                String name = current.equals("unknown") ? "forbidden_write_tool" : "get_health_metrics";
                reply = Map.of("choices", List.of(Map.of("message", Map.of("content", "", "tool_calls", List.of(
                        Map.of("id", "synthetic-call-1", "type", "function", "function", Map.of("name", name,
                                "arguments", "{\"metric\":\"heart_rate\",\"limit\":20}")))))));
            } else reply = Map.of("choices", List.of(Map.of("message", Map.of("content", "模拟回答：请结合记录观察变化。"))));
            byte[] bytes = mapper.writeValueAsBytes(reply);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(status, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            } catch (java.io.IOException ignored) { exchange.close(); } // Expected after deadline cancellation.
        });
        model.start();
        Path project = Path.of("..").toAbsolutePath().normalize().resolve("kangban-agent");
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        Path executable = Path.of(System.getProperty("kangban.python.executable",
                project.resolve(windows ? ".venv/Scripts/python.exe" : ".venv/bin/python").toString()));
        assertThat(executable).as("Initialize kangban-agent first, or set -Dkangban.python.executable").isRegularFile();
        ProcessBuilder builder = new ProcessBuilder(executable.toString(), "-m", "uvicorn", "kangban_agent.main:create_app",
                "--factory", "--app-dir", "src", "--host", "127.0.0.1", "--port", Integer.toString(PYTHON_PORT),
                "--no-access-log", "--log-level", "warning").directory(project.toFile());
        Map<String, String> env = builder.environment();
        env.put("APP_AGENT_SERVICE_TOKEN", SERVICE_TOKEN);
        env.put("APP_AGENT_JAVA_BASE_URL", "http://127.0.0.1:" + port);
        env.put("APP_AGENT_INTERNAL_MAX_REQUEST_MS", "170000");
        env.put("APP_AI_PROVIDER", "qwen");
        env.put("APP_AI_API_URL", "http://127.0.0.1:" + model.getAddress().getPort() + "/chat");
        env.put("APP_AI_API_KEY", "test-only-local-model-key");
        env.put("APP_AI_AI_MODEL", "synthetic-model");
        env.put("APP_AI_CONNECT_TIMEOUT", "1000");
        env.put("APP_AI_READ_TIMEOUT", "5000");
        env.remove("APP_AGENT_CONTEXT_SECRET");
        python = builder.redirectErrorStream(true).redirectOutput(Path.of("target/python-integration.log").toFile()).start();
        for (int attempt = 0; attempt < 100; attempt++) {
            if (!python.isAlive()) fail("Python exited; inspect target/python-integration.log (synthetic data only)");
            if (gateway.isPythonHealthy()) return;
            Thread.sleep(50);
        }
        fail("Python did not become healthy");
    }

    @AfterAll
    void stopHelpers() throws Exception {
        if (python != null) {
            List<ProcessHandle> children = python.descendants().toList();
            children.forEach(ProcessHandle::destroy);
            python.destroy();
            if (!python.waitFor(5, TimeUnit.SECONDS)) { python.destroyForcibly(); python.waitFor(5, TimeUnit.SECONDS); }
            for (ProcessHandle child : children) {
                if (child.isAlive()) child.destroyForcibly();
                try { child.onExit().get(5, TimeUnit.SECONDS); } catch (ExecutionException ignored) { }
            }
            assertThat(python.isAlive()).isFalse();
        }
        if (model != null) model.stop(0);
        if (modelExecutor != null) modelExecutor.shutdownNow();
        for (int attempt = 0; attempt < 40; attempt++) {
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress("127.0.0.1", PYTHON_PORT), 100);
            } catch (java.io.IOException expected) { return; }
            Thread.sleep(50);
        }
        fail("Python helper port was not released");
    }

    @BeforeEach
    void setUp() {
        clean();
        jdbc.update("INSERT INTO users(id,username,name,password,status) VALUES(?,?,?,'test-only-nonlogin-password',1)", ACTOR, "python-integration", "合成患者");
        jdbc.update("INSERT INTO health_records(user_id,metric,\"VALUE\",unit,recorded_date) VALUES(?,'heart_rate','72','bpm',CURRENT_DATE)", ACTOR);
        auth = jwt.generateToken(ACTOR, "python-integration");
        modelRequests.clear(); mode.set("ordinary");
        agent.setEnabled(true); agent.setModelToolCallingEnabled(false); agent.setMaxIterations(5);
        internal.setMaxRequestMilliseconds(170_000);
        rag.setEnabled(false); rag.setPrivateEnabled(true);
        when(publicSearch.search(anyString())).thenReturn(RagSearchResult.empty());
        when(privateSearch.search(anyString(), any())).thenReturn(RagSearchResult.empty());
    }

    @AfterEach
    void checkBoundaryAndClean() {
        assertThat(application.getBeansOfType(AgentGateway.class)).hasSize(1).containsValue(gateway);
        clean();
    }

    @Test
    void realHttpSsePersistsTracesCitationsHistoryAndReplaysWithoutAnotherRun() throws Exception {
        rag.setEnabled(true);
        Citation citation = new Citation("synthetic-public", "合成资料", "1", 2, "观察", "测试", null, "PUBLIC");
        when(publicSearch.search(anyString())).thenReturn(new RagSearchResult("", List.of(new KnowledgeSearchHit("合成证据正文", .95, citation))));
        long session = session();
        jdbc.update("INSERT INTO chat_messages(session_id,user_id,role,content) VALUES(?,?,'user','之前的合成问题')", session, ACTOR);
        long message = message(session, "我的心率最近有什么变化", "dedup-1");
        double before = count("kangban.agent.runs", "outcome", "success");
        double tools = count("kangban.agent.tools.calls", "tool", "get_health_metrics", "outcome", "success");
        String stream = stream(session, message);
        assertThat(events(stream)).containsExactly("thinking", "agent_tool", "thinking_done", "citation", "token", "done");
        assertThat(stream).doesNotContain("elapsedMs", "errorCode", SERVICE_TOKEN, agent.getContextSecret());
        assertThat(modelRequests).hasSize(1);
        JsonNode modelBody = modelRequests.get(0);
        assertThat(modelBody.path("messages").toString()).contains("之前的合成问题", "合成证据正文", "72");
        Map<String, Object> reply = jdbc.queryForMap("SELECT * FROM chat_messages WHERE reply_to_message_id=?", message);
        assertThat(reply.get("CITATIONS_JSON").toString()).contains("synthetic-public");
        assertThat(reply.get("AGENT_TOOL_TRACES_JSON").toString()).contains("get_health_metrics");
        assertThat(count("kangban.agent.runs", "outcome", "success") - before).isEqualTo(1);
        assertThat(count("kangban.agent.tools.calls", "tool", "get_health_metrics", "outcome", "success") - tools).isEqualTo(1);
        assertThat(message(session, "我的心率最近有什么变化", "dedup-1")).isEqualTo(message);
        String replay = stream(session, message);
        assertThat(events(replay)).containsExactly("agent_tool", "thinking_done", "citation", "token", "done");
        assertThat(modelRequests).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages WHERE reply_to_message_id=?", Long.class, message)).isEqualTo(1);
    }

    @Test
    void modelToolLoopUsesRealCallbackAndRetainsToolMessages() throws Exception {
        mode.set("tools"); agent.setModelToolCallingEnabled(true);
        long session = session(), message = message(session, "我的心率", "tools-1");
        String stream = stream(session, message);
        assertThat(events(stream)).containsExactly("thinking", "agent_tool", "thinking_done", "token", "done");
        assertThat(modelRequests).hasSize(2);
        assertThat(modelRequests.get(1).path("messages").toString()).contains("synthetic-call-1", "tool_call_id", "72");
        assertThat(modelRequests.get(0).path("tools").size()).isEqualTo(8);
    }

    @Test
    void proposalRemainsPendingAndOnlyPublicConfirmationWritesOnce() throws Exception {
        long session = session();
        long missing = message(session, "帮我记录血压", "proposal-missing");
        assertThat(events(stream(session, missing))).containsExactly("thinking", "thinking_done", "token", "done");
        long complete = message(session, "帮我记录今天早上血压 128/82 mmHg", "proposal-complete");
        long records = jdbc.queryForObject("SELECT COUNT(*) FROM health_records WHERE user_id=?", Long.class, ACTOR);
        double before = count("kangban.agent.runs", "outcome", "success");
        String stream = stream(session, complete);
        assertThat(events(stream)).containsExactly("thinking", "thinking_done", "action_proposal", "token", "done");
        assertThat(modelRequests).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM health_records WHERE user_id=?", Long.class, ACTOR)).isEqualTo(records);
        assertThat(count("kangban.agent.runs", "outcome", "success")).isEqualTo(before);
        String actions = jdbc.queryForObject("SELECT actions_json FROM chat_messages WHERE reply_to_message_id=?", String.class, complete);
        JsonNode action = mapper.readTree(actions).get(0);
        assertThat(action.path("status").asText()).isEqualTo("PENDING_CONFIRMATION");
        String path = "/consultation/actions/" + action.path("id").asText() + "/confirm";
        JsonNode first = api("POST", path, Map.of("payloadHash", action.path("payloadHash").asText()));
        JsonNode second = api("POST", path, Map.of("payloadHash", action.path("payloadHash").asText()));
        assertThat(first.path("code").asInt()).isZero();
        assertThat(second.path("code").asInt()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM health_records WHERE user_id=?", Long.class, ACTOR)).isEqualTo(records + 1);
    }

    @Test
    void emergencyStillPrecedesAgentSwitchAndDoesNotCallModel() throws Exception {
        agent.setEnabled(false);
        long session = session(), message = message(session, "突然胸痛呼吸困难", "emergency-1");
        String stream = stream(session, message);
        assertThat(events(stream)).containsExactly("thinking", "thinking_done", "token", "done");
        assertThat(stream).contains("120");
        assertThat(modelRequests).isEmpty();
    }

    @Test
    void unknownToolFailsSafelyAndRecordsFailureWithoutSavingAnAnswer() throws Exception {
        agent.setModelToolCallingEnabled(true); mode.set("unknown");
        long session = session(), message = message(session, "我的心率", "unknown-1");
        double tools = count("kangban.agent.tools.calls", "tool", "unknown_tool", "outcome", "failed");
        String stream = stream(session, message);
        assertThat(events(stream)).containsExactly("thinking", "ai_error");
        assertThat(stream).contains("患者健康数据工具暂时不可用");
        assertThat(modelRequests).hasSize(1);
        assertThat(count("kangban.agent.tools.calls", "tool", "unknown_tool", "outcome", "failed") - tools).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages WHERE reply_to_message_id=?", Long.class, message)).isZero();
    }

    @Test
    void dependencyFailureUsesExistingSseErrorAndNoAutomaticRetry() throws Exception {
        mode.set("failure");
        long session = session(), message = message(session, "我的心率", "failure-1");
        String stream = stream(session, message);
        assertThat(events(stream)).containsExactly("thinking", "ai_error");
        assertThat(stream).contains("AI 服务暂时不可用").doesNotContain("choices", "synthetic-model");
        assertThat(modelRequests).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages WHERE reply_to_message_id=?", Long.class, message)).isZero();
    }

    @Test
    void shortBudgetCancelsCrossServiceWorkAndDoesNotPersistOrRetry() throws Exception {
        mode.set("slow"); internal.setMaxRequestMilliseconds(600);
        long session = session(), message = message(session, "我的心率", "timeout-1");
        String stream = stream(session, message);
        assertThat(events(stream)).containsExactly("thinking", "ai_error");
        assertThat(stream).contains("AI 响应超时");
        assertThat(modelRequests).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages WHERE reply_to_message_id=?", Long.class, message)).isZero();
    }

    private long session() throws Exception {
        return api("POST", "/consultation/sessions", Map.of("title", "合成问诊")).path("data").path("id").asLong();
    }

    private long message(long session, String content, String clientId) throws Exception {
        return api("POST", "/consultation/sessions/" + session + "/messages", Map.of("content", content, "clientMessageId", clientId))
                .path("data").path("userMessage").path("id").asLong();
    }

    private JsonNode api(String method, String path, Object body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + auth).header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15)).method(method, HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body))).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode result = mapper.readTree(response.body());
        assertThat(result.path("code").asInt()).as("existing public Result success code").isZero();
        return result;
    }

    private String stream(long session, long message) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/consultation/sessions/" + session + "/stream?messageId=" + message))
                .header("Authorization", "Bearer " + auth).timeout(Duration.ofSeconds(15)).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private static List<String> events(String stream) {
        return stream.lines().filter(line -> line.startsWith("event:")).map(line -> line.substring(6).trim()).toList();
    }

    private double count(String name, String... tags) { return meters.counter(name, tags).count(); }

    private void clean() {
        jdbc.update("DELETE FROM agent_action_proposals WHERE actor_user_id=?", ACTOR);
        jdbc.update("DELETE FROM chat_messages WHERE user_id=?", ACTOR);
        jdbc.update("DELETE FROM chat_sessions WHERE user_id=?", ACTOR);
        jdbc.update("DELETE FROM health_records WHERE user_id=?", ACTOR);
        jdbc.update("DELETE FROM audit_logs WHERE user_id=?", ACTOR);
        jdbc.update("DELETE FROM users WHERE id=?", ACTOR);
    }

    private static int availablePort() {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); }
        catch (java.io.IOException exception) { throw new IllegalStateException(exception); }
    }
}
