package com.kangban.agent;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Agent/RAG 配置健康检查，不输出 API key、签名密钥或业务正文。
 */
@Component("kangbanAgent")
@RequiredArgsConstructor
public class AgentHealthIndicator implements HealthIndicator {

    private final AgentProperties agentProperties;
    private final RagProperties ragProperties;
    private final PythonAgentGateway gateway;

    @Override
    public Health health() {
        boolean providerConfigured = gateway.isConfigured();
        boolean pythonHealthy = agentProperties.isEnabled() && gateway.isPythonHealthy();
        Health.Builder builder;
        if (!agentProperties.isEnabled()) {
            builder = Health.outOfService();
        } else if (!providerConfigured || !pythonHealthy) {
            builder = Health.down();
        } else {
            builder = Health.up();
        }
        return builder
                .withDetail("enabled", agentProperties.isEnabled())
                .withDetail("provider", "python")
                .withDetail("providerConfigured", providerConfigured)
                .withDetail("pythonHealthy", pythonHealthy)
                .withDetail("ragEnabled", ragProperties.isEnabled())
                .withDetail("vectorStore", ragProperties.getVectorStore())
                .build();
    }
}
