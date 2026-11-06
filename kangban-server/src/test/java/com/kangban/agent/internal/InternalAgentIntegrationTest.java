package com.kangban.agent.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kangban.TestCaptchaConfig;
import com.kangban.agent.AgentContextSigner;
import com.kangban.agent.AgentExecutionContext;
import com.kangban.agent.Citation;
import com.kangban.agent.RagProperties;
import com.kangban.rag.*;
import com.kangban.security.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.agent.internal.enabled=true",
        "app.agent.internal.service-token=test-only-internal-service-token",
        "app.agent.context-secret=test-only-independent-context-key",
        "app.agent.rag.enabled=true",
        "app.agent.rag.embedding-provider=local",
        "app.ai.provider=mock",
        "app.mcp.public.enabled=false",
        "spring.ai.mcp.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:kangban_internal_agent_test;MODE=MySQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestCaptchaConfig.class)
class InternalAgentIntegrationTest {
    private static final long ACTOR = 970001L;
    private static final long OTHER = 970002L;
    private static final long SESSION = 970031L;
    private static final long FAMILY = 970041L;
    private static final String SERVICE_TOKEN = "test-only-internal-service-token";
    private static final String PREFIX = "/internal/agent/v1";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AgentContextSigner signer;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RagProperties ragProperties;
    @Autowired private JwtTokenProvider jwtTokens;
    @Autowired private TestRestTemplate rest;
    @MockitoBean private KnowledgeSearchService publicSearch;
    @MockitoBean private PrivateKnowledgeSearchService privateSearch;
    private AgentExecutionContext context;

    @BeforeEach
    void setUp() {
        cleanFixtures();
        jdbc.update("INSERT INTO users(id,username,name,status) VALUES(?,?,?,1)", ACTOR, "internal-a", "当前患者");
        jdbc.update("INSERT INTO users(id,username,name,status) VALUES(?,?,?,1)", OTHER, "internal-b", "其他患者");
        jdbc.update("INSERT INTO chat_sessions(id,user_id,status,title) VALUES(?,?,'active','internal-test')", SESSION, ACTOR);
        jdbc.update("INSERT INTO health_records(user_id,metric,\"VALUE\",unit,recorded_date) VALUES(?,'heart_rate','72','bpm',CURRENT_DATE)", ACTOR);
        jdbc.update("INSERT INTO health_records(user_id,metric,\"VALUE\",unit,recorded_date) VALUES(?,'heart_rate','199','bpm',CURRENT_DATE)", OTHER);
        context = context(ACTOR, ACTOR, null);
        ragProperties.setEnabled(true);
        ragProperties.setPrivateEnabled(true);
        when(publicSearch.search(anyString())).thenReturn(RagSearchResult.empty());
        when(privateSearch.search(anyString(), any())).thenReturn(RagSearchResult.empty());
    }

    @AfterEach
    void tearDown() {
        ragProperties.setEnabled(true);
        ragProperties.setPrivateEnabled(true);
        cleanFixtures();
    }

    @Test
    void ordinaryJwtCannotAccessInternalToolsAndServiceTokenCannotAccessPublicBusiness() throws Exception {
        mvc.perform(get(PREFIX + "/tools").header("Authorization", "Bearer " + jwtTokens.generateToken(ACTOR, "internal-a")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_SERVICE_CREDENTIAL"))
                .andExpect(jsonPath("$.contractVersion").value("1"));
        mvc.perform(get("/health/trends").header("X-Agent-Service-Token", SERVICE_TOKEN))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void validCallbackReturnsRawToolDefinitions() throws Exception {
        mvc.perform(authorized(get(PREFIX + "/tools")))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(8)))
                .andExpect(jsonPath("$[0].parameters.type").value("object"));
    }

    @Test
    void realHttpCallbackUsesInternalSecurityChainEvenWithInvalidUserJwt() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Agent-Service-Token", SERVICE_TOKEN);
        headers.set("X-Agent-Context", signer.sign(context));
        headers.set("X-Agent-Deadline-Epoch-Ms", deadline());
        headers.setBearerAuth("invalid-user-jwt");
        var response = rest.exchange(PREFIX + "/tools", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).startsWith("[");
    }

