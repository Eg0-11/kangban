package com.kangban.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class McpPublicMedicalAgentToolTest {

    @Test
    void rejectsPatientContentBeforeMcpCall() {
        McpPublicMedicalClient client = mock(McpPublicMedicalClient.class);
        AgentTool tool = new McpPublicMedicalAgentTool(client, "search_hospitals", "公开医院目录",
                Map.of("type", "object", "properties", Map.of("keyword", Map.of("type", "string"))));

        AgentToolResult result = tool.execute(context(), Map.of("keyword", "我爸爸血压180/110，想查天津医院"));

        assertThat(result.status()).isEqualTo(AgentToolResult.Status.FAILED);
        assertThat(result.errorCode()).isEqualTo("MCP_PUBLIC_INPUT_REJECTED");
        verifyNoInteractions(client);
    }

    private AgentExecutionContext context() {
        long now = System.currentTimeMillis() / 1000;
        return new AgentExecutionContext(9L, 9L, null, 31L,
                "run-mcp-guard", "trace-mcp-guard", now - 1, now + 60);
    }
}
