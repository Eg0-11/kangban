package com.kangban.agent;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Also catches stale target/classes files: the migration must be built with clean. */
class PythonMigrationBoundaryTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "com.kangban.agent.AgentOrchestrator", "com.kangban.agent.AgentToolPlanner",
            "com.kangban.agent.ConversationMemoryPolicy", "com.kangban.agent.MedicalSafetyPolicy",
            "com.kangban.client.AiConsultationClient", "com.kangban.client.MockAiConsultationClient",
            "com.kangban.client.QwenAiConsultationClient", "com.kangban.client.DeepSeekAiConsultationClient",
            "com.kangban.client.AiToolCall", "com.kangban.client.AiToolMessage", "com.kangban.client.AiToolResponse"
    })
    void oldOrchestrationIsAbsentFromRuntimeClasspath(String name) {
        assertThatThrownBy(() -> Class.forName(name)).isInstanceOf(ClassNotFoundException.class);
    }
}
