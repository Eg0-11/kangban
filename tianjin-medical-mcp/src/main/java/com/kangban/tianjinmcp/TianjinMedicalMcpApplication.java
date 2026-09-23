package com.kangban.tianjinmcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class TianjinMedicalMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(TianjinMedicalMcpApplication.class, args);
    }

    @Bean
    public ToolCallbackProvider medicalTools(McpMedicalTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
