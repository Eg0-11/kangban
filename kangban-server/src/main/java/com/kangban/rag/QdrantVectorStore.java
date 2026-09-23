package com.kangban.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kangban.agent.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Qdrant REST 适配器。只存储可重建的向量索引，不替代 MySQL 的业务事实和引用数据。
 */
@Slf4j
@Component
public class QdrantVectorStore implements VectorStore {

    private static final List<String> FILTER_INDEX_FIELDS = List.of(
            "scope", "ownerUserId", "subjectUserId", "memberId", "documentStatus", "embeddingModel");

    private final RagProperties properties;
    private final ObjectMapper objectMapper;
    private final QdrantTransport transport;

    @Autowired
    public QdrantVectorStore(RagProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, new HttpQdrantTransport(properties));
    }

    QdrantVectorStore(RagProperties properties, ObjectMapper objectMapper, QdrantTransport transport) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.transport = transport;
    }

    /** 创建 Collection 并建立权限过滤字段索引；重复执行是幂等的。 */
    public void ensureCollection() {
        Map<String, Object> vectors = new LinkedHashMap<>();
        vectors.put("size", properties.getEmbeddingDimensions());
        vectors.put("distance", "Cosine");
        Map<String, Object> body = Map.of("vectors", vectors);
        QdrantTransport.QdrantResponse response = exchange("PUT",
                "/collections/" + pathSegment(properties.getQdrantCollection()), body, false);
        if (response.statusCode() != 200 && response.statusCode() != 201 && response.statusCode() != 409) {
            throw unavailable("Qdrant Collection 创建失败，状态码=" + response.statusCode());
        }
        for (String field : FILTER_INDEX_FIELDS) {
            Map<String, Object> indexBody = new LinkedHashMap<>();
            indexBody.put("field_name", field);
            indexBody.put("field_schema", "keyword");
            QdrantTransport.QdrantResponse indexResponse = exchange("PUT",
                    "/collections/" + pathSegment(properties.getQdrantCollection()) + "/index", indexBody, false);
            if (indexResponse.statusCode() != 200 && indexResponse.statusCode() != 201
                    && indexResponse.statusCode() != 409) {
                throw unavailable("Qdrant Payload 索引创建失败，字段=" + field);
            }
        }
    }

    @Override
    public void ensureReady() {
        ensureCollection();
    }

    @Override
    public void upsert(VectorRecord record) {
        if (record == null || record.pointId() == null || record.pointId().isBlank()
                || record.vector() == null || record.vector().length == 0) {
            throw new IllegalArgumentException("Qdrant 向量记录不能为空");
        }
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("id", record.pointId());
        point.put("vector", record.vector());
        point.put("payload", record.payload() == null ? Map.of() : record.payload());
        QdrantTransport.QdrantResponse response = exchange("PUT",
                "/collections/" + pathSegment(properties.getQdrantCollection()) + "/points?wait=true",
                Map.of("points", List.of(point)), false);
        requireSuccess(response, "Qdrant 向量写入失败");
    }

    @Override
    public void delete(String pointId) {
        if (pointId == null || pointId.isBlank()) {
            return;
        }
        QdrantTransport.QdrantResponse response = exchange("POST",
                "/collections/" + pathSegment(properties.getQdrantCollection()) + "/points/delete?wait=true",
                Map.of("points", List.of(pointId)), false);
        requireSuccess(response, "Qdrant 向量删除失败");
    }

    @Override
    public void deleteByFilter(Map<String, Object> filters) {
        QdrantTransport.QdrantResponse response = exchange("POST",
                "/collections/" + pathSegment(properties.getQdrantCollection()) + "/points/delete?wait=true",
                Map.of("filter", exactFilter(filters)), false);
        requireSuccess(response, "Qdrant 条件删除失败");
    }

    @Override
    public void updatePayloadByFilter(Map<String, Object> filters, Map<String, Object> payload) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("filter", exactFilter(filters));
        body.put("payload", payload == null ? Map.of() : payload);
        QdrantTransport.QdrantResponse response = exchange("POST",
                "/collections/" + pathSegment(properties.getQdrantCollection()) + "/points/payload?wait=true",
                body, false);
        requireSuccess(response, "Qdrant Payload 更新失败");
    }

    @Override
    public List<VectorSearchHit> search(double[] queryVector, int limit, Map<String, Object> filters) {
        if (queryVector == null || queryVector.length == 0) {
            return List.of();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", queryVector);
        body.put("limit", Math.max(1, Math.min(limit, properties.getQdrantMaxSearchLimit())));
        body.put("with_payload", true);
        Map<String, Object> qdrantFilter = exactFilter(filters);
        if (!qdrantFilter.isEmpty()) {
            body.put("filter", qdrantFilter);
        }
        QdrantTransport.QdrantResponse response = exchange("POST",
                "/collections/" + pathSegment(properties.getQdrantCollection()) + "/points/query",
                body, false);
        requireSuccess(response, "Qdrant 向量检索失败");
        return parseHits(response.body());
    }

    @Override
    public boolean isAvailable() {
        try {
            QdrantTransport.QdrantResponse response = exchange("GET",
                    "/collections/" + pathSegment(properties.getQdrantCollection()), null, true);
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private List<VectorSearchHit> parseHits(String body) {
        try {
            JsonNode result = objectMapper.readTree(body).path("result");
            JsonNode points = result.isObject() ? result.path("points") : result;
            if (!points.isArray()) {
                throw unavailable("Qdrant 返回结果格式异常");
            }
            List<VectorSearchHit> hits = new ArrayList<>();
            for (JsonNode point : points) {
                String pointId = point.path("id").asText("");
                if (pointId.isBlank()) {
                    continue;
                }
                Map<String, Object> payload = objectMapper.convertValue(
                        point.path("payload"), objectMapper.getTypeFactory()
                                .constructMapType(LinkedHashMap.class, String.class, Object.class));
                hits.add(new VectorSearchHit(pointId, point.path("score").asDouble(0.0), payload));
            }
            return List.copyOf(hits);
        } catch (VectorStoreUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw unavailable("Qdrant 返回结果解析失败", e);
        }
    }

    private Map<String, Object> exactFilter(Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> must = new ArrayList<>();
        for (Map.Entry<String, Object> entry : filters.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                continue;
            }
            must.add(Map.of("key", entry.getKey(), "match", Map.of("value", entry.getValue())));
        }
        return must.isEmpty() ? Map.of() : Map.of("must", must);
    }

    private QdrantTransport.QdrantResponse exchange(String method, String path,
                                                     Map<String, Object> body, boolean healthCheck) {
        String json = null;
        if (body != null) {
            try {
                json = objectMapper.writeValueAsString(body);
            } catch (JsonProcessingException e) {
                throw unavailable("Qdrant 请求序列化失败", e);
            }
        }
        QdrantTransport.QdrantResponse response = transport.exchange(method, path, json);
        if (!healthCheck && (response == null || response.body() == null)) {
            throw unavailable("Qdrant 返回为空");
        }
        return response;
    }

    private void requireSuccess(QdrantTransport.QdrantResponse response, String message) {
        if (response == null || response.statusCode() < 200 || response.statusCode() >= 300) {
            int status = response == null ? 0 : response.statusCode();
            throw unavailable(message + "，状态码=" + status);
        }
    }

    private VectorStoreUnavailableException unavailable(String message) {
        return new VectorStoreUnavailableException(message);
    }

    private VectorStoreUnavailableException unavailable(String message, Throwable cause) {
        return new VectorStoreUnavailableException(message, cause);
    }

    private String pathSegment(String value) {
        return value == null || value.isBlank() ? "kangban_knowledge" : value.replace("/", "");
    }

    private static final class HttpQdrantTransport implements QdrantTransport {
        private final RagProperties properties;
        private final HttpClient httpClient;

        private HttpQdrantTransport(RagProperties properties) {
            this.properties = properties;
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(properties.getQdrantTimeoutMs()))
                    .build();
        }

        @Override
        public QdrantResponse exchange(String method, String path, String body) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl() + path))
                        .timeout(Duration.ofMillis(properties.getQdrantTimeoutMs()))
                        .header("Accept", "application/json");
                if (properties.getQdrantApiKey() != null && !properties.getQdrantApiKey().isBlank()) {
                    builder.header("api-key", properties.getQdrantApiKey());
                }
                if (body == null) {
                    builder.method(method, HttpRequest.BodyPublishers.noBody());
                } else {
                    builder.header("Content-Type", "application/json")
                            .method(method, HttpRequest.BodyPublishers.ofString(body));
                }
                HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                return new QdrantResponse(response.statusCode(), response.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new VectorStoreUnavailableException("Qdrant 请求被中断", e);
            } catch (Exception e) {
                log.warn("Qdrant request failed: method={}, path={}, errorType={}",
                        method, path, e.getClass().getSimpleName());
                throw new VectorStoreUnavailableException("Qdrant 服务暂时不可用", e);
            }
        }

        private String baseUrl() {
            String value = properties.getQdrantUrl();
            if (value == null || value.isBlank()) {
                return "http://127.0.0.1:6333";
            }
            return value.replaceAll("/$", "");
        }
    }
}