    @Test
    void toolArgumentsCannotOverrideSignedPatient() throws Exception {
        String body = mapper.writeValueAsString(Map.of("name", "get_health_metrics", "arguments",
                Map.of("metric", "heart_rate", "subjectUserId", OTHER)));
        String raw = mvc.perform(authorized(post(PREFIX + "/tools/execute")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.status").value("SUCCESS"))
                .andExpect(jsonPath("$.elapsedMs").isNumber()).andReturn().getResponse().getContentAsString();
        JsonNode facts = mapper.readTree(mapper.readTree(raw).path("result").path("content").asText());
        assertThat(facts.path("records")).hasSize(1);
        assertThat(facts.path("records").get(0).path("value").asText()).isEqualTo("72");
    }

    @Test
    void unknownToolAndDisabledMcpStayStructuredToolFailures() throws Exception {
        mvc.perform(authorized(post(PREFIX + "/tools/execute")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"write_patient_record\",\"arguments\":{}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.status").value("FAILED"))
                .andExpect(jsonPath("$.result.errorCode").value("UNKNOWN_TOOL"));
        mvc.perform(authorized(post(PREFIX + "/tools/execute")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"search_hospitals\",\"arguments\":{\"keyword\":\"天津医院\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.errorCode").value("MCP_PUBLIC_DISABLED"));
    }

    @Test
    void rejectsUnknownRootFieldsWrongTypesAndMalformedJsonWithoutEchoingInput() throws Exception {
        mvc.perform(authorized(post(PREFIX + "/tools/execute")).contentType(MediaType.TEXT_PLAIN).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        for (String body : List.of("{\"name\":\"get_health_metrics\",\"arguments\":{},\"subjectUserId\":970002}",
                "{\"name\":42,\"arguments\":{}}", "{\"name\":\"get_health_metrics\",\"arguments\":null}",
                "{\"name\":\"test-canary-private-input\",", "[]")) {
            String response = mvc.perform(authorized(post(PREFIX + "/tools/execute")).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.contractVersion").value("1"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("test-canary-private-input");
        }
    }

    @Test
    void changedSessionPatientAndRevokedFamilyPermissionAreRejected() throws Exception {
        jdbc.update("UPDATE chat_sessions SET subject_user_id=? WHERE id=?", OTHER, SESSION);
        mvc.perform(authorized(get(PREFIX + "/tools"))).andExpect(status().isForbidden());
        context = context(ACTOR, OTHER, null);
        jdbc.update("INSERT INTO family_groups(id,name,owner_user_id) VALUES(?,'internal-family',?)", FAMILY, ACTOR);
        jdbc.update("INSERT INTO family_group_members(family_id,user_id,status) VALUES(?,?,'active'),(?,?,'active')", FAMILY, ACTOR, FAMILY, OTHER);
        jdbc.update("INSERT INTO family_permissions(family_id,subject_user_id,grantee_user_id,can_use_ai,status) VALUES(?,?,?,TRUE,'active')", FAMILY, OTHER, ACTOR);
        mvc.perform(authorized(get(PREFIX + "/tools"))).andExpect(status().isOk());
        jdbc.update("UPDATE family_permissions SET revoked_at=CURRENT_TIMESTAMP WHERE family_id=?", FAMILY);
        mvc.perform(authorized(get(PREFIX + "/tools"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PATIENT_SCOPE_DENIED"));
    }

    @Test
    void expiredAndTamperedContextAndExpiredDeadlineAreDistinctErrors() throws Exception {
        mvc.perform(get(PREFIX + "/tools").header("X-Agent-Service-Token", SERVICE_TOKEN)
                        .header("X-Agent-Context", signer.sign(context)).header("X-Agent-Deadline-Epoch-Ms", "invalid"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(PREFIX + "/tools").header("X-Agent-Service-Token", SERVICE_TOKEN)
                        .header("X-Agent-Context", "invalid-context").header("X-Agent-Deadline-Epoch-Ms", deadline()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("INVALID_CONTEXT"));
        long now = System.currentTimeMillis() / 1000;
        context = new AgentExecutionContext(ACTOR, ACTOR, null, SESSION, "expired-run", "test-trace", now - 5, now - 1);
        mvc.perform(authorized(get(PREFIX + "/tools"))).andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONTEXT_EXPIRED"));
        context = context(ACTOR, ACTOR, null);
        mvc.perform(get(PREFIX + "/tools").header("X-Agent-Service-Token", SERVICE_TOKEN)
                        .header("X-Agent-Context", signer.sign(context))
                        .header("X-Agent-Deadline-Epoch-Ms", System.currentTimeMillis() - 1))
                .andExpect(status().isGatewayTimeout()).andExpect(jsonPath("$.code").value("DEADLINE_EXCEEDED"));
    }

    @Test
    void ragMergesBothDomainsAndKeepsCitationScopes() throws Exception {
        Citation publicCitation = new Citation("public-1", "公开资料", "v1", null, null, "fixture", null, "PUBLIC");
        Citation privateCitation = new Citation("private-1", "私有病历", "v1", null, null, "fixture", null, "PRIVATE");
        when(publicSearch.search("查询资料")).thenReturn(new RagSearchResult("", List.of(new KnowledgeSearchHit("公开内容", .8, publicCitation))));
        when(privateSearch.search(eq("查询资料"), any())).thenReturn(new RagSearchResult("", List.of(new KnowledgeSearchHit("私有内容", .9, privateCitation))));
        mvc.perform(authorized(post(PREFIX + "/rag/search")).contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"查询资料\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hits", hasSize(2)))
                .andExpect(jsonPath("$.hits[0].citation.scope").value("PRIVATE"))
                .andExpect(jsonPath("$.hits[1].citation.scope").value("PUBLIC"));
        verify(privateSearch).search("查询资料", context);
    }

    @Test
    void ragHonorsPrivateSwitchAndDistinguishesEmptyFromUnavailable() throws Exception {
        ragProperties.setPrivateEnabled(false);
        mvc.perform(authorized(post(PREFIX + "/rag/search")).contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"空检索\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hits", hasSize(0)));
        verifyNoInteractions(privateSearch);
        when(publicSearch.search("服务故障")).thenThrow(new RagUnavailableException("test-canary-sensitive-error"));
        String raw = mvc.perform(authorized(post(PREFIX + "/rag/search")).contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"服务故障\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("RAG_UNAVAILABLE"))
                .andExpect(jsonPath("$.runId").value(context.runId())).andReturn().getResponse().getContentAsString();
        assertThat(raw).doesNotContain("test-canary-sensitive-error");
        ragProperties.setEnabled(false);
        mvc.perform(authorized(post(PREFIX + "/rag/search")).contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"空检索\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("RAG_DISABLED"));
    }

    @Test
    void proposalEndpointKeepsClarificationAndDoesNotWriteHealthData() throws Exception {
        mvc.perform(authorized(post(PREFIX + "/actions/propose")).contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"你好\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.handled").value(false))
                .andExpect(jsonPath("$.content").isEmpty()).andExpect(jsonPath("$.action").isEmpty());
        mvc.perform(authorized(post(PREFIX + "/actions/propose")).contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"帮我记录血压\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.handled").value(true)).andExpect(jsonPath("$.action").isEmpty());
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM health_records WHERE user_id=?", Long.class, ACTOR);
        mvc.perform(authorized(post(PREFIX + "/actions/propose")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"帮我记录今天早上血压 128/82 mmHg\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.action.status").value("PENDING_CONFIRMATION"))
                .andExpect(jsonPath("$.action.parameters.subjectUserId").value(ACTOR));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM health_records WHERE user_id=?", Long.class, ACTOR)).isEqualTo(count);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_action_proposals WHERE actor_user_id=?", Long.class, ACTOR)).isEqualTo(1L);
        mvc.perform(authorized(post(PREFIX + "/actions/any-id/confirm"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void exportsSyntheticCallbackResponsesForCrossLanguageContractValidation() throws Exception {
        List<Map<String, Object>> fixtures = new ArrayList<>();
        capture(fixtures, "ToolDefinitionsResponse", authorized(get(PREFIX + "/tools")), null);
        capture(fixtures, "ToolExecutionResponse", authorized(post(PREFIX + "/tools/execute")),
                "{\"name\":\"get_health_metrics\",\"arguments\":{}}");
        capture(fixtures, "ToolExecutionResponse", authorized(post(PREFIX + "/tools/execute")),
                "{\"name\":\"unknown_tool\",\"arguments\":{}}");
        Citation citation = new Citation("fixture-doc", "模拟资料", "v1", 1, null, "fixture", null, "PUBLIC");
        when(publicSearch.search("模拟资料")).thenReturn(new RagSearchResult("", List.of(new KnowledgeSearchHit("模拟正文", .9, citation))));
        capture(fixtures, "RagSearchResult", authorized(post(PREFIX + "/rag/search")), "{\"query\":\"模拟资料\"}");
        capture(fixtures, "ProposalDecision", authorized(post(PREFIX + "/actions/propose")), "{\"message\":\"你好\"}");
        capture(fixtures, "ProposalDecision", authorized(post(PREFIX + "/actions/propose")), "{\"message\":\"帮我记录血压\"}");
        capture(fixtures, "ProposalDecision", authorized(post(PREFIX + "/actions/propose")),
                "{\"message\":\"帮我记录今天早上血压 128/82 mmHg\"}");
        String error = mvc.perform(get(PREFIX + "/tools")).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        fixtures.add(Map.of("definition", "InternalError", "payload", mapper.readTree(error)));
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "internal-agent-contract-fixtures.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(fixtures), StandardCharsets.UTF_8);
    }

    private void capture(List<Map<String, Object>> fixtures, String definition,
                         MockHttpServletRequestBuilder request, String body) throws Exception {
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        String raw = mvc.perform(request).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        fixtures.add(Map.of("definition", definition, "payload", mapper.readTree(raw)));
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request) {
        return request.header("X-Agent-Service-Token", SERVICE_TOKEN).header("X-Agent-Context", signer.sign(context))
                .header("X-Agent-Deadline-Epoch-Ms", deadline());
    }

    private String deadline() { return Long.toString(System.currentTimeMillis() + 30_000); }

    private AgentExecutionContext context(long actor, long subject, Long member) {
        long now = System.currentTimeMillis() / 1000;
        return new AgentExecutionContext(actor, subject, member, SESSION, "internal-test-run", "internal-test-trace", now - 1, now + 300);
    }

    private void cleanFixtures() {
        jdbc.update("DELETE FROM agent_action_proposals WHERE actor_user_id IN (?,?)", ACTOR, OTHER);
        jdbc.update("DELETE FROM audit_logs WHERE user_id IN (?,?)", ACTOR, OTHER);
        jdbc.update("DELETE FROM family_permissions WHERE family_id=?", FAMILY);
        jdbc.update("DELETE FROM family_group_members WHERE family_id=?", FAMILY);
        jdbc.update("DELETE FROM family_groups WHERE id=?", FAMILY);
        jdbc.update("DELETE FROM health_records WHERE user_id IN (?,?)", ACTOR, OTHER);
        jdbc.update("DELETE FROM chat_sessions WHERE id=?", SESSION);
        jdbc.update("DELETE FROM users WHERE id IN (?,?)", ACTOR, OTHER);
    }
}
