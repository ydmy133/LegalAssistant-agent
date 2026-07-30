package com.legalassistant.timing;

/**
 * 当前线程正在执行的工具 callId（供 Thought running→done 对齐）。
 */
public final class ToolCallContext {

    private static final ThreadLocal<String> CALL_ID = new ThreadLocal<>();

    private ToolCallContext() {
    }

    public static void setCallId(String callId) {
        if (callId == null || callId.isBlank()) {
            CALL_ID.remove();
        } else {
            CALL_ID.set(callId);
        }
    }

    public static String getCallId() {
        return CALL_ID.get();
    }

    public static void clear() {
        CALL_ID.remove();
    }
}
