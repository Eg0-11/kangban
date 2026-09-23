package com.kangban.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MCP 公共医疗目录客户端适配层。
 *
 * <p>这个客户端只转发最小化的公开查询字段，不接受或组装 actorUserId、subjectUserId、memberId、
 * 病历正文和用药正文。患者数据工具继续留在 kangban-server 进程内。</p>
 */
@Slf4j
@Component
public class McpPublicMedicalClient {

    private final ObjectProvider<List<McpSyncClient>> clientsProvider;
    private final ObjectMapper objectMapper;
    private final PublicMedicalMcpProperties properties;

    public McpPublicMedicalClient(ObjectProvider<List<McpSyncClient>> clientsProvider,
                                  ObjectMapper objectMapper,
                                  PublicMedicalMcpProperties properties) {
        this.clientsProvider = clientsProvider;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public String call(String toolName, Map<String, Object> arguments) {
        if (!properties.isEnabled()) {
            throw new McpPublicMedicalException("MCP_PUBLIC_DISABLED", "天津公共医疗信息工具未启用");
        }
        List<McpSyncClient> clients = clientsProvider.getIfAvailable();
        if (clients == null || clients.isEmpty()) {
            throw new McpPublicMedicalException("MCP_UNAVAILABLE", "天津公共医疗信息服务暂时不可用");
        }
        McpSyncClient client = clients.get(0);
        try {
            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest(toolName, arguments == null ? Map.of() : arguments));
            if (result == null || Boolean.TRUE.equals(result.isError())) {
                throw new McpPublicMedicalException("MCP_TOOL_FAILED", "天津公共医疗信息工具调用失败");
            }
            String text = result.content().stream()
                    .filter(content -> content instanceof McpSchema.TextContent)
                    .map(content -> ((McpSchema.TextContent) content).text())
                    .findFirst()
                    .orElse("");
            if (text.isBlank()) {
                throw new McpPublicMedicalException("MCP_EMPTY_RESULT", "天津公共医疗信息工具未返回有效结果");
            }
            if (text.contains("\"dataStatus\":\"EMPTY\"")) {
                throw new McpPublicMedicalException("MCP_EMPTY_RESULT", "天津公共医疗目录暂无可靠数据");
            }
            return text;
        } catch (McpPublicMedicalException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Public medical MCP call failed: tool={}, errorType={}",
                    toolName, e.getClass().getSimpleName());
            throw new McpPublicMedicalException("MCP_UNAVAILABLE", "天津公共医疗信息服务暂时不可用");
        }
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    public static final class McpPublicMedicalException extends RuntimeException {
        private final String errorCode;

        public McpPublicMedicalException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public String errorCode() {
            return errorCode;
        }
    }
}
