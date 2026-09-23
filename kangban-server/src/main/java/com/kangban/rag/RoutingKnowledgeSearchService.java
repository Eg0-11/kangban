package com.kangban.rag;

import com.kangban.agent.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/** 根据运行模式选择 MySQL、双读对比或 Qdrant 检索。 */
@Slf4j
@Primary
@Service
public class RoutingKnowledgeSearchService implements KnowledgeSearchService {

    private final JdbcKnowledgeSearchService jdbcService;
    private final QdrantKnowledgeSearchService qdrantService;
    private final RagProperties properties;

    public RoutingKnowledgeSearchService(JdbcKnowledgeSearchService jdbcService,
                                         QdrantKnowledgeSearchService qdrantService,
                                         RagProperties properties) {
        this.jdbcService = jdbcService;
        this.qdrantService = qdrantService;
        this.properties = properties;
    }

    @Override
    public RagSearchResult search(String query) {
        return switch (properties.vectorStoreMode()) {
            case MYSQL -> jdbcService.search(query);
            case QDRANT -> qdrantService.search(query);
            case DUAL -> dualSearch(query);
        };
    }

    private RagSearchResult dualSearch(String query) {
        try {
            qdrantService.search(query);
        } catch (VectorStoreUnavailableException e) {
            log.warn("Qdrant dual-read unavailable: errorType={}", e.getClass().getSimpleName());
        }
        return jdbcService.search(query);
    }
}
