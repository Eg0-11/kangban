ALTER TABLE family_permissions
    ADD COLUMN can_add_medication TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '允许为该家庭账号新增用药记录'
        AFTER can_add_health;

ALTER TABLE family_invitations
    ADD COLUMN can_add_medication TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '邀请接受后允许新增用药记录'
        AFTER can_add_health;

CREATE TABLE agent_action_proposals (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    proposal_id      VARCHAR(64)  NOT NULL,
    actor_user_id    BIGINT       NOT NULL,
    subject_user_id  BIGINT       NOT NULL,
    member_id        BIGINT       DEFAULT NULL,
    session_id       BIGINT       NOT NULL,
    action_type      VARCHAR(64)  NOT NULL,
    payload_json     LONGTEXT     NOT NULL,
    payload_hash     CHAR(64)     NOT NULL,
    status           VARCHAR(24)  NOT NULL,
    expires_at       TIMESTAMP    NOT NULL,
    confirmed_at     TIMESTAMP    NULL DEFAULT NULL,
    executed_at      TIMESTAMP    NULL DEFAULT NULL,
    idempotency_key  VARCHAR(128) NOT NULL,
    result_reference VARCHAR(128) DEFAULT NULL,
    error_code       VARCHAR(64)  DEFAULT NULL,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_aap_proposal (proposal_id),
    UNIQUE KEY uk_aap_idempotency (idempotency_key),
    KEY idx_aap_actor_status (actor_user_id, status, created_at),
    KEY idx_aap_session (session_id),
    KEY idx_aap_expiry (status, expires_at),
    CONSTRAINT fk_aap_actor FOREIGN KEY (actor_user_id) REFERENCES users (id),
    CONSTRAINT fk_aap_subject FOREIGN KEY (subject_user_id) REFERENCES users (id),
    CONSTRAINT fk_aap_session FOREIGN KEY (session_id) REFERENCES chat_sessions (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Agent two-phase action proposals; no business write before confirmation';
