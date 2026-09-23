package com.kangban.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentActionPlannerTest {

    private final AgentActionPlanner planner = new AgentActionPlanner();

    @Test
    void createsHealthProposalOnlyAfterAllKeyFieldsAreExplicit() {
        AgentActionPlanner.Decision decision = planner.decide(
                "帮我记录今天早上血压 128/82 mmHg",
                context(null), "本人");

        assertThat(decision.hasProposal()).isTrue();
        assertThat(decision.actionType()).isEqualTo("HEALTH_RECORD");
        assertThat(decision.payload()).containsEntry("value", "128/82")
                .containsEntry("unit", "mmHg")
                .containsEntry("recordedTime", "08:00");
    }

    @Test
    void asksForMissingMedicationFieldsInsteadOfInferringThem() {
        AgentActionPlanner.Decision decision = planner.decide(
                "帮我记录用药",
                context(null), "本人");

        assertThat(decision.handled()).isTrue();
        assertThat(decision.hasProposal()).isFalse();
        assertThat(decision.content()).contains("单次剂量", "服用频率", "提醒时间");
    }

    @Test
    void blocksMedicationRecommendationAndDoseChanges() {
        AgentActionPlanner.Decision decision = planner.decide(
                "帮我增加阿莫西林剂量",
                context(null), "本人");

        assertThat(decision.handled()).isTrue();
        assertThat(decision.hasProposal()).isFalse();
        assertThat(decision.content()).contains("不能替您推荐药物、调整剂量、停药或换药");
    }

    @Test
    void createsMedicationProposalWithoutInventingStartOrEndDates() {
        AgentActionPlanner.Decision decision = planner.decide(
                "帮我记录阿莫西林，每次一粒，每天三次，晚上8点",
                context(2L), "爸爸");

        assertThat(decision.hasProposal()).isTrue();
        assertThat(decision.actionType()).isEqualTo("MEDICATION");
        assertThat(decision.payload()).containsEntry("name", "阿莫西林")
                .containsEntry("dosage", "1")
                .containsEntry("unit", "粒")
                .containsEntry("frequency", "每天三次")
                .containsEntry("times", "20:00")
                .containsEntry("startDate", null)
                .containsEntry("endDate", null);
    }

    private AgentExecutionContext context(Long memberId) {
        long now = System.currentTimeMillis() / 1000;
        return new AgentExecutionContext(9L, 9L, memberId, 31L,
                "run-action", "trace-action", now - 1, now + 60);
    }
}
