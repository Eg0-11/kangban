-- Qdrant is a derived, rebuildable index. MySQL remains the source of truth.
CREATE TABLE vector_index_sync (
    id              BIGINT NOT NULL AUTO_INCREMENT,
    index_scope     VARCHAR(20) NOT NULL,
    document_id     BIGINT NOT NULL,
    chunk_index     INT NOT NULL,
    point_id        VARCHAR(64) NOT NULL,
    embedding_model VARCHAR(100) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts        INT NOT NULL DEFAULT 0,
    last_error      VARCHAR(1000) DEFAULT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    synced_at       TIMESTAMP NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_vis_chunk_model (index_scope, document_id, chunk_index, embedding_model),
    UNIQUE KEY uk_vis_point (point_id),
    KEY idx_vis_status_updated (status, updated_at),
    KEY idx_vis_document (index_scope, document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Qdrant derived vector index synchronization state';
