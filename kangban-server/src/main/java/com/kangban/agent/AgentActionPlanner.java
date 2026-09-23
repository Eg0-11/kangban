package com.kangban.agent;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将用户明确表达的录入意图转换为草案字段。这里不做任何数据库写入，也不替用户补齐关键医疗字段。
 */
public final class AgentActionPlanner {

    private static final Pattern BLOOD_PRESSURE = Pattern.compile("血压\\s*[:：]?\\s*(\\d{2,3}\\s*/\\s*\\d{2,3})");
    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)");
    private static final Pattern DATE = Pattern.compile("(\\d{4})[-年](\\d{1,2})[月-](\\d{1,2})日?");
    private static final Pattern MONTH_DAY = Pattern.compile("(\\d{1,2})月(\\d{1,2})日?");
    private static final Pattern EXACT_TIME = Pattern.compile("(?:(早上|上午|中午|下午|晚上|夜间)\\s*)?([01]?\\d|2[0-3])\\s*(?:点|时)(?:([0-5]\\d)分?)?");
    private static final Pattern DOSE = Pattern.compile("每次\\s*([0-9一二两三四五六七八九十半]+(?:\\.\\d+)?)\\s*(片|粒|袋|丸|毫克|mg|克|g|毫升|ml|滴)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FREQUENCY = Pattern.compile("((?:每天|每日|一天|每周|每晚|每晨)\\s*(?:[0-9一二两三四五六七八九十]+\\s*次|一次|两次|三次|四次|五次))");

    private static final Map<String, String> METRICS = Map.of(
            "血压", "blood_pressure",
            "血糖", "blood_sugar",
            "心率", "heart_rate",
            "体温", "temperature",
            "体重", "weight",
            "血氧", "oxygen_saturation"
    );

    public Decision decide(String message, AgentExecutionContext context, String patientLabel) {
        String text = message == null ? "" : message.trim();
        if (text.isBlank()) {
            return Decision.none();
        }

        if (isMedicationIntent(text)) {
            if (isUnsafeMedicationRequest(text)) {
                return Decision.handled("我可以帮您记录已经明确的用药信息，但不能替您推荐药物、调整剂量、停药或换药。请先咨询医生，并提供医生已确定的药品、剂量、单位、频率和时间。");
            }
            return planMedication(text, context, patientLabel);
        }

        if (containsWriteVerb(text) && containsMetric(text)) {
            return planHealth(text, context, patientLabel);
        }
        return Decision.none();
    }

    private Decision planHealth(String text, AgentExecutionContext context, String patientLabel) {
        String metricLabel = METRICS.keySet().stream().filter(text::contains).findFirst().orElse(null);
        String metric = METRICS.get(metricLabel);
        String value = extractHealthValue(text, metricLabel);
        String unit = extractUnit(text, value);
        LocalDate date = extractDate(text);
        LocalTime time = extractTime(text);
        List<String> missing = new ArrayList<>();
        if (value == null) missing.add("数值");
        if (unit == null) missing.add("单位");
        if (date == null) missing.add("日期");
        if (time == null) missing.add("时间");
        if (!missing.isEmpty()) {
            return Decision.handled("为了安全保存健康记录，还需要您补充：" + String.join("、", missing) + "。请不要让我替您猜测这些字段。");
        }
        if (context.memberId() == null && mentionsFamilyPatient(text)) {
            return Decision.handled("请先在问诊页面左侧选择要记录的家庭成员，我不会根据称呼猜测患者身份。");
        }

        Map<String, Object> payload = basePayload("HEALTH_RECORD", context, patientLabel);
        payload.put("metric", metric);
        payload.put("metricLabel", metricLabel);
        payload.put("value", value);
        payload.put("unit", unit);
        payload.put("recordedDate", date.toString());
        payload.put("recordedTime", time.toString());
        payload.put("note", extractNote(text));
        return Decision.proposal("HEALTH_RECORD", payload,
                "已整理健康记录草案，请核对患者、指标、数值、单位、日期和时间后确认。尚未写入数据库。");
    }

    private Decision planMedication(String text, AgentExecutionContext context, String patientLabel) {
        String name = extractMedicationName(text);
        Matcher doseMatcher = DOSE.matcher(text);
        boolean hasDose = doseMatcher.find();
        String dosage = hasDose ? normalizeNumber(doseMatcher.group(1)) : null;
        String unit = hasDose ? doseMatcher.group(2) : null;
        Matcher frequencyMatcher = FREQUENCY.matcher(text);
        String frequency = frequencyMatcher.find() ? frequencyMatcher.group(1).replaceAll("\\s+", "") : null;
        LocalTime time = extractTime(text);
        LocalDate startDate = text.contains("今天开始") || text.contains("从今天") ? LocalDate.now() : extractDate(text);
        LocalDate endDate = extractEndDate(text);
        List<String> missing = new ArrayList<>();
        if (name == null) missing.add("药品名称");
        if (dosage == null) missing.add("单次剂量");
        if (unit == null) missing.add("剂量单位");
        if (frequency == null) missing.add("服用频率");
        if (time == null) missing.add("提醒时间");
        if (!missing.isEmpty()) {
            return Decision.handled("为了安全保存用药计划，还需要您补充：" + String.join("、", missing) + "。我不会自行推断剂量、频率或时间。");
        }
        if (context.memberId() == null && mentionsFamilyPatient(text)) {
            return Decision.handled("请先在问诊页面左侧选择要记录的家庭成员，我不会根据称呼猜测患者身份。");
        }

        Map<String, Object> payload = basePayload("MEDICATION", context, patientLabel);
        payload.put("name", name);
        payload.put("dosage", dosage);
        payload.put("unit", unit);
        payload.put("frequency", frequency);
        payload.put("times", time.toString());
        payload.put("startDate", startDate == null ? null : startDate.toString());
        payload.put("endDate", endDate == null ? null : endDate.toString());
        payload.put("instruction", "仅记录用户明确提供的计划");
        payload.put("note", text.contains("医生") || text.contains("医嘱") ? "信息来源：医生医嘱" : "信息来源：用户提供");
        payload.put("informationSource", text.contains("医生") || text.contains("医嘱") ? "DOCTOR_INSTRUCTION" : "USER_PROVIDED");
        return Decision.proposal("MEDICATION", payload,
                "已整理用药计划草案，请核对患者、药品、剂量、频率和提醒时间后确认。尚未写入数据库，也不代表处方。");
    }

    private Map<String, Object> basePayload(String actionType, AgentExecutionContext context, String patientLabel) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("actionType", actionType);
        payload.put("patientLabel", patientLabel);
        payload.put("actorUserId", context.actorUserId());
        payload.put("subjectUserId", context.subjectUserId());
        payload.put("memberId", context.memberId());
        payload.put("sessionId", context.sessionId());
        return payload;
    }

    private boolean isMedicationIntent(String text) {
        boolean medicationWords = text.contains("药") || text.contains("服用") || text.contains("吃药")
                || text.contains("用药") || text.contains("剂量") || text.contains("停药");
        boolean medicationShape = DOSE.matcher(text).find() || FREQUENCY.matcher(text).find();
        return (medicationWords || medicationShape)
                && (containsWriteVerb(text) || medicationShape || isUnsafeMedicationRequest(text));
    }

    private boolean isUnsafeMedicationRequest(String text) {
        return text.matches(".*(推荐|建议).*(药|用药).*|.*(吃什么药|增加.*剂量|调整.*剂量|加量|减量|停药|停止服用|换药|换成).*");
    }

    private boolean containsWriteVerb(String text) {
        return text.matches(".*(记录|录入|添加|填写|保存|记下|安排).*");
    }

    private boolean containsMetric(String text) {
        return METRICS.keySet().stream().anyMatch(text::contains);
    }

    private boolean mentionsFamilyPatient(String text) {
        return text.matches(".*(爸爸|妈妈|父亲|母亲|爷爷|奶奶|外公|外婆|家属|家人).*");
    }

    private String extractHealthValue(String text, String metricLabel) {
        if ("血压".equals(metricLabel)) {
            Matcher matcher = BLOOD_PRESSURE.matcher(text);
            return matcher.find() ? matcher.group(1).replaceAll("\\s+", "") : null;
        }
        int metricIndex = text.indexOf(metricLabel);
        if (metricIndex < 0) return null;
        Matcher matcher = NUMBER.matcher(text.substring(metricIndex + metricLabel.length()));
        return matcher.find() ? matcher.group(1) : null;
    }

    private String extractUnit(String text, String value) {
        if (value == null) return null;
        int start = text.indexOf(value);
        String tail = start < 0 ? text : text.substring(start + value.length());
        Matcher matcher = Pattern.compile("^(?:\\s*)(mmHg|毫米汞柱|mmol/L|毫摩尔/升|kg|公斤|千克|斤|℃|度|次/分|bpm|%|百分比)", Pattern.CASE_INSENSITIVE).matcher(tail);
        if (!matcher.find()) return null;
        return switch (matcher.group(1).toLowerCase(Locale.ROOT)) {
            case "公斤", "千克" -> "kg";
            case "斤" -> "斤";
            case "毫米汞柱" -> "mmHg";
            case "毫摩尔/升" -> "mmol/L";
            case "度" -> "℃";
            case "bpm" -> "次/分";
            case "百分比" -> "%";
            default -> matcher.group(1);
        };
    }

    private LocalDate extractDate(String text) {
        if (text.contains("今天")) return LocalDate.now();
        if (text.contains("昨天")) return LocalDate.now().minusDays(1);
        Matcher full = DATE.matcher(text);
        if (full.find()) return LocalDate.of(Integer.parseInt(full.group(1)), Integer.parseInt(full.group(2)), Integer.parseInt(full.group(3)));
        Matcher monthDay = MONTH_DAY.matcher(text);
        if (monthDay.find()) return LocalDate.of(Year.now().getValue(), Integer.parseInt(monthDay.group(1)), Integer.parseInt(monthDay.group(2)));
        return null;
    }

    private LocalDate extractEndDate(String text) {
        int marker = Math.max(text.indexOf("至"), text.indexOf("到期"));
        if (marker < 0) return null;
        return extractDate(text.substring(marker));
    }

    private LocalTime extractTime(String text) {
        Matcher matcher = EXACT_TIME.matcher(text);
        if (matcher.find()) {
            int hour = Integer.parseInt(matcher.group(2));
            if (("下午".equals(matcher.group(1)) || "晚上".equals(matcher.group(1)) || "夜间".equals(matcher.group(1))) && hour < 12) hour += 12;
            return LocalTime.of(hour, matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3)));
        }
        if (text.contains("早上") || text.contains("上午")) return LocalTime.of(8, 0);
        if (text.contains("中午")) return LocalTime.NOON;
        if (text.contains("下午")) return LocalTime.of(15, 0);
        if (text.contains("晚上") || text.contains("夜间")) return LocalTime.of(20, 0);
        return null;
    }

    private String extractMedicationName(String text) {
        Matcher explicit = Pattern.compile("药品(?:名称)?\\s*[:：]\\s*([^，,。；;\\s]+)").matcher(text);
        if (explicit.find()) return explicit.group(1);
        Matcher afterVerb = Pattern.compile("(?:添加|记录|填写|保存|安排)\\s*(?:给[^，,。；;\\s]+\\s*)?([^，,。；;\\s]+)").matcher(text);
        if (afterVerb.find()) {
            String value = afterVerb.group(1);
            if (!value.matches("(药品|用药|药物|每天|每日|每次).*")) return value;
        }
        return null;
    }

    private String extractNote(String text) {
        Matcher matcher = Pattern.compile("(?:备注|说明)\\s*[:：]?\\s*(.+)$").matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private String normalizeNumber(String value) {
        return switch (value) {
            case "一" -> "1";
            case "两", "二" -> "2";
            case "三" -> "3";
            case "四" -> "4";
            case "五" -> "5";
            case "半" -> "0.5";
            default -> value;
        };
    }

    public record Decision(boolean handled, String content, String actionType, Map<String, Object> payload) {
        public static Decision none() { return new Decision(false, null, null, Map.of()); }
        public static Decision handled(String content) { return new Decision(true, content, null, Map.of()); }
        public static Decision proposal(String actionType, Map<String, Object> payload, String content) {
            return new Decision(true, content, actionType, payload);
        }
        public boolean hasProposal() { return actionType != null && !payload.isEmpty(); }
    }
}
