package com.kangban.rag;

import com.kangban.agent.RagProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Qdrant 派生索引健康状态；不输出地址中的凭据或业务数据。 */
@Component("qdrant")
@RequiredArgsConstructor
public class QdrantHealthIndicator implements HealthIndicator {

    private final RagProperties properties;
    private final VectorStore vectorStore;

    @Override
    public Health health() {
        VectorStoreMode mode = properties.vectorStoreMode();
        if (mode == VectorStoreMode.MYSQL) {
            return Health.up()
                    .withDetail("enabled", false)
                    .withDetail("mode", mode.name())
                    .build();
        }
        boolean available = vectorStore.isAvailable();
        Health.Builder builder = available || mode == VectorStoreMode.DUAL
                ? Health.up() : Health.down();
        return builder.withDetail("enabled", true)
                .withDetail("mode", mode.name())
                .withDetail("available", available)
                .build();
    }
}
