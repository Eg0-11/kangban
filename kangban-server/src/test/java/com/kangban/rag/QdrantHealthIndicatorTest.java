package com.kangban.rag;

import com.kangban.agent.RagProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QdrantHealthIndicatorTest {

    @Test
    void mysqlModeDoesNotRequireQdrant() {
        RagProperties properties = new RagProperties();
        VectorStore vectorStore = mock(VectorStore.class);

        assertThat(new QdrantHealthIndicator(properties, vectorStore).health().getStatus().getCode())
                .isEqualTo("UP");
    }

    @Test
    void qdrantModeIsDownWhenIndexServiceIsUnavailable() {
        RagProperties properties = new RagProperties();
        properties.setVectorStore("qdrant");
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.isAvailable()).thenReturn(false);

        assertThat(new QdrantHealthIndicator(properties, vectorStore).health().getStatus().getCode())
                .isEqualTo("DOWN");
    }

    @Test
    void dualModeRemainsUpButExposesAvailability() {
        RagProperties properties = new RagProperties();
        properties.setVectorStore("dual");
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.isAvailable()).thenReturn(false);

        assertThat(new QdrantHealthIndicator(properties, vectorStore).health().getStatus().getCode())
                .isEqualTo("UP");
    }
}
