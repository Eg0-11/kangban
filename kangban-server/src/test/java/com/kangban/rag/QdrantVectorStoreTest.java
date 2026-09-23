package com.kangban.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kangban.agent.RagProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QdrantVectorStoreTest {

    @Test
    void createsCollectionIndexesAndSupportsUpsertDeleteAndFilteredQuery() {
        RagProperties properties = new RagProperties();
        properties.setEmbeddingDimensions(3);
        properties.setQdrantCollection("test_collection");
        List<String> requests = new ArrayList<>();
        QdrantTransport transport = (method, path, body) -> {
            requests.add(method + " " + path + " " + (body == null ? "" : body));
            if (path.endsWith("/points/query")) {
                return new QdrantTransport.QdrantResponse(200,
                        "{\"result\":{\"points\":[{\"id\":\"point-1\",\"score\":0.91,"
                                + "\"payload\":{\"scope\":\"PUBLIC\",\"chunkId\":11}}]}}");
            }
            return new QdrantTransport.QdrantResponse(200, "{}");
        };
        QdrantVectorStore store = new QdrantVectorStore(properties, new ObjectMapper(), transport);

        store.ensureCollection();
        store.upsert(new VectorStore.VectorRecord("point-1", new double[]{0.1, 0.2, 0.3},
                Map.of("scope", "PUBLIC", "chunkId", 11L)));
        List<VectorStore.VectorSearchHit> hits = store.search(new double[]{0.1, 0.2, 0.3}, 5,
                Map.of("scope", "PUBLIC", "subjectUserId", 9L));
        store.delete("point-1");

        assertThat(requests).hasSize(10);
        assertThat(requests.get(0)).contains("PUT /collections/test_collection");
        assertThat(requests).anyMatch(request -> request.contains("/points?wait=true")
                && request.contains("point-1"));
        assertThat(requests).anyMatch(request -> request.contains("/points/query")
                && request.contains("subjectUserId") && request.contains("PUBLIC"));
        assertThat(hits).singleElement().satisfies(hit -> {
            assertThat(hit.pointId()).isEqualTo("point-1");
            assertThat(hit.score()).isEqualTo(0.91);
            assertThat(hit.payload()).containsEntry("scope", "PUBLIC");
        });
        assertThat(requests).anyMatch(request -> request.contains("/points/delete?wait=true"));
    }

    @Test
    void reportsHealthWithoutLeakingConnectionErrors() {
        RagProperties properties = new RagProperties();
        QdrantVectorStore healthy = new QdrantVectorStore(properties, new ObjectMapper(),
                (method, path, body) -> new QdrantTransport.QdrantResponse(200, "{}"));
        QdrantVectorStore unavailable = new QdrantVectorStore(properties, new ObjectMapper(),
                (method, path, body) -> { throw new VectorStoreUnavailableException("unavailable"); });

        assertThat(healthy.isAvailable()).isTrue();
        assertThat(unavailable.isAvailable()).isFalse();
    }
}
