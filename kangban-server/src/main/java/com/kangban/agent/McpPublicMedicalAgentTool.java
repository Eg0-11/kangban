package com.kangban.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/** MCP 公共目录工具在现有 AgentTool 白名单中的适配器。 */
public final class McpPublicMedicalAgentTool implements AgentTool {

    private final McpPublicMedicalClient client;
    private final String name;
    private final String description;
    private final Map<String, Object> inputSchema;

    public McpPublicMedicalAgentTool(McpPublicMedicalClient client,
                                     String name,
                                     String description,
                                     Map<String, Object> inputSchema) {
        this.client = client;
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return inputSchema;
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public AgentToolResult execute(AgentExecutionContext context, Map<String, Object> arguments) {
        try {
            Map<String, Object> sanitized = sanitize(arguments);
            return AgentToolResult.success(name, client.call(name, sanitized));
        } catch (McpPublicMedicalClient.McpPublicMedicalException e) {
            return AgentToolResult.failed(name, e.errorCode(), e.getMessage());
        }
    }

    private Map<String, Object> sanitize(Map<String, Object> arguments) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        if (arguments == null) {
            return sanitized;
        }
        for (String key : inputProperties()) {
            Object value = arguments.get(key);
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (containsPrivateMedicalContent(text)) {
                throw new McpPublicMedicalClient.McpPublicMedicalException(
                        "MCP_PUBLIC_INPUT_REJECTED", "公共医疗工具不接受患者或健康数据");
            }
            if (!text.isBlank() && text.length() <= 100) {
                sanitized.put(key, text);
            }
        }
        return sanitized;
    }

    private java.util.Set<String> inputProperties() {
        Object properties = inputSchema.get("properties");
        if (!(properties instanceof Map<?, ?> propertyMap)) {
            return java.util.Set.of();
        }
        return propertyMap.keySet().stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet());
    }

    private boolean containsPrivateMedicalContent(String value) {
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        return normalized.matches(".*(患者|家属|本人|我的|爸爸|妈妈|父亲|母亲|爷爷|奶奶|血压|血糖|心率|体重|用药|吃药|服药|药品|病历|症状|诊断|检查结果|身份证|手机号|user_id|member_id).*" );
    }
}
