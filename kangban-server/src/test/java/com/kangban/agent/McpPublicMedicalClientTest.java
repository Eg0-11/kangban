package com.kangban.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpPublicMedicalClientTest {

    @Test
    void forwardsOnlySuccessfulPublicToolResponse() {
        McpSyncClient mcp = mock(McpSyncClient.class);
        when(mcp.callTool(org.mockito.ArgumentMatchers.any())).thenReturn(
                new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("{\"dataStatus\":\"DEMO\"}")), false));

        String response = client(true, mcp).call("search_hospitals", Map.of("keyword", "天津医院"));

        assertThat(response).contains("DEMO");
    }

    @Test
    void blocksDisabledUnavailableFailedAndEmptyResponses() {
        assertThatThrownBy(() -> client(false, null).call("search_hospitals", Map.of()))
                .isInstanceOf(McpPublicMedicalClient.McpPublicMedicalException.class)
                .hasMessage("天津公共医疗信息工具未启用");
        assertThatThrownBy(() -> client(true, null).call("search_hospitals", Map.of()))
                .isInstanceOf(McpPublicMedicalClient.McpPublicMedicalException.class)
                .hasMessage("天津公共医疗信息服务暂时不可用");

        McpSyncClient failed = mock(McpSyncClient.class);
        when(failed.callTool(org.mockito.ArgumentMatchers.any())).thenThrow(new RuntimeException("timeout"));
        assertThatThrownBy(() -> client(true, failed).call("search_hospitals", Map.of()))
                .isInstanceOf(McpPublicMedicalClient.McpPublicMedicalException.class)
                .hasMessage("天津公共医疗信息服务暂时不可用");

        McpSyncClient empty = mock(McpSyncClient.class);
        when(empty.callTool(org.mockito.ArgumentMatchers.any())).thenReturn(
                new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("{\"dataStatus\":\"EMPTY\"}")), false));
        assertThatThrownBy(() -> client(true, empty).call("search_hospitals", Map.of()))
                .isInstanceOf(McpPublicMedicalClient.McpPublicMedicalException.class)
                .hasMessage("天津公共医疗目录暂无可靠数据");
    }

    private McpPublicMedicalClient client(boolean enabled, McpSyncClient mcp) {
        PublicMedicalMcpProperties properties = new PublicMedicalMcpProperties();
        properties.setEnabled(enabled);
        @SuppressWarnings("unchecked")
        ObjectProvider<List<McpSyncClient>> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mcp == null ? List.of() : List.of(mcp));
        return new McpPublicMedicalClient(provider, new ObjectMapper(), properties);
    }
}
