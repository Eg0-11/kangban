package com.kangban.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kangban.agent.ActionProposal;
import com.kangban.agent.AgentActionPlanner;
import com.kangban.agent.AgentExecutionContext;
import com.kangban.common.BusinessException;
import com.kangban.common.Result;
import com.kangban.dto.request.AddHealthRecordRequest;
import com.kangban.dto.request.AddMedicationRequest;
import com.kangban.entity.AgentActionProposal;
import com.kangban.entity.ChatSession;
import com.kangban.entity.FamilyMember;
import com.kangban.entity.User;
import com.kangban.mapper.AgentActionProposalMapper;
import com.kangban.mapper.ChatSessionMapper;
import com.kangban.mapper.FamilyMemberMapper;
import com.kangban.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.math.BigDecimal;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ActionProposalService {

    private static final String PROPOSED = "PROPOSED";
    private static final String CONFIRMED = "CONFIRMED";
    private static final String EXECUTED = "EXECUTED";
    private static final String CANCELLED = "CANCELLED";
    private static final String EXPIRED = "EXPIRED";
    private static final String FAILED = "FAILED";

    private final AgentActionProposalMapper proposalMapper;
    private final ChatSessionMapper chatSessionMapper;
    private final FamilyMemberMapper familyMemberMapper;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;
    private final FamilyAccessService familyAccessService;
    private final HealthService healthService;
    private final MedicationService medicationService;
    private final AuditService auditService;

    @Value("${app.agent.action-proposal-ttl-seconds:300}")
    private long ttlSeconds;

    private final AgentActionPlanner planner = new AgentActionPlanner();

    public Decision propose(AgentExecutionContext context, String message) {
        String patientLabel = patientLabel(context);
        AgentActionPlanner.Decision decision = planner.decide(message, context, patientLabel);
        if (!decision.hasProposal()) {
            return new Decision(decision.handled(), decision.content(), null);
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusSeconds(Math.max(30, ttlSeconds));
        String proposalId = UUID.randomUUID().toString();
        String payloadHash = sha256(decision.payload());
        AgentActionProposal proposal = new AgentActionProposal();
        proposal.setProposalId(proposalId);
        proposal.setActorUserId(context.actorUserId());
        proposal.setSubjectUserId(context.subjectUserId());
        proposal.setMemberId(context.memberId());
        proposal.setSessionId(context.sessionId());
        proposal.setActionType(decision.actionType());
        proposal.setPayloadJson(write(decision.payload()));
        proposal.setPayloadHash(payloadHash);
        proposal.setStatus(PROPOSED);
        proposal.setExpiresAt(expiresAt);
        proposal.setIdempotencyKey(proposalId);
        proposal.setCreatedAt(now);
        proposal.setUpdatedAt(now);
        proposalMapper.insert(proposal);
        auditService.record(context.actorUserId(), "AGENT_ACTION_PROPOSED", "agent_action_proposal",
                proposal.getId(), "actionType=" + decision.actionType() + ",payloadHash=" + payloadHash);

        ActionProposal action = new ActionProposal(proposalId, decision.actionType(),
                ActionProposal.Status.PENDING_CONFIRMATION, decision.payload(), payloadHash,
                expiresAt.toString());
        return new Decision(true, decision.content(), action);
    }

    public ActionProposalView get(Long actorUserId, String proposalId) {
        AgentActionProposal proposal = findForActor(actorUserId, proposalId);
        return toView(proposal);
    }

    @Transactional
    public ActionProposalView confirm(Long actorUserId, String proposalId, String requestedHash) {
        AgentActionProposal proposal = findForActor(actorUserId, proposalId);
        if (!MessageDigest.isEqual(proposal.getPayloadHash().getBytes(StandardCharsets.UTF_8),
                safe(requestedHash).getBytes(StandardCharsets.UTF_8))) {
            throw BusinessException.conflict("草案内容已变化，请重新生成并确认");
        }
        Map<String, Object> payload = readPayload(proposal.getPayloadJson());
        if (!MessageDigest.isEqual(proposal.getPayloadHash().getBytes(StandardCharsets.UTF_8),
                sha256(payload).getBytes(StandardCharsets.UTF_8))) {
            throw BusinessException.conflict("草案校验失败，请重新生成");
        }
        verifySessionBinding(actorUserId, proposal);
        FamilyAccessService.Scope scope = scopeFor(proposal.getActionType());
        familyAccessService.require(actorUserId, proposal.getSubjectUserId(), scope);
        if (EXECUTED.equals(proposal.getStatus())) {
            return toView(proposal);
        }
        if (proposal.getExpiresAt().isBefore(LocalDateTime.now())) {
            markExpired(proposal);
            throw BusinessException.conflict("草案已过期，请重新生成");
        }

        int claimed = proposalMapper.update(null, new UpdateWrapper<AgentActionProposal>()
                .eq("proposal_id", proposalId)
                .eq("actor_user_id", actorUserId)
                .eq("status", PROPOSED)
                .gt("expires_at", LocalDateTime.now())
                .set("status", CONFIRMED)
                .set("confirmed_at", LocalDateTime.now())
                .set("updated_at", LocalDateTime.now()));
        if (claimed == 0) {
            proposal = findForActor(actorUserId, proposalId);
            if (EXECUTED.equals(proposal.getStatus())) return toView(proposal);
            if (CONFIRMED.equals(proposal.getStatus())) {
                return waitForExecution(actorUserId, proposalId);
            }
            if (EXPIRED.equals(proposal.getStatus())) {
                throw BusinessException.conflict("草案已过期，请重新生成");
            }
            throw BusinessException.conflict("草案当前状态不可确认：" + proposal.getStatus());
        }

        try {
            String resultReference = execute(proposal, payload);
            proposalMapper.update(null, new UpdateWrapper<AgentActionProposal>()
                    .eq("proposal_id", proposalId)
                    .set("status", EXECUTED)
                    .set("executed_at", LocalDateTime.now())
                    .set("result_reference", resultReference)
                    .set("updated_at", LocalDateTime.now()));
            AgentActionProposal executed = findForActor(actorUserId, proposalId);
            auditService.record(actorUserId, "AGENT_ACTION_EXECUTED", "agent_action_proposal",
                    executed.getId(), "actionType=" + executed.getActionType() + ",resultReference=" + resultReference);
            return toView(executed);
        } catch (RuntimeException exception) {
            proposalMapper.update(null, new UpdateWrapper<AgentActionProposal>()
                    .eq("proposal_id", proposalId)
                    .set("status", FAILED)
                    .set("error_code", exception.getClass().getSimpleName())
                    .set("updated_at", LocalDateTime.now()));
            throw exception;
        }
    }

    @Transactional
    public ActionProposalView cancel(Long actorUserId, String proposalId) {
        AgentActionProposal proposal = findForActor(actorUserId, proposalId);
        if (EXECUTED.equals(proposal.getStatus())) return toView(proposal);
        if (!PROPOSED.equals(proposal.getStatus())) {
            throw BusinessException.conflict("草案当前状态不可取消：" + proposal.getStatus());
        }
        proposalMapper.update(null, new UpdateWrapper<AgentActionProposal>()
                .eq("proposal_id", proposalId)
                .eq("actor_user_id", actorUserId)
                .eq("status", PROPOSED)
                .set("status", CANCELLED)
                .set("updated_at", LocalDateTime.now()));
        AgentActionProposal cancelled = findForActor(actorUserId, proposalId);
        auditService.record(actorUserId, "AGENT_ACTION_CANCELLED", "agent_action_proposal",
                cancelled.getId(), "actionType=" + cancelled.getActionType());
        return toView(cancelled);
    }

    private String execute(AgentActionProposal proposal, Map<String, Object> payload) {
        if ("HEALTH_RECORD".equals(proposal.getActionType())) {
            validateHealthPayload(payload);
            AddHealthRecordRequest request = new AddHealthRecordRequest();
            request.setSubjectUserId(proposal.getSubjectUserId());
            request.setMemberId(proposal.getMemberId());
            request.setMetric(text(payload, "metric"));
            request.setValue(text(payload, "value"));
            request.setUnit(text(payload, "unit"));
            request.setRecordedDate(LocalDate.parse(text(payload, "recordedDate")));
            request.setRecordedTime(LocalTime.parse(text(payload, "recordedTime")));
            request.setNote(text(payload, "note"));
            Result<Map<String, Object>> result = healthService.addRecord(proposal.getActorUserId(), request);
            return reference(result.getData(), "id");
        }
        if ("MEDICATION".equals(proposal.getActionType())) {
            validateMedicationPayload(payload);
            AddMedicationRequest request = new AddMedicationRequest();
            request.setSubjectUserId(proposal.getSubjectUserId());
            request.setMemberId(proposal.getMemberId());
            request.setName(text(payload, "name"));
            request.setDosage(text(payload, "dosage"));
            request.setUnit(text(payload, "unit"));
            request.setInstruction(text(payload, "instruction"));
            request.setFrequency(text(payload, "frequency"));
            request.setTimes(text(payload, "times"));
            request.setStartDate(dateOrNull(payload, "startDate"));
            request.setEndDate(dateOrNull(payload, "endDate"));
            request.setNote(text(payload, "note"));
            Result<Map<String, Object>> result = medicationService.add(proposal.getActorUserId(), request);
            return reference(result.getData(), "id");
        }
        throw BusinessException.paramsError("不支持的 Agent 写入动作");
    }

    private void validateHealthPayload(Map<String, Object> payload) {
        String metric = text(payload, "metric");
        String value = text(payload, "value");
        String unit = text(payload, "unit");
        if (blank(metric) || blank(value) || blank(unit) || blank(text(payload, "recordedDate"))
                || blank(text(payload, "recordedTime"))) {
            throw BusinessException.paramsError("健康记录草案缺少必填字段");
        }
        if (value.length() > 50 || !validMetricValue(metric, value)) {
            throw BusinessException.paramsError("健康记录数值格式或范围不合法");
        }
    }

    private boolean validMetricValue(String metric, String value) {
        try {
            if ("blood_pressure".equals(metric)) {
                String[] parts = value.split("/");
                if (parts.length != 2) return false;
                double systolic = Double.parseDouble(parts[0]);
                double diastolic = Double.parseDouble(parts[1]);
                return systolic >= 20 && systolic <= 300 && diastolic >= 10 && diastolic <= 200;
            }
            double numeric = Double.parseDouble(value);
            return switch (metric) {
                case "heart_rate" -> numeric >= 20 && numeric <= 250;
                case "temperature" -> numeric >= 25 && numeric <= 45;
                case "weight" -> numeric > 0 && numeric <= 500;
                case "blood_sugar" -> numeric >= 0 && numeric <= 50;
                case "oxygen_saturation" -> numeric >= 0 && numeric <= 100;
                default -> true;
            };
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private void validateMedicationPayload(Map<String, Object> payload) {
        String name = text(payload, "name");
        String dosage = text(payload, "dosage");
        String unit = text(payload, "unit");
        String frequency = text(payload, "frequency");
        String times = text(payload, "times");
        if (blank(name) || blank(dosage) || blank(unit) || blank(frequency) || blank(times)) {
            throw BusinessException.paramsError("用药计划草案缺少药品、剂量、单位、频率或时间");
        }
        if (name.length() > 200 || dosage.length() > 50 || unit.length() > 20 || frequency.length() > 100) {
            throw BusinessException.paramsError("用药计划字段长度不合法");
        }
        try {
            if (new BigDecimal(dosage).signum() <= 0) throw BusinessException.paramsError("剂量必须大于 0");
        } catch (NumberFormatException exception) {
            throw BusinessException.paramsError("剂量必须是明确的数字");
        }
        if (!Pattern.compile("^(?:[01]\\d|2[0-3]):[0-5]\\d(?:,(?:[01]\\d|2[0-3]):[0-5]\\d)*$").matcher(times).matches()) {
            throw BusinessException.paramsError("提醒时间格式不合法");
        }
        LocalDate startDate = dateOrNull(payload, "startDate");
        LocalDate endDate = dateOrNull(payload, "endDate");
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw BusinessException.paramsError("结束日期不能早于开始日期");
        }
    }

    private String reference(Map<String, Object> result, String key) {
        Object value = result == null ? null : result.get(key);
        return value == null ? "completed" : String.valueOf(value);
    }

    private void verifySessionBinding(Long actorUserId, AgentActionProposal proposal) {
        ChatSession session = chatSessionMapper.selectOne(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getId, proposal.getSessionId())
                .eq(ChatSession::getUserId, actorUserId)
                .isNull(ChatSession::getDeletedAt));
        if (session == null) throw BusinessException.forbidden("问诊会话不存在或无权访问");
        Long sessionSubject = session.getSubjectUserId() == null ? actorUserId : session.getSubjectUserId();
        if (!proposal.getSubjectUserId().equals(sessionSubject)
                || !java.util.Objects.equals(proposal.getMemberId(), session.getMemberId())) {
            throw BusinessException.conflict("草案与当前患者会话不一致，请重新生成");
        }
    }

    private FamilyAccessService.Scope scopeFor(String actionType) {
        return "MEDICATION".equals(actionType)
                ? FamilyAccessService.Scope.ADD_MEDICATION
                : FamilyAccessService.Scope.ADD_HEALTH;
    }

    private AgentActionProposal findForActor(Long actorUserId, String proposalId) {
        AgentActionProposal proposal = proposalMapper.selectOne(new LambdaQueryWrapper<AgentActionProposal>()
                .eq(AgentActionProposal::getProposalId, proposalId)
                .eq(AgentActionProposal::getActorUserId, actorUserId));
        if (proposal == null) throw BusinessException.notFound("动作草案不存在或无权访问");
        return proposal;
    }

    private ActionProposalView waitForExecution(Long actorUserId, String proposalId) {
        for (int i = 0; i < 10; i++) {
            try { Thread.sleep(100L); } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            AgentActionProposal current = findForActor(actorUserId, proposalId);
            if (EXECUTED.equals(current.getStatus()) || FAILED.equals(current.getStatus())) return toView(current);
        }
        throw BusinessException.conflict("草案正在执行，请稍后查询结果");
    }

    private void markExpired(AgentActionProposal proposal) {
        proposalMapper.update(null, new UpdateWrapper<AgentActionProposal>()
                .eq("proposal_id", proposal.getProposalId())
                .eq("status", PROPOSED)
                .set("status", EXPIRED)
                .set("updated_at", LocalDateTime.now()));
    }

    private ActionProposalView toView(AgentActionProposal proposal) {
        return new ActionProposalView(proposal.getProposalId(), proposal.getActionType(), proposal.getStatus(),
                readPayload(proposal.getPayloadJson()), proposal.getPayloadHash(), proposal.getExpiresAt(),
                proposal.getConfirmedAt(), proposal.getExecutedAt(), proposal.getResultReference(), proposal.getErrorCode());
    }

    private String patientLabel(AgentExecutionContext context) {
        if (context.memberId() != null) {
            FamilyMember member = familyMemberMapper.selectById(context.memberId());
            if (member != null && context.subjectUserId().equals(member.getUserId())) return member.getName();
        }
        User user = userMapper.selectById(context.subjectUserId());
        return user == null || user.getName() == null || user.getName().isBlank()
                ? "当前患者" : user.getName();
    }

    private Map<String, Object> readPayload(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (JsonProcessingException exception) {
            throw BusinessException.conflict("动作草案内容损坏，请重新生成");
        }
    }

    private String write(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("动作草案序列化失败", exception); }
    }

    private String sha256(Object value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(write(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private LocalDate dateOrNull(Map<String, Object> payload, String key) {
        String value = text(payload, key);
        return blank(value) ? null : LocalDate.parse(value);
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    private String safe(String value) { return value == null ? "" : value; }

    public record Decision(boolean handled, String content, ActionProposal action) { }

    public record ActionProposalView(
            String proposalId,
            String actionType,
            String status,
            Map<String, Object> payload,
            String payloadHash,
            LocalDateTime expiresAt,
            LocalDateTime confirmedAt,
            LocalDateTime executedAt,
            String resultReference,
            String errorCode
    ) { }
}
