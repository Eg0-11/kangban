package com.kangban.agent;

import com.kangban.client.AiClientException;

/** Links a servlet lifecycle to one HTTP call, including cancellation before the call starts. */
public final class AgentRunCancellation {
    private boolean cancelled;
    private Runnable onCancel;

    public synchronized void attach(Runnable action) {
        if (cancelled) {
            action.run();
            checkActive();
        }
        onCancel = action;
    }

    public synchronized void cancel() {
        cancelled = true;
        if (onCancel != null) {
            onCancel.run();
            onCancel = null;
        }
    }

    public synchronized void detach() {
        onCancel = null;
    }

    public synchronized void checkActive() {
        if (cancelled) throw new AiClientException("本次请求已结束。");
    }
}
