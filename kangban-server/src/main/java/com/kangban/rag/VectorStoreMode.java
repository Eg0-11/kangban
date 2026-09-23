package com.kangban.rag;

import java.util.Locale;

/** RAG 向量索引运行模式。 */
public enum VectorStoreMode {
    MYSQL,
    DUAL,
    QDRANT;

    public static VectorStoreMode parse(String value) {
        if (value == null || value.isBlank()) {
            return MYSQL;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "mysql", "mysql-jdbc", "memory" -> MYSQL;
            case "dual" -> DUAL;
            case "qdrant" -> QDRANT;
            default -> throw new IllegalArgumentException("不支持的 RAG 向量存储模式: " + value);
        };
    }
}
