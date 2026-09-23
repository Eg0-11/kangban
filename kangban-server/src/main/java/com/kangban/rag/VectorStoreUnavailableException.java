package com.kangban.rag;

/** 向量索引服务不可用或返回了无效响应。 */
public class VectorStoreUnavailableException extends RuntimeException {

    public VectorStoreUnavailableException(String message) {
        super(message);
    }

    public VectorStoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
