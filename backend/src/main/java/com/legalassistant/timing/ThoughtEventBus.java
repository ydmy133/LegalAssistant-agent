package com.legalassistant.timing;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 将会话内 Thought / 工具步骤推到流式 SSE（供 LegalTools 等非 ChatService 线程使用）。
 */
public final class ThoughtEventBus {

    private static final ConcurrentHashMap<String, Consumer<Map<String, Object>>> LISTENERS =
            new ConcurrentHashMap<>();
    private static final ThreadLocal<String> CURRENT_SESSION = new ThreadLocal<>();

    private ThoughtEventBus() {
    }

    public static void bind(String sessionId, Consumer<Map<String, Object>> listener) {
        if (sessionId == null || sessionId.isBlank() || listener == null) {
            return;
        }
        LISTENERS.put(sessionId, listener);
        CURRENT_SESSION.set(sessionId);
    }

    public static void bindSession(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            CURRENT_SESSION.set(sessionId);
        }
    }

    public static void emit(String sessionId, Map<String, Object> step) {
        if (step == null) {
            return;
        }
        String sid = sessionId != null && !sessionId.isBlank() ? sessionId : CURRENT_SESSION.get();
        if (sid == null) {
            return;
        }
        Consumer<Map<String, Object>> listener = LISTENERS.get(sid);
        if (listener != null) {
            listener.accept(step);
        }
    }

    public static void emit(Map<String, Object> step) {
        emit(CURRENT_SESSION.get(), step);
    }

    public static void unbind(String sessionId) {
        CURRENT_SESSION.remove();
        if (sessionId != null) {
            LISTENERS.remove(sessionId);
        }
    }
}
