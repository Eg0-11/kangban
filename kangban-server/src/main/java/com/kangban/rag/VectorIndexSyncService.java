package com.kangban.rag;

import com.kangban.agent.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 将 MySQL 中的知识切片同步为可重建的 Qdrant 索引，并记录同步状态。
 */
@Slf4j
@Service
public class VectorIndexSyncService {

    private final JdbcTemplate jdbcTemplate;
    private final VectorStore vectorStore;
    private final RagProperties properties;

    public VectorIndexSyncService(JdbcTemplate jdbcTemplate,
                                  VectorStore vectorStore,
                                  RagProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    public void upsertPublicChunk(Long documentId, int chunkIndex, Integer pageNumber,
                                  String section, double[] vector, String documentStatus) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scope", "PUBLIC");
        payload.put("documentId", documentId);
        payload.put("chunkIndex", chunkIndex);
        payload.put("documentStatus", safeStatus(documentStatus));
        payload.put("embeddingModel", properties.getEmbeddingModel());
        putIfPresent(payload, "pageNumber", pageNumber);
        putIfPresent(payload, "section", section);
        upsert("PUBLIC", documentId, chunkIndex, vector, payload);
    }

    public void upsertPrivateChunk(Long documentId, int chunkIndex, Integer pageNumber,
                                   String section, double[] vector, Long ownerUserId,
                                   Long subjectUserId, Long familyId, Long memberId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scope", "PRIVATE");
        payload.put("documentId", documentId);
        payload.put("chunkIndex", chunkIndex);
        payload.put("documentStatus", "READY");
        payload.put("embeddingModel", properties.getEmbeddingModel());
        payload.put("ownerUserId", ownerUserId);
        payload.put("subjectUserId", subjectUserId);
        putIfPresent(payload, "familyId", familyId);
        putIfPresent(payload, "memberId", memberId);
        putIfPresent(payload, "pageNumber", pageNumber);
        putIfPresent(payload, "section", section);
        upsert("PRIVATE", documentId, chunkIndex, vector, payload);
    }

    public void deleteDocument(String scope, Long documentId) {
        if (!enabled() || documentId == null) {
            return;
        }
        try {
            ensureReady();
            vectorStore.deleteByFilter(Map.of("scope", scope, "documentId", documentId));
            jdbcTemplate.update("UPDATE vector_index_sync SET status='DELETED', last_error=NULL, "
                            + "updated_at=CURRENT_TIMESTAMP WHERE index_scope=? AND document_id=?",
                    scope, documentId);
        } catch (RuntimeException e) {
            recordFailure(scope, documentId, null, null, e);
            handleFailure("删除 " + scope + " 向量索引失败", e);
        }
    }

    public void updateDocumentStatus(String scope, Long documentId, String status) {
        if (!enabled() || documentId == null) {
            return;
        }
        try {
            ensureReady();
            vectorStore.updatePayloadByFilter(
                    Map.of("scope", scope, "documentId", documentId),
                    Map.of("documentStatus", safeStatus(status)));
        } catch (RuntimeException e) {
            recordFailure(scope, documentId, null, null, e);
            handleFailure("更新 " + scope + " 向量状态失败", e);
        }
    }

    private void upsert(String scope, Long documentId, int chunkIndex,
                        double[] vector, Map<String, Object> payload) {
        if (!enabled()) {
            return;
        }
        String pointId = pointId(scope, documentId, chunkIndex);
        recordPending(scope, documentId, chunkIndex, pointId);
        try {
            ensureReady();
            vectorStore.upsert(new VectorStore.VectorRecord(pointId, vector, payload));
            jdbcTemplate.update("UPDATE vector_index_sync SET status='SUCCEEDED', attempts=attempts+1, "
                            + "last_error=NULL, synced_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP "
                            + "WHERE index_scope=? AND document_id=? AND chunk_index=? AND embedding_model=?",
                    scope, documentId, chunkIndex, properties.getEmbeddingModel());
        } catch (RuntimeException e) {
            recordFailure(scope, documentId, chunkIndex, pointId, e);
            handleFailure("写入 " + scope + " 向量索引失败", e);
        }
    }

    private void ensureReady() {
        vectorStore.ensureReady();
    }

    private void recordPending(String scope, Long documentId, int chunkIndex, String pointId) {
        int updated = jdbcTemplate.update("UPDATE vector_index_sync SET point_id=?, status='PENDING', "
                        + "attempts=attempts+1, last_error=NULL, updated_at=CURRENT_TIMESTAMP "
                        + "WHERE index_scope=? AND document_id=? AND chunk_index=? AND embedding_model=?",
                pointId, scope, documentId, chunkIndex, properties.getEmbeddingModel());
        if (updated == 0) {
            jdbcTemplate.update("INSERT INTO vector_index_sync "
                            + "(index_scope, document_id, chunk_index, point_id, embedding_model, status, attempts) "
                            + "VALUES (?, ?, ?, ?, ?, 'PENDING', 1)",
                    scope, documentId, chunkIndex, pointId, properties.getEmbeddingModel());
        }
    }

    private void recordFailure(String scope, Long documentId, Integer chunkIndex,
                               String pointId, RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        message = message.length() > 900 ? message.substring(0, 900) : message;
        if (chunkIndex == null) {
            jdbcTemplate.update("UPDATE vector_index_sync SET status='FAILED', last_error=?, "
                            + "updated_at=CURRENT_TIMESTAMP WHERE index_scope=? AND document_id=?",
                    message, scope, documentId);
            return;
        }
        int updated = jdbcTemplate.update("UPDATE vector_index_sync SET status='FAILED', last_error=?, "
                        + "updated_at=CURRENT_TIMESTAMP WHERE index_scope=? AND document_id=? AND chunk_index=? "
                        + "AND embedding_model=?",
                message, scope, documentId, chunkIndex, properties.getEmbeddingModel());
        if (updated == 0 && pointId != null) {
            jdbcTemplate.update("INSERT INTO vector_index_sync "
                            + "(index_scope, document_id, chunk_index, point_id, embedding_model, status, attempts, last_error) "
                            + "VALUES (?, ?, ?, ?, ?, 'FAILED', 1, ?)",
                    scope, documentId, chunkIndex, pointId, properties.getEmbeddingModel(), message);
        }
    }

    private void handleFailure(String message, RuntimeException exception) {
        if (properties.vectorStoreMode() == VectorStoreMode.QDRANT) {
            throw new VectorStoreUnavailableException(message, exception);
        }
        log.warn("{}: errorType={}", message, exception.getClass().getSimpleName());
    }

    private boolean enabled() {
        return properties.vectorStoreMode() != VectorStoreMode.MYSQL;
    }

    private String pointId(String scope, Long documentId, int chunkIndex) {
        String value = "kangban|" + scope + "|" + documentId + "|" + chunkIndex + "|"
                + properties.getEmbeddingModel();
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String safeStatus(String status) {
        return status == null || status.isBlank() ? "UNKNOWN" : status.trim().toUpperCase();
    }

    private void putIfPresent(Map<String, Object> payload, String key, Object value) {
        if (value != null && (!(value instanceof String text) || !text.isBlank())) {
            payload.put(key, value);
        }
    }
}
