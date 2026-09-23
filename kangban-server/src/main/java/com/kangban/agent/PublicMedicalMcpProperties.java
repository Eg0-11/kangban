package com.kangban.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "app.mcp.public")
public class PublicMedicalMcpProperties {

    private boolean enabled;
    private String serverUrl = "http://localhost:8092";
    private String endpoint = "/mcp";
}
