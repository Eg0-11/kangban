package com.kangban.rag;

import java.util.List;
import java.util.Map;

/**
 * RAG 向量索引的最小契约。
 *
 * <p>MySQL 仍保存正文、引用和权限事实；向量存储只负责可重建的索引数据。</p>
 */
public interface VectorStore {

    void upsert(VectorRecord record);

    void delete(String pointId);

    void deleteByFilter(Map<String, Object> filters);

    void updatePayloadByFilter(Map<String, Object> filters, Map<String, Object> payload);

    List<VectorSearchHit> search(double[] queryVector, int limit, Map<String, Object> filters);

    boolean isAvailable();

    default void ensureReady() {
    }

    record VectorRecord(String pointId, double[] vector, Map<String, Object> payload) {
    }

    record VectorSearchHit(String pointId, double score, Map<String, Object> payload) {
    }
}
