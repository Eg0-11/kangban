package com.kangban.rag;

@FunctionalInterface
interface QdrantTransport {

    QdrantResponse exchange(String method, String path, String body);

    record QdrantResponse(int statusCode, String body) {
    }
}
