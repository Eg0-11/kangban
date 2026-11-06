package com.kangban.agent.internal;

/** 只携带经过明确选择的安全错误文案，不承载请求正文或底层异常消息。 */
public final class InternalAgentException extends RuntimeException {
    private final int status;
    private final String code;
    private final boolean retryable;

    public InternalAgentException(int status, String code, String message, boolean retryable) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
    }

    public int status() { return status; }
    public String code() { return code; }
    public boolean retryable() { return retryable; }

    public static InternalAgentException invalidRequest() {
        return new InternalAgentException(400, "INVALID_REQUEST", "内部 Agent 请求格式无效。", false);
    }
}
