package com.kangban.rag;

import com.kangban.agent.RagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class VectorIndexSyncServiceTest {

    @Test
    void keepsMySqlModeSideEffectFree() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        VectorStore vectorStore = mock(VectorStore.class);
        VectorIndexSyncService service = new VectorIndexSyncService(
                jdbcTemplate, vectorStore, new RagProperties());

        service.upsertPublicChunk(1L, 0, 1, "章节", new double[]{0.1, 0.2}, "DRAFT");
        service.deleteDocument("PUBLIC", 1L);

        verifyNoInteractions(jdbcTemplate, vectorStore);
    }

    @Test
    void recordsSuccessfulQdrantUpsertAndSendsScopePayload() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        VectorStore vectorStore = mock(VectorStore.class);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);
        RagProperties properties = new RagProperties();
        properties.setVectorStore("qdrant");
        properties.setEmbeddingModel("text-embedding-v4");
        VectorIndexSyncService service = new VectorIndexSyncService(jdbcTemplate, vectorStore, properties);

        service.upsertPrivateChunk(2L, 1, null, "病历", new double[]{0.1, 0.2},
                9L, 15L, 20L, 3L);

        verify(vectorStore).ensureReady();
        verify(vectorStore).upsert(argThat(record ->
                record.vector().length == 2
                        && "PRIVATE".equals(record.payload().get("scope"))
                        && Long.valueOf(15L).equals(record.payload().get("subjectUserId"))));
    }

    @Test
    void dualModeRecordsFailureButDoesNotBreakTheMySqlWritePath() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        VectorStore vectorStore = mock(VectorStore.class);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);
        doThrow(new VectorStoreUnavailableException("unavailable")).when(vectorStore).upsert(any());
        RagProperties properties = new RagProperties();
        properties.setVectorStore("dual");
        VectorIndexSyncService service = new VectorIndexSyncService(jdbcTemplate, vectorStore, properties);

        service.upsertPublicChunk(1L, 0, null, null, new double[]{0.1}, "PUBLISHED");

        verify(vectorStore).upsert(any());
        verify(jdbcTemplate, atLeastOnce()).update(anyString(), any(Object[].class));
    }

    @Test
    void qdrantModeFailsExplicitlyWhenDerivedIndexCannotBeWritten() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        VectorStore vectorStore = mock(VectorStore.class);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);
        doThrow(new VectorStoreUnavailableException("unavailable")).when(vectorStore).upsert(any());
        RagProperties properties = new RagProperties();
        properties.setVectorStore("qdrant");
        VectorIndexSyncService service = new VectorIndexSyncService(jdbcTemplate, vectorStore, properties);

        assertThatThrownBy(() -> service.upsertPublicChunk(1L, 0, null, null,
                        new double[]{0.1}, "PUBLISHED"))
                .isInstanceOf(VectorStoreUnavailableException.class)
                .hasMessageContaining("写入 PUBLIC");
    }
}
