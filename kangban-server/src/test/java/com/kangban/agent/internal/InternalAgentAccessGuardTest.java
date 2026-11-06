package com.kangban.agent.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InternalAgentAccessGuardTest {
    private final InternalAgentProperties properties = new InternalAgentProperties();
    private final AgentProperties agentProperties = new AgentProperties();
    private final ChatSessionMapper sessions = mock(ChatSessionMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final FamilyMemberMapper members = mock(FamilyMemberMapper.class);
    private final FamilyAccessService access = mock(FamilyAccessService.class);
    private final AgentContextSigner signer = new AgentContextSigner(new ObjectMapper(), agentProperties);
    private final InternalAgentAccessGuard guard = new InternalAgentAccessGuard(
            properties, agentProperties, signer, sessions, users, members, access);
    private ChatSession session;
    private AgentExecutionContext context;
    private User actor;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setServiceToken("test-only-service-token");
        agentProperties.setContextSecret("test-only-distinct-context-secret");
        long now = System.currentTimeMillis() / 1000;
        context = new AgentExecutionContext(9L, 9L, null, 31L, "run-test", "trace-test", now - 1, now + 300);
        session = new ChatSession();
        session.setId(31L);
        session.setUserId(9L);
        actor = new User();
        actor.setStatus(1);
        when(sessions.selectById(31L)).thenReturn(session);
        when(users.selectById(9L)).thenReturn(actor);
    }

    @Test
    void acceptsOnlySignedScopeAndRechecksCurrentAuthorization() {
        InternalAgentCall call = authorize();
        assertThat(call.context()).isEqualTo(context);
        verify(access).require(9L, 9L, FamilyAccessService.Scope.USE_AI);
        verifyNoInteractions(members);
    }

    @Test
    void disabledInternalServiceDoesNotReadPatientMetadata() {
        properties.setEnabled(false);
        failure(this::authorize, 503, "INTERNAL_AGENT_UNAVAILABLE");
        verifyNoInteractions(sessions, users, members, access);
    }

    @Test
    void refusesMissingAndWrongServiceCredentialsBeforeReadingData() {
        failure(() -> guard.authorize(null, signer.sign(context), deadline()), 401, "INVALID_SERVICE_CREDENTIAL");
        failure(() -> guard.authorize("wrong-token", signer.sign(context), deadline()), 401, "INVALID_SERVICE_CREDENTIAL");
        verifyNoInteractions(sessions, users, members, access);
    }

    @Test
    void refusesTamperedAndExpiredSignedContexts() {
        String[] parts = signer.sign(context).split("\\.");
        String forged = parts[0] + ".A" + parts[1].substring(1) + "." + parts[2];
        failure(() -> guard.authorize(properties.getServiceToken(), forged, deadline()), 403, "INVALID_CONTEXT");
        long now = System.currentTimeMillis() / 1000;
        AgentExecutionContext expired = new AgentExecutionContext(9L, 9L, null, 31L,
                "run-expired", "trace-test", now - 10, now - 1);
        failure(() -> guard.authorize(properties.getServiceToken(), signer.sign(expired), deadline()), 410, "CONTEXT_EXPIRED");
        verifyNoInteractions(sessions, users, members, access);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "not-a-deadline", "999999999999999999999999"})
    void refusesMalformedDeadline(String value) {
        failure(() -> guard.authorize(properties.getServiceToken(), signer.sign(context), value), 400, "INVALID_DEADLINE");
        verifyNoInteractions(sessions, users, members, access);
    }

    @Test
    void refusesExpiredAndExcessiveBudgetsBeforeReadingData() {
        failure(() -> guard.authorize(properties.getServiceToken(), signer.sign(context),
                Long.toString(System.currentTimeMillis() - 1)), 504, "DEADLINE_EXCEEDED");
        failure(() -> guard.authorize(properties.getServiceToken(), signer.sign(context),
                Long.toString(System.currentTimeMillis() + properties.getMaxRequestMilliseconds() + 10_000)),
                400, "INVALID_DEADLINE");
        verifyNoInteractions(sessions, users, members, access);
    }

    @Test
    void acceptsDeadlineAtContextExpiryButRejectsBudgetBeyondIt() {
        long now = System.currentTimeMillis() / 1000;
        context = new AgentExecutionContext(9L, 9L, null, 31L, context.runId(), context.traceId(), now - 1, now + 30);
        String expiry = Long.toString(context.expiresAtEpochSecond() * 1000);
        assertThat(guard.authorize(properties.getServiceToken(), signer.sign(context), expiry).context()).isEqualTo(context);
        failure(() -> guard.authorize(properties.getServiceToken(), signer.sign(context),
                Long.toString(context.expiresAtEpochSecond() * 1000 + 1)), 400, "INVALID_DEADLINE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"actor", "subject", "member", "deleted", "missing"})
    void rejectsSessionScopeChanges(String change) {
        switch (change) {
            case "actor" -> session.setUserId(99L);
            case "subject" -> session.setSubjectUserId(99L);
            case "member" -> session.setMemberId(99L);
            case "deleted" -> session.setDeletedAt(LocalDateTime.now());
            case "missing" -> when(sessions.selectById(31L)).thenReturn(null);
        }
        failure(this::authorize, 403, "PATIENT_SCOPE_DENIED");
        verifyNoInteractions(users, members, access);
    }

    @ParameterizedTest
    @ValueSource(strings = {"inactive", "deleted", "missing"})
    void rejectsUnavailableUserAccounts(String change) {
        switch (change) {
            case "inactive" -> actor.setStatus(0);
            case "deleted" -> actor.setDeletedAt(LocalDateTime.now());
            case "missing" -> when(users.selectById(9L)).thenReturn(null);
        }
        failure(this::authorize, 403, "PATIENT_SCOPE_DENIED");
    }

    @Test
    void rechecksRevokedFamilyPermissionOnEveryCallback() {
        session.setSubjectUserId(15L);
        context = new AgentExecutionContext(9L, 15L, null, 31L, context.runId(), context.traceId(),
                context.issuedAtEpochSecond(), context.expiresAtEpochSecond());
        User subject = new User();
        subject.setStatus(1);
        when(users.selectById(15L)).thenReturn(subject);
        when(access.require(9L, 15L, FamilyAccessService.Scope.USE_AI))
                .thenReturn(15L).thenThrow(BusinessException.forbidden("revoked"));
        authorize();
        failure(this::authorize, 403, "PATIENT_SCOPE_DENIED");
        verify(access, times(2)).require(9L, 15L, FamilyAccessService.Scope.USE_AI);
    }

    @Test
    void checksMemberOwnershipAndDeletion() {
        session.setMemberId(2L);
        context = new AgentExecutionContext(9L, 9L, 2L, 31L, context.runId(), context.traceId(),
                context.issuedAtEpochSecond(), context.expiresAtEpochSecond());
        FamilyMember member = new FamilyMember();
        member.setUserId(99L);
        when(members.selectById(2L)).thenReturn(member);
        failure(this::authorize, 403, "PATIENT_SCOPE_DENIED");
        member.setUserId(9L);
        member.setDeletedAt(LocalDateTime.now());
        failure(this::authorize, 403, "PATIENT_SCOPE_DENIED");
        member.setDeletedAt(null);
        assertThat(authorize().context().memberId()).isEqualTo(2L);
    }

    @Test
    void requiresSeparateNonEmptyCredentialsWhenEnabled() {
        properties.setServiceToken("");
        assertThatThrownBy(guard::validateConfiguration).isInstanceOf(IllegalStateException.class);
        properties.setServiceToken(agentProperties.getContextSecret());
        assertThatThrownBy(guard::validateConfiguration).isInstanceOf(IllegalStateException.class);
        properties.setServiceToken("test-service");
        agentProperties.setContextSecret("");
        assertThatThrownBy(guard::validateConfiguration).isInstanceOf(IllegalStateException.class);
        properties.setEnabled(false);
        guard.validateConfiguration();
    }

    private InternalAgentCall authorize() {
        return guard.authorize(properties.getServiceToken(), signer.sign(context), deadline());
    }

    private String deadline() { return Long.toString(System.currentTimeMillis() + 30_000); }

    private void failure(ThrowingCallable action, int status, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(InternalAgentException.class, error -> {
            assertThat(error.status()).isEqualTo(status);
            assertThat(error.code()).isEqualTo(code);
        });
    }
}
