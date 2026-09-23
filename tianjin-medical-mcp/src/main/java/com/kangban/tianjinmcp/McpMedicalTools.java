package com.kangban.tianjinmcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class McpMedicalTools {

    private final MedicalInfoCatalog catalog;
    private final ObjectMapper objectMapper;

    public McpMedicalTools(MedicalInfoCatalog catalog, ObjectMapper objectMapper) {
        this.catalog = catalog;
        this.objectMapper = objectMapper.findAndRegisterModules();
    }

    @Tool(name = "search_hospitals", description = "查询天津公共医院目录。只返回公开目录信息，不接受或处理患者身份、病历和用药数据。")
    public String searchHospitals(
            @ToolParam(description = "医院名称或地址关键词，可为空", required = false) String keyword,
            @ToolParam(description = "医院等级，例如三级甲等，可为空", required = false) String hospitalLevel,
            @ToolParam(description = "科室名称，可为空", required = false) String department) {
        return result("search_hospitals", catalog.searchHospitals(keyword, hospitalLevel, department));
    }

    @Tool(name = "get_hospital_info", description = "获取天津公共医院的公开详情。必须提供医院 ID 或医院名称之一。")
    public String getHospitalInfo(
            @ToolParam(description = "公共医院 ID，可为空", required = false) String hospitalId,
            @ToolParam(description = "医院名称，可为空", required = false) String hospitalName) {
        if ((hospitalId == null || hospitalId.isBlank()) && (hospitalName == null || hospitalName.isBlank())) {
            return error("INVALID_ARGUMENT", "hospitalId 或 hospitalName 至少提供一个");
        }
        return result("get_hospital_info", catalog.getHospitalInfo(hospitalId, hospitalName));
    }

    @Tool(name = "search_doctors", description = "查询天津公开医生目录。只返回公开姓名、职称、科室和医院信息，不处理患者数据。")
    public String searchDoctors(
            @ToolParam(description = "医生姓名关键词，可为空", required = false) String keyword,
            @ToolParam(description = "科室名称，可为空", required = false) String department,
            @ToolParam(description = "医院名称，可为空", required = false) String hospitalName,
            @ToolParam(description = "医生职称，可为空", required = false) String doctorTitle) {
        return result("search_doctors", catalog.searchDoctors(keyword, department, hospitalName, doctorTitle));
    }

    @Tool(name = "search_departments", description = "查询天津公共医院科室目录，可按科室或医院名称过滤。")
    public String searchDepartments(
            @ToolParam(description = "科室关键词，可为空", required = false) String keyword,
            @ToolParam(description = "医院名称，可为空", required = false) String hospitalName) {
        return result("search_departments", catalog.searchDepartments(keyword, hospitalName));
    }

    private String result(String tool, List<MedicalPublicRecord> items) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", tool);
        payload.put("source", "天津公共医疗目录");
        String dataStatus = items.isEmpty() ? "EMPTY"
                : (items.stream().anyMatch(item -> "DEMO".equals(item.status())) ? "DEMO" : "IMPORTED");
        payload.put("dataStatus", dataStatus);
        payload.put("items", items);
        payload.put("resultCount", items.size());
        payload.put("notice", "公共目录仅供信息查询，具体出诊和医疗服务请以官方来源为准。");
        return write(payload);
    }

    private String error(String code, String message) {
        return write(Map.of("errorCode", code, "message", message, "items", List.of()));
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("公共医疗目录响应序列化失败", e);
        }
    }
}
