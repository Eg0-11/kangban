package com.kangban.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 康伴医疗 Agent 的受控工具规划器。
 *
 * <p>模型不直接获得 SQL 或任意函数执行权。规划器只根据问题选择固定白名单工具，
 * 身份和患者范围始终来自服务端上下文。</p>
 */
public final class AgentToolPlanner {

    private static final int DEFAULT_LIMIT = 20;

    public List<AgentToolCall> plan(String message, int maxIterations) {
        String query = message == null ? "" : message.trim().toLowerCase(Locale.ROOT);
        Map<String, AgentToolCall> calls = new LinkedHashMap<>();

        if (containsAny(query, "血压", "心率", "心跳", "血糖", "体重", "步数", "睡眠", "健康指标", "健康数据", "趋势", "测量", "异常")) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            String metric = metricFor(query);
            if (metric != null) {
                arguments.put("metric", metric);
            }
            arguments.put("limit", DEFAULT_LIMIT);
            calls.put("get_health_metrics", new AgentToolCall("get_health_metrics", arguments));
        }
        if (containsAny(query, "用药", "吃药", "服药", "药物", "药品", "剂量", "漏服", "相互作用")) {
            calls.put("get_active_medications", new AgentToolCall(
                    "get_active_medications", Map.of("limit", DEFAULT_LIMIT)));
        }
        boolean publicHospitalQuery = isPublicHospitalQuery(query);
        boolean publicMedicalQuery = publicHospitalQuery || containsAny(query,
                "医生", "专家", "医师", "科室", "门诊");
        if (!publicMedicalQuery && containsAny(query, "病历", "报告", "检查", "体检", "诊断", "医院")) {
            calls.put("get_recent_medical_records", new AgentToolCall(
                    "get_recent_medical_records", Map.of("limit", 5)));
        }
        if (publicHospitalQuery) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("keyword", extractPublicKeyword(message, "hospital"));
            if (containsAny(query, "三甲")) {
                arguments.put("hospitalLevel", "三级甲等");
            }
            calls.put("search_hospitals", new AgentToolCall("search_hospitals", arguments));
        } else if (containsAny(query, "医生", "专家", "医师")) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("keyword", extractPublicKeyword(message, "doctor"));
            calls.put("search_doctors", new AgentToolCall("search_doctors", arguments));
        } else if (containsAny(query, "科室", "门诊")) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("keyword", extractPublicKeyword(message, "department"));
            calls.put("search_departments", new AgentToolCall("search_departments", arguments));
        }
        if (calls.isEmpty()) {
            calls.put("get_patient_health_snapshot", new AgentToolCall(
                    "get_patient_health_snapshot", Map.of()));
        }

        int limit = Math.max(1, maxIterations);
        return calls.values().stream().limit(limit).toList();
    }

    private String metricFor(String query) {
        if (containsAny(query, "血压")) return "blood_pressure";
        if (containsAny(query, "心率", "心跳")) return "heart_rate";
        if (containsAny(query, "血糖")) return "blood_glucose";
        if (containsAny(query, "体重")) return "weight";
        if (containsAny(query, "步数")) return "steps";
        if (containsAny(query, "睡眠")) return "sleep";
        return null;
    }

    private boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) return true;
        }
        return false;
    }

    private boolean isPublicHospitalQuery(String query) {
        if (containsAny(query, "天津医院", "医院信息", "医院地址", "医院等级", "三甲", "公共医院")) {
            return true;
        }
        return query.matches(".*(?:查一下|想查|查询|查找|了解|看看|查|找).{0,30}医院.*")
                && !containsAny(query, "我的医院", "我在医院", "住院", "病历", "报告", "检查", "诊断",
                "患者", "家属", "本人");
    }

    private String extractPublicKeyword(String message, String type) {
        String value = message == null ? "" : message.trim();
        value = afterLastPublicQueryVerb(value);
        String expression = switch (type) {
            case "hospital" -> "([\\p{IsHan}A-Za-z0-9]{2,30}医院)";
            case "doctor" -> "([\\p{IsHan}]{2,10}(?:医生|专家|医师))";
            default -> "([\\p{IsHan}A-Za-z0-9]{1,20}科)";
        };
        Matcher matcher = Pattern.compile(expression).matcher(value);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String afterLastPublicQueryVerb(String value) {
        String[] verbs = {"查一下", "想查", "查询", "查找", "了解", "看看", "查", "找"};
        int start = -1;
        int length = 0;
        for (String verb : verbs) {
            int candidate = value.lastIndexOf(verb);
            if (candidate > start) {
                start = candidate;
                length = verb.length();
            }
        }
        return start < 0 ? value : value.substring(start + length);
    }
}
