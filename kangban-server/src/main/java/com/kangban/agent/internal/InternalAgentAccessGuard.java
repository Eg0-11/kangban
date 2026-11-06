package com.kangban.agent.internal;

import com.kangban.agent.AgentContextSigner;
import com.kangban.agent.AgentExecutionContext;
import com.kangban.agent.AgentProperties;
import com.kangban.common.BusinessException;
import com.kangban.entity.ChatSession;
import com.kangban.entity.FamilyMember;
import com.kangban.entity.User;
import com.kangban.mapper.ChatSessionMapper;
import com.kangban.mapper.FamilyMemberMapper;
import com.kangban.mapper.UserMapper;
import com.kangban.service.FamilyAccessService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class InternalAgentAccessGuard {
    private final InternalAgentProperties properties;
    private final AgentProperties agentProperties;
    private final AgentContextSigner signer;
    private final ChatSessionMapper sessionMapper;
    private final UserMapper userMapper;
    private final FamilyMemberMapper memberMapper;
    private final FamilyAccessService familyAccessService;

    @PostConstruct
    void validateConfiguration() {
        if (properties.isEnabled() && (!configured()
                || properties.getServiceToken().equals(agentProperties.getContextSecret())
                || properties.getMaxRequestMilliseconds() <= 0)) {
            throw new IllegalStateException("启用内部 Agent 接口需要独立的服务凭据、上下文签名密钥及有效超时配置。");
        }
    }

    public InternalAgentCall authorize(String serviceToken, String contextToken, String deadlineHeader) {
        if (!properties.isEnabled() || !agentProperties.isEnabled() || !configured()) {
            throw new InternalAgentException(503, "INTERNAL_AGENT_UNAVAILABLE", "内部 Agent 服务暂未启用。", false);
        }
        if (serviceToken == null || !MessageDigest.isEqual(
                properties.getServiceToken().getBytes(StandardCharsets.UTF_8),
                serviceToken.getBytes(StandardCharsets.UTF_8))) {
            throw new InternalAgentException(401, "INVALID_SERVICE_CREDENTIAL", "内部 Agent 服务身份无效。", false);
        }

        AgentExecutionContext context;
        try {
            context = signer.verify(contextToken);
        } catch (AgentContextSigner.ExpiredContextException exception) {
            throw new InternalAgentException(410, "CONTEXT_EXPIRED", "本次问诊上下文已失效，请重新发送。", false);
        } catch (IllegalArgumentException exception) {
            throw new InternalAgentException(403, "INVALID_CONTEXT", "内部 Agent 上下文无效。", false);
        }

        long deadline;
        try {
            deadline = Long.parseLong(deadlineHeader);
        } catch (NumberFormatException exception) {
            throw new InternalAgentException(400, "INVALID_DEADLINE", "内部 Agent 截止时间无效。", false);
        }
        InternalAgentCall call = new InternalAgentCall(context, deadline);
        call.ensureActive();
        long remaining = deadline - System.currentTimeMillis();
        if (remaining > properties.getMaxRequestMilliseconds()
                || deadline > context.expiresAtEpochSecond() * 1000) {
            throw new InternalAgentException(400, "INVALID_DEADLINE", "内部 Agent 截止时间超出授权范围。", false);
        }
        try {
            requireScope(context);
        } catch (BusinessException exception) {
            throw denied();
        } catch (DataAccessException exception) {
            throw new InternalAgentException(503, "AUTHORIZATION_UNAVAILABLE", "患者授权校验暂时不可用。", true);
        }
        call.ensureActive();
        return call;
    }

    private void requireScope(AgentExecutionContext context) {
        ChatSession session = sessionMapper.selectById(context.sessionId());
        if (session == null || session.getDeletedAt() != null
                || !Objects.equals(session.getUserId(), context.actorUserId())
                || !Objects.equals(session.getSubjectUserId() == null ? session.getUserId() : session.getSubjectUserId(),
                                   context.subjectUserId())
                || !Objects.equals(session.getMemberId(), context.memberId())) {
            throw denied();
        }
        requireActiveUser(userMapper.selectById(context.actorUserId()));
        if (!Objects.equals(context.actorUserId(), context.subjectUserId())) {
            requireActiveUser(userMapper.selectById(context.subjectUserId()));
        }
        familyAccessService.require(context.actorUserId(), context.subjectUserId(), FamilyAccessService.Scope.USE_AI);
        if (context.memberId() != null) {
            FamilyMember member = memberMapper.selectById(context.memberId());
            if (member == null || member.getDeletedAt() != null
                    || !Objects.equals(member.getUserId(), context.subjectUserId())) {
                throw denied();
            }
        }
    }

    private void requireActiveUser(User user) {
        if (user == null || user.getDeletedAt() != null || !Integer.valueOf(1).equals(user.getStatus())) {
            throw denied();
        }
    }

    private boolean configured() {
        return properties.getServiceToken() != null && !properties.getServiceToken().isBlank()
                && agentProperties.getContextSecret() != null && !agentProperties.getContextSecret().isBlank();
    }

    private InternalAgentException denied() {
        return new InternalAgentException(403, "PATIENT_SCOPE_DENIED", "当前账号无权读取该患者健康数据。", false);
    }
}
