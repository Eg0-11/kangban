package com.kangban.agent;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
public class PublicMedicalMcpToolConfiguration {

    @Bean
    public AgentTool publicHospitalSearchTool(McpPublicMedicalClient client) {
        return tool(client, "search_hospitals", "查询天津公共医院目录，不接收患者数据。",
                "keyword", "hospitalLevel", "department");
    }

    @Bean
    public AgentTool publicHospitalInfoTool(McpPublicMedicalClient client) {
        return tool(client, "get_hospital_info", "查询天津公共医院公开详情。",
                "hospitalId", "hospitalName");
    }

    @Bean
    public AgentTool publicDoctorSearchTool(McpPublicMedicalClient client) {
        return tool(client, "search_doctors", "查询天津公开医生目录，不接收患者数据。",
                "keyword", "department", "hospitalName", "doctorTitle");
    }

    @Bean
    public AgentTool publicDepartmentSearchTool(McpPublicMedicalClient client) {
        return tool(client, "search_departments", "查询天津公共医院科室目录。",
                "keyword", "hospitalName");
    }

    private AgentTool tool(McpPublicMedicalClient client, String name, String description, String... fields) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (String field : fields) {
            properties.put(field, Map.of("type", "string"));
        }
        return new McpPublicMedicalAgentTool(client, name, description,
                Map.of("type", "object", "properties", properties));
    }
}
