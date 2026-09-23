package com.kangban.rag;

import com.kangban.agent.AgentExecutionContext;
import com.kangban.agent.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/** 私有知识检索路由；权限校验在 MySQL 和 Qdrant 两条链路中都保留。 */
@Slf4j
@Primary
@Service
public class RoutingPrivateKnowledgeSearchService implements PrivateKnowledgeSearchService {

    private final JdbcPrivateKnowledgeSearchService jdbcService;
    private final QdrantKnowledgeSearchService qdrantService;
    private final RagProperties properties;

    public RoutingPrivateKnowledgeSearchService(JdbcPrivateKnowledgeSearchService jdbcService,
                                                QdrantKnowledgeSearchService qdrantService,
                                                RagProperties properties) {
        this.jdbcService = jdbcService;
        this.qdrantService = qdrantService;
        this.properties = properties;
    }

    @Override
    public RagSearchResult search(String query, AgentExecutionContext context) {
        return switch (properties.vectorStoreMode()) {
            case MYSQL -> jdbcService.search(query, context);
            case QDRANT -> qdrantService.search(query, context);
            case DUAL -> dualSearch(query, context);
        };
    }

    private RagSearchResult dualSearch(String query, AgentExecutionContext context) {
        try {
            qdrantService.search(query, context);
        } catch (VectorStoreUnavailableException e) {
            log.warn("Private Qdrant dual-read unavailable: errorType={}", e.getClass().getSimpleName());
        }
        return jdbcService.search(query, context);
    }
}
