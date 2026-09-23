package com.kangban.rag;

import com.kangban.agent.AgentExecutionContext;
import com.kangban.agent.AgentMetrics;
import com.kangban.agent.Citation;
import com.kangban.agent.RagProperties;
import com.kangban.common.BusinessException;
import com.kangban.service.FamilyAccessService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Qdrant 召回、MySQL 正文回查和现有混合重排的组合实现。 */
@Slf4j
@Service
public class QdrantKnowledgeSearchService implements KnowledgeSearchService, PrivateKnowledgeSearchService {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final RagProperties properties;
    private final FamilyAccessService familyAccessService;
    private final AgentMetrics metrics;

    public QdrantKnowledgeSearchService(JdbcTemplate jdbcTemplate,
                                        EmbeddingClient embeddingClient,
                                        VectorStore vectorStore,
                                        RagProperties properties,
                                        FamilyAccessService familyAccessService,
                                        AgentMetrics metrics) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.familyAccessService = familyAccessService;
        this.metrics = metrics;
    }

    @Override
    public RagSearchResult search(String query) {
        if (query == null || query.isBlank()) {
            return RagSearchResult.empty();
        }
        long startedAt = System.currentTimeMillis();
        List<VectorStore.VectorSearchHit> vectorHits = vectorStore.search(
                embeddingClient.embed(query), properties.getQdrantMaxSearchLimit(),
                Map.of("scope", "PUBLIC", "documentStatus", "PUBLISHED",
                        "embeddingModel", properties.getEmbeddingModel()));
        List<KnowledgeSearchHit> selected = rankPublic(query, vectorHits);
        metrics.recordRagSearch("public", System.currentTimeMillis() - startedAt, selected.size());
        return new RagSearchResult(buildContext(selected), selected);
    }

    @Override
    public RagSearchResult search(String query, AgentExecutionContext context) {
        if (query == null || query.isBlank() || context == null || !hasRecordPermission(context)) {
            return RagSearchResult.empty();
        }
        long startedAt = System.currentTimeMillis();
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("scope", "PRIVATE");
        filters.put("ownerUserId", context.subjectUserId());
        filters.put("subjectUserId", context.subjectUserId());
        filters.put("documentStatus", "READY");
        filters.put("embeddingModel", properties.getEmbeddingModel());
        if (context.memberId() != null) {
            filters.put("memberId", context.memberId());
        }
        List<VectorStore.VectorSearchHit> vectorHits = vectorStore.search(
                embeddingClient.embed(query), properties.getQdrantMaxSearchLimit(), filters);
        List<KnowledgeSearchHit> selected = rankPrivate(query, vectorHits, context);
        metrics.recordRagSearch("private", System.currentTimeMillis() - startedAt, selected.size());
        return new RagSearchResult(buildContext(selected), selected);
    }

    private List<KnowledgeSearchHit> rankPublic(String query,
                                                List<VectorStore.VectorSearchHit> vectorHits) {
        Map<String, Double> vectorScores = scores(vectorHits);
        List<Map<String, Object>> rows = loadPublicRows(vectorHits);
        List<HybridSearchRanker.Candidate> candidates = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String key = key(row.get("document_id"), row.get("chunk_index"));
            candidates.add(new HybridSearchRanker.Candidate(
                    String.valueOf(row.get("content")),
                    KeywordRelevance.score(query, String.valueOf(row.get("title")) + " " + row.get("content")),
                    vectorScores.getOrDefault(key, 0.0),
                    publicCitation(row)));
        }
        return HybridSearchRanker.rank(candidates, properties);
    }

    private List<KnowledgeSearchHit> rankPrivate(String query,
                                                 List<VectorStore.VectorSearchHit> vectorHits,
                                                 AgentExecutionContext context) {
        Map<String, Double> vectorScores = scores(vectorHits);
        List<Map<String, Object>> rows = loadPrivateRows(vectorHits, context);
        List<HybridSearchRanker.Candidate> candidates = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String key = key(row.get("document_id"), row.get("chunk_index"));
            candidates.add(new HybridSearchRanker.Candidate(
                    String.valueOf(row.get("content")),
                    KeywordRelevance.score(query, String.valueOf(row.get("title")) + " " + row.get("content")),
                    vectorScores.getOrDefault(key, 0.0),
                    privateCitation(row)));
        }
        return HybridSearchRanker.rank(candidates, properties);
    }

    private List<Map<String, Object>> loadPublicRows(List<VectorStore.VectorSearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT c.document_id, c.chunk_index, c.content, c.page_number, c.section, "
                        + "d.title, d.version, d.source, d.updated_at "
                        + "FROM knowledge_chunks c JOIN knowledge_documents d ON d.id=c.document_id "
                        + "WHERE d.status='PUBLISHED' AND d.deleted_at IS NULL AND c.embedding_model=? AND (");
        List<Object> args = new ArrayList<>(List.of(properties.getEmbeddingModel()));
        appendPointConditions(sql, args, hits);
        sql.append(")");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    private List<Map<String, Object>> loadPrivateRows(List<VectorStore.VectorSearchHit> hits,
                                                       AgentExecutionContext context) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT c.document_id, c.chunk_index, c.content, c.page_number, c.section, "
                        + "d.title, d.version, d.source, d.updated_at "
                        + "FROM family_knowledge_chunks c JOIN family_knowledge_documents d ON d.id=c.document_id "
                        + "JOIN medical_records m ON m.id=d.medical_record_id "
                        + "WHERE d.status='READY' AND d.deleted_at IS NULL AND d.revoked_at IS NULL "
                        + "AND m.deleted_at IS NULL AND c.embedding_model=? "
                        + "AND d.owner_user_id=? AND d.subject_user_id=?");
        List<Object> args = new ArrayList<>(List.of(
                properties.getEmbeddingModel(), context.subjectUserId(), context.subjectUserId()));
        if (context.memberId() == null) {
            sql.append(" AND d.member_id IS NULL");
        } else {
            sql.append(" AND d.member_id=?");
            args.add(context.memberId());
        }
        sql.append(" AND (");
        appendPointConditions(sql, args, hits);
        sql.append(")");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    private void appendPointConditions(StringBuilder sql, List<Object> args,
                                       List<VectorStore.VectorSearchHit> hits) {
        List<String> conditions = new ArrayList<>();
        for (VectorStore.VectorSearchHit hit : hits) {
            Long documentId = number(hit.payload().get("documentId"));
            Integer chunkIndex = integer(hit.payload().get("chunkIndex"));
            if (documentId != null && chunkIndex != null) {
                conditions.add("(c.document_id=? AND c.chunk_index=?)");
                args.add(documentId);
                args.add(chunkIndex);
            }
        }
        if (conditions.isEmpty()) {
            sql.append("1=0");
        } else {
            sql.append(String.join(" OR ", conditions));
        }
    }

    private Map<String, Double> scores(List<VectorStore.VectorSearchHit> hits) {
        Map<String, Double> scores = new HashMap<>();
        if (hits == null) {
            return scores;
        }
        for (VectorStore.VectorSearchHit hit : hits) {
            Long documentId = number(hit.payload().get("documentId"));
            Integer chunkIndex = integer(hit.payload().get("chunkIndex"));
            if (documentId != null && chunkIndex != null) {
                scores.put(key(documentId, chunkIndex), hit.score());
            }
        }
        return scores;
    }

    private Citation publicCitation(Map<String, Object> row) {
        return new Citation(String.valueOf(row.get("document_id")), String.valueOf(row.get("title")),
                String.valueOf(row.get("version")), integer(row.get("page_number")),
                text(row.get("section")), text(row.get("source")), String.valueOf(row.get("updated_at")),
                "PUBLIC");
    }

    private Citation privateCitation(Map<String, Object> row) {
        return new Citation("private:" + row.get("document_id"), String.valueOf(row.get("title")),
                String.valueOf(row.get("version")), integer(row.get("page_number")),
                text(row.get("section")), text(row.get("source")), String.valueOf(row.get("updated_at")),
                "PRIVATE");
    }

    private String buildContext(List<KnowledgeSearchHit> hits) {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            Citation citation = hits.get(i).citation();
            context.append("[资料").append(i + 1).append("] ").append(citation.title());
            if ("PRIVATE".equals(citation.scope())) {
                context.append("，家庭私有病历");
            }
            if (citation.pageNumber() != null) {
                context.append("，第").append(citation.pageNumber()).append("页");
            }
            if (citation.section() != null && !citation.section().isBlank()) {
                context.append("，章节：").append(citation.section());
            }
            if (citation.source() != null && !citation.source().isBlank()) {
                context.append("，来源：").append(citation.source());
            }
            context.append("\n").append(hits.get(i).content()).append("\n\n");
        }
        return context.toString().trim();
    }

    private boolean hasRecordPermission(AgentExecutionContext context) {
        if (context.actorUserId().equals(context.subjectUserId())) {
            return true;
        }
        try {
            familyAccessService.require(context.actorUserId(), context.subjectUserId(),
                    FamilyAccessService.Scope.VIEW_RECORDS);
            return true;
        } catch (BusinessException e) {
            log.info("Private Qdrant scope denied: actorUserId={}, subjectUserId={}",
                    context.actorUserId(), context.subjectUserId());
            return false;
        }
    }

    private String key(Object documentId, Object chunkIndex) {
        return String.valueOf(documentId) + ":" + String.valueOf(chunkIndex);
    }

    private Long number(Object value) {
        return value instanceof Number number ? number.longValue()
                : value == null ? null : parseLong(value.toString());
    }

    private Long parseLong(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Integer integer(Object value) {
        return value instanceof Number number ? number.intValue()
                : value == null ? null : parseInteger(value.toString());
    }

    private Integer parseInteger(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
