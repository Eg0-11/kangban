package com.kangban.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kangban.agent.AgentExecutionContext;
import com.kangban.agent.ActionProposal;
import com.kangban.common.Result;
import com.kangban.entity.AgentActionProposal;
import com.kangban.entity.ChatSession;
import com.kangban.mapper.AgentActionProposalMapper;
import com.kangban.mapper.ChatSessionMapper;
import com.kangban.mapper.FamilyMemberMapper;
import com.kangban.mapper.UserMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class ActionProposalServiceTest {

    @Test
    void persistsProposalAndExecutesItOnlyOnceAfterConfirmation() {
        AgentActionProposalMapper proposalMapper = mock(AgentActionProposalMapper.class);
        ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
        FamilyMemberMapper memberMapper = mock(FamilyMemberMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        FamilyAccessService accessService = mock(FamilyAccessService.class);
        HealthService healthService = mock(HealthService.class);
        MedicationService medicationService = mock(MedicationService.class);
        AuditService auditService = mock(AuditService.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ActionProposalService service = new ActionProposalService(
                proposalMapper, sessionMapper, memberMapper, userMapper, objectMapper,
                accessService, healthService, medicationService, auditService);

        doAnswer(invocation -> {
            AgentActionProposal proposal = invocation.getArgument(0);
            proposal.setId(101L);
            return 1;
        }).when(proposalMapper).insert(any(AgentActionProposal.class));
        when(userMapper.selectById(9L)).thenReturn(null);

        AgentExecutionContext context = context();
        ActionProposalService.Decision decision = service.propose(
                context, "帮我记录今天早上血压 128/82 mmHg");
        ActionProposal action = decision.action();

        assertThat(action).isNotNull();
        assertThat(action.status()).isEqualTo(ActionProposal.Status.PENDING_CONFIRMATION);
        assertThat(action.payloadHash()).hasSize(64);
        AgentActionProposal persisted = captureProposal(proposalMapper);
        assertThat(persisted.getStatus()).isEqualTo("PROPOSED");
        assertThat(persisted.getPayloadHash()).isEqualTo(action.payloadHash());
        verifyNoInteractions(healthService, medicationService);

        ChatSession session = new ChatSession();
        session.setId(31L);
        session.setUserId(9L);
        session.setSubjectUserId(null);
        session.setMemberId(null);
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(healthService.addRecord(eq(9L), any())).thenReturn(Result.success(Map.of("id", 7001L)));

        AtomicInteger updates = new AtomicInteger();
        doAnswer(invocation -> {
            if (updates.getAndIncrement() == 0) {
                persisted.setStatus("CONFIRMED");
            } else {
                persisted.setStatus("EXECUTED");
                persisted.setResultReference("7001");
            }
            return 1;
        }).when(proposalMapper).update(isNull(), any(UpdateWrapper.class));
        when(proposalMapper.selectOne(any())).thenReturn(persisted);

        ActionProposalService.ActionProposalView executed = service.confirm(
                9L, action.id(), action.payloadHash());
        assertThat(executed.status()).isEqualTo("EXECUTED");
        assertThat(executed.resultReference()).isEqualTo("7001");
        verify(healthService, times(1)).addRecord(eq(9L), any());

        ActionProposalService.ActionProposalView repeated = service.confirm(
                9L, action.id(), action.payloadHash());
        assertThat(repeated.status()).isEqualTo("EXECUTED");
        verify(healthService, times(1)).addRecord(eq(9L), any());
    }

    @Test
    void confirmsMedicationProposalThroughMedicationService() {
        AgentActionProposalMapper proposalMapper = mock(AgentActionProposalMapper.class);
        ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
        FamilyMemberMapper memberMapper = mock(FamilyMemberMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        FamilyAccessService accessService = mock(FamilyAccessService.class);
        HealthService healthService = mock(HealthService.class);
        MedicationService medicationService = mock(MedicationService.class);
        AuditService auditService = mock(AuditService.class);
        ActionProposalService service = new ActionProposalService(
                proposalMapper, sessionMapper, memberMapper, userMapper, new ObjectMapper().findAndRegisterModules(),
                accessService, healthService, medicationService, auditService);

        doAnswer(invocation -> {
            AgentActionProposal proposal = invocation.getArgument(0);
            proposal.setId(102L);
            return 1;
        }).when(proposalMapper).insert(any(AgentActionProposal.class));
        when(userMapper.selectById(9L)).thenReturn(null);

        ActionProposal action = service.propose(
                context(), "帮我记录阿莫西林，每次一粒，每天三次，晚上8点").action();
        AgentActionProposal persisted = captureProposal(proposalMapper);
        ChatSession session = new ChatSession();
        session.setId(31L);
        session.setUserId(9L);
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(medicationService.add(eq(9L), any())).thenReturn(Result.success(Map.of("id", 8001L)));

        AtomicInteger updates = new AtomicInteger();
        doAnswer(invocation -> {
            if (updates.getAndIncrement() == 0) {
                persisted.setStatus("CONFIRMED");
            } else {
                persisted.setStatus("EXECUTED");
                persisted.setResultReference("8001");
            }
            return 1;
        }).when(proposalMapper).update(isNull(), any(UpdateWrapper.class));
        when(proposalMapper.selectOne(any())).thenReturn(persisted);

        ActionProposalService.ActionProposalView executed = service.confirm(
                9L, action.id(), action.payloadHash());
        assertThat(executed.status()).isEqualTo("EXECUTED");
        assertThat(executed.resultReference()).isEqualTo("8001");
        verify(medicationService).add(eq(9L), any());
        verifyNoInteractions(healthService);
    }

    private AgentActionProposal captureProposal(AgentActionProposalMapper mapper) {
        var captor = org.mockito.ArgumentCaptor.forClass(AgentActionProposal.class);
        verify(mapper).insert(captor.capture());
        return captor.getValue();
    }

    private AgentExecutionContext context() {
        long now = System.currentTimeMillis() / 1000;
        return new AgentExecutionContext(9L, 9L, null, 31L,
                "run-action-service", "trace-action-service", now - 1, now + 60);
    }
}
