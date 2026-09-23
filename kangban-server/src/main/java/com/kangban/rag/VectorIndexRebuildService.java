package com.kangban.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kangban.agent.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 从 MySQL 事实表重建 Qdrant 派生索引。
 *
 * <p>重建不依赖 Qdrant 当前内容；切片正文、发布状态和引用始终以 MySQL 为准。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorIndexRebuildService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final EmbeddingClient embeddingClient;
    private final RagProperties properties;
    private final VectorIndexSyncService vectorIndexSyncService;

    public Map<String, Object> rebuildPublic(Long documentId) {
        if (properties.vectorStoreMode() == VectorStoreMode.MYSQL) {
            return Map.of("status", "SKIPPED", "reason", "当前向量存储模式为 mysql-jdbc");
        }
        List<Map<String, Object>> documents = documentId == null
                ? jdbcTemplate.queryForList("SELECT id, status FROM knowledge_documents "
                + "WHERE deleted_at IS NULL ORDER BY id")
                : jdbcTemplate.queryForList("SELECT id, status FROM knowledge_documents "
                + "WHERE id=? AND deleted_at IS NULL", documentId);
        int chunks = 0;
        int failed = 0;
        for (Map<String, Object> document : documents) {
            Long id = number(document.get("id"));
            if (id == null) {
                continue;
            }
            vectorIndexSyncService.deleteDocument("PUBLIC", id);
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT document_id, chunk_index, page_number, section, content, embedding_json, "
                            + "embedding_model FROM knowledge_chunks WHERE document_id=? ORDER BY chunk_index", id);
            for (Map<String, Object> row : rows) {
                try {
                    double[] vector = vector(row);
                    vectorIndexSyncService.upsertPublicChunk(id, integer(row.get("chunk_index")),
                            integer(row.get("page_number")), text(row.get("section")), vector,
                            text(document.get("status")));
                    chunks++;
                } catch (RuntimeException e) {
                    failed++;
                    log.warn("Public vector rebuild failed: documentId={}, chunkIndex={}, errorType={}",
                            id, row.get("chunk_index"), e.getClass().getSimpleName());
                    if (properties.vectorStoreMode() == VectorStoreMode.QDRANT) {
                        throw e;
                    }
                }
            }
        }
        return Map.of("status", failed == 0 ? "SUCCEEDED" : "PARTIAL",
                "documents", documents.size(), "chunks", chunks, "failed", failed,
                "documentId", documentId == null ? "all" : documentId);
    }

    private double[] vector(Map<String, Object> row) {
        String model = text(row.get("embedding_model"));
        double[] stored = parse(row.get("embedding_json"));
        if (properties.getEmbeddingModel().equals(model)
                && stored.length == properties.getEmbeddingDimensions()) {
            return stored;
        }
        String content = text(row.get("content"));
        if (content.isBlank()) {
            throw new IllegalArgumentException("知识片段正文为空，无法重建向量");
        }
        return embeddingClient.embed(content);
    }

    private double[] parse(Object raw) {
        if (raw == null) {
            return new double[0];
        }
        try {
            return objectMapper.readValue(String.valueOf(raw), new TypeReference<>() {});
        } catch (Exception e) {
            return new double[0];
        }
    }

    private Long number(Object value) {
        return value instanceof Number number ? number.longValue() : parseLong(text(value));
    }

    private Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : parseInteger(text(value));
    }

    private Long parseLong(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Integer parseInteger(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
