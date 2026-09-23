package com.kangban.rag;

import com.kangban.agent.AgentExecutionContext;
import com.kangban.agent.RagProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RoutingKnowledgeSearchServiceTest {

    @Test
    void mysqlModeKeepsExistingJdbcSearchAsTheDefault() {
        JdbcKnowledgeSearchService jdbc = mock(JdbcKnowledgeSearchService.class);
        QdrantKnowledgeSearchService qdrant = mock(QdrantKnowledgeSearchService.class);
        RagProperties properties = new RagProperties();
        properties.setVectorStore("mysql-jdbc");
        RagSearchResult expected = new RagSearchResult("mysql", java.util.List.of());
        when(jdbc.search("问题")).thenReturn(expected);

        RagSearchResult actual = new RoutingKnowledgeSearchService(jdbc, qdrant, properties).search("问题");

        assertThat(actual).isSameAs(expected);
        verify(jdbc).search("问题");
        verifyNoInteractions(qdrant);
    }

    @Test
    void qdrantModeRoutesPublicAndPrivateSearchToQdrant() {
        JdbcKnowledgeSearchService jdbc = mock(JdbcKnowledgeSearchService.class);
        QdrantKnowledgeSearchService qdrant = mock(QdrantKnowledgeSearchService.class);
        RagProperties properties = new RagProperties();
        properties.setVectorStore("qdrant");
        RagSearchResult expectedPublic = new RagSearchResult("qdrant", java.util.List.of());
        when(qdrant.search("问题")).thenReturn(expectedPublic);
        AgentExecutionContext context = context();
        RagSearchResult expectedPrivate = new RagSearchResult("private", java.util.List.of());
        when(qdrant.search("病历", context)).thenReturn(expectedPrivate);

        assertThat(new RoutingKnowledgeSearchService(jdbc, qdrant, properties).search("问题"))
                .isSameAs(expectedPublic);
        assertThat(new RoutingPrivateKnowledgeSearchService(jdbcPrivate(), qdrant, properties)
                .search("病历", context)).isSameAs(expectedPrivate);
        verify(qdrant).search("问题");
        verify(qdrant).search("病历", context);
        verifyNoInteractions(jdbc);
    }

    @Test
    void dualModeFallsBackToMysqlWhenQdrantIsUnavailable() {
        JdbcKnowledgeSearchService jdbc = mock(JdbcKnowledgeSearchService.class);
        QdrantKnowledgeSearchService qdrant = mock(QdrantKnowledgeSearchService.class);
        RagProperties properties = new RagProperties();
        properties.setVectorStore("dual");
        RagSearchResult expected = new RagSearchResult("fallback", java.util.List.of());
        when(qdrant.search("问题")).thenThrow(new VectorStoreUnavailableException("offline"));
        when(jdbc.search("问题")).thenReturn(expected);

        assertThat(new RoutingKnowledgeSearchService(jdbc, qdrant, properties).search("问题"))
                .isSameAs(expected);
        verify(qdrant).search("问题");
        verify(jdbc).search("问题");
    }

    private JdbcPrivateKnowledgeSearchService jdbcPrivate() {
        return mock(JdbcPrivateKnowledgeSearchService.class);
    }

    private AgentExecutionContext context() {
        long now = System.currentTimeMillis() / 1000;
        return new AgentExecutionContext(9L, 15L, 2L, 31L,
                "run-route", "trace-route", now - 1, now + 60);
    }
}
