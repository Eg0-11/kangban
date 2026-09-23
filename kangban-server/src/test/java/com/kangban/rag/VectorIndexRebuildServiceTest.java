package com.kangban.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kangban.agent.RagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VectorIndexRebuildServiceTest {

    @Test
    void rebuildsStoredEmbeddingForQdrantWithoutCallingRemoteEmbedding() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        VectorIndexSyncService syncService = mock(VectorIndexSyncService.class);
        RagProperties properties = new RagProperties();
        properties.setVectorStore("qdrant");
        properties.setEmbeddingModel("local-hash-v1");
        properties.setEmbeddingDimensions(3);
        Map<String, Object> document = Map.of("id", 31L, "status", "PUBLISHED");
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("document_id", 31L);
        chunk.put("chunk_index", 0);
        chunk.put("page_number", 2);
        chunk.put("section", "用药提醒");
        chunk.put("content", "蓝色药盒每天晚上使用一次。");
        chunk.put("embedding_json", new ObjectMapper().writeValueAsString(new double[]{0.1, 0.2, 0.3}));
        chunk.put("embedding_model", "local-hash-v1");
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(document), List.of(chunk));

        Map<String, Object> result = new VectorIndexRebuildService(
                jdbcTemplate, new ObjectMapper(), embeddingClient, properties, syncService)
                .rebuildPublic(31L);

        assertThat(result).containsEntry("status", "SUCCEEDED")
                .containsEntry("documents", 1)
                .containsEntry("chunks", 1)
                .containsEntry("failed", 0);
        verify(syncService).deleteDocument("PUBLIC", 31L);
        verify(syncService).upsertPublicChunk(31L, 0, 2, "用药提醒",
                new double[]{0.1, 0.2, 0.3}, "PUBLISHED");
    }

    @Test
    void reembedsWhenStoredModelOrDimensionDoesNotMatchCurrentQdrantCollection() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        VectorIndexSyncService syncService = mock(VectorIndexSyncService.class);
        RagProperties properties = new RagProperties();
        properties.setVectorStore("dual");
        properties.setEmbeddingModel("text-embedding-v4");
        properties.setEmbeddingDimensions(3);
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("chunk_index", 1);
        chunk.put("content", "健康指南正文");
        chunk.put("embedding_json", "[0.1,0.2]");
        chunk.put("embedding_model", "old-model");
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 32L, "status", "PUBLISHED")), List.of(chunk));
        when(embeddingClient.embed("健康指南正文")).thenReturn(new double[]{0.4, 0.5, 0.6});

        Map<String, Object> result = new VectorIndexRebuildService(
                jdbcTemplate, new ObjectMapper(), embeddingClient, properties, syncService)
                .rebuildPublic(32L);

        assertThat(result.get("status")).isEqualTo("SUCCEEDED");
        verify(embeddingClient).embed("健康指南正文");
        verify(syncService).upsertPublicChunk(eq(32L), eq(1), eq(null), eq(""),
                any(double[].class), eq("PUBLISHED"));
    }

    @Test
    void doesNothingInMysqlMode() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        VectorIndexSyncService syncService = mock(VectorIndexSyncService.class);
        RagProperties properties = new RagProperties();

        Map<String, Object> result = new VectorIndexRebuildService(
                jdbcTemplate, new ObjectMapper(), mock(EmbeddingClient.class), properties, syncService)
                .rebuildPublic(null);

        assertThat(result).containsEntry("status", "SKIPPED");
    }
}
