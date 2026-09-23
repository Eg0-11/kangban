package com.kangban.rag;

import com.kangban.agent.AgentExecutionContext;
import com.kangban.agent.AgentMetrics;
import com.kangban.agent.RagProperties;
import com.kangban.service.FamilyAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class QdrantKnowledgeSearchServiceTest {

    @Test
    void recallsFromQdrantThenHydratesPublishedContentFromMysql() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        VectorStore vectorStore = mock(VectorStore.class);
        FamilyAccessService accessService = mock(FamilyAccessService.class);
        RagProperties properties = properties();
        when(embeddingClient.embed("蓝色药盒什么时候使用")).thenReturn(new double[]{0.1, 0.2, 0.3});
        when(vectorStore.search(any(double[].class), eq(50), anyMap())).thenReturn(List.of(
                new VectorStore.VectorSearchHit("point-1", 0.94,
                        Map.of("documentId", 31L, "chunkIndex", 0))));
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(publicRow()));

        RagSearchResult result = new QdrantKnowledgeSearchService(
                jdbcTemplate, embeddingClient, vectorStore, properties, accessService, new AgentMetrics())
                .search("蓝色药盒什么时候使用");

        assertThat(result.hits()).singleElement().satisfies(hit -> {
            assertThat(hit.content()).contains("每天晚上 21:10");
            assertThat(hit.citation().documentId()).isEqualTo("31");
            assertThat(hit.citation().scope()).isEqualTo("PUBLIC");
        });
        assertThat(result.context()).contains("[资料1]").contains("系统测试资料");
        verify(vectorStore).search(any(double[].class), eq(50), eq(Map.of(
                "scope", "PUBLIC", "documentStatus", "PUBLISHED", "embeddingModel", "local-hash-v1")));
    }

    @Test
    void privateSearchKeepsSubjectAndMemberFiltersAndRechecksMysqlRows() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        VectorStore vectorStore = mock(VectorStore.class);
        FamilyAccessService accessService = mock(FamilyAccessService.class);
        RagProperties properties = properties();
        when(embeddingClient.embed("白细胞")).thenReturn(new double[]{0.1, 0.2, 0.3});
        when(vectorStore.search(any(double[].class), eq(50), anyMap())).thenReturn(List.of(
                new VectorStore.VectorSearchHit("private-1", 0.93,
                        Map.of("documentId", 41L, "chunkIndex", 2))));
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(privateRow()));

        AgentExecutionContext context = context(9L, 15L, 2L);
        RagSearchResult result = new QdrantKnowledgeSearchService(
                jdbcTemplate, embeddingClient, vectorStore, properties, accessService, new AgentMetrics())
                .search("白细胞", context);

        assertThat(result.hits()).hasSize(1);
        assertThat(result.citations().get(0).scope()).isEqualTo("PRIVATE");
        assertThat(result.citations().get(0).documentId()).isEqualTo("private:41");
        verify(accessService).require(9L, 15L, FamilyAccessService.Scope.VIEW_RECORDS);
        verify(vectorStore).search(any(double[].class), eq(50), eq(Map.of(
                "scope", "PRIVATE", "ownerUserId", 15L, "subjectUserId", 15L,
                "documentStatus", "READY", "embeddingModel", "local-hash-v1", "memberId", 2L)));
    }

    @Test
    void privateSearchStopsBeforeEmbeddingWhenFamilyPermissionIsMissing() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        VectorStore vectorStore = mock(VectorStore.class);
        FamilyAccessService accessService = mock(FamilyAccessService.class);
        doThrow(com.kangban.common.BusinessException.forbidden("未获得该家庭成员的数据访问权限"))
                .when(accessService).require(9L, 15L, FamilyAccessService.Scope.VIEW_RECORDS);

        RagSearchResult result = new QdrantKnowledgeSearchService(
                jdbcTemplate, embeddingClient, vectorStore, properties(), accessService, new AgentMetrics())
                .search("病历", context(9L, 15L, 2L));

        assertThat(result.hits()).isEmpty();
        verifyNoInteractions(jdbcTemplate, embeddingClient, vectorStore);
    }

    private RagProperties properties() {
        RagProperties properties = new RagProperties();
        properties.setMinScore(0.7);
        properties.setTopK(3);
        properties.setQdrantMaxSearchLimit(50);
        return properties;
    }

    private Map<String, Object> publicRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("document_id", 31L);
        row.put("chunk_index", 0);
        row.put("content", "蓝色药盒每天晚上 21:10 使用一次。");
        row.put("page_number", 2);
        row.put("section", "用药提醒");
        row.put("title", "RAG验收-蓝色药盒");
        row.put("version", 1);
        row.put("source", "系统测试资料");
        row.put("updated_at", "2026-08-16T10:00");
        return row;
    }

    private Map<String, Object> privateRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("document_id", 41L);
        row.put("chunk_index", 2);
        row.put("content", "白细胞计数偏高，建议复查。");
        row.put("page_number", null);
        row.put("section", "检验结果");
        row.put("title", "李明血常规");
        row.put("version", 2);
        row.put("source", "家庭私有病历");
        row.put("updated_at", "2026-08-16T10:00");
        return row;
    }

    private AgentExecutionContext context(Long actor, Long subject, Long member) {
        long now = System.currentTimeMillis() / 1000;
        return new AgentExecutionContext(actor, subject, member, 31L,
                "run-qdrant", "trace-qdrant", now - 1, now + 60);
    }
}
