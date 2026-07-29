package com.legalassistant.timing;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 sessionId 绑定当前进行中的对话耗时上下文（支持工具线程查找）。
 */
public final class ChatTimingRegistry {

    private static final ConcurrentHashMap<String, ChatTiming> ACTIVE = new ConcurrentHashMap<>();
    private static final ThreadLocal<ChatTiming> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<ChatTiming.Stage> CURRENT_STAGE = new ThreadLocal<>();

    private ChatTimingRegistry() {
    }

    public static ChatTiming begin(String sessionId) {
        ChatTiming timing = new ChatTiming(sessionId);
        ACTIVE.put(sessionId, timing);
        CURRENT.set(timing);
        return timing;
    }

    public static ChatTiming get(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        return ACTIVE.get(sessionId);
    }

    public static ChatTiming current() {
        return CURRENT.get();
    }

    public static void bind(ChatTiming timing) {
        if (timing != null) {
            CURRENT.set(timing);
        }
    }

    public static void bind(String sessionId) {
        ChatTiming timing = get(sessionId);
        if (timing != null) {
            CURRENT.set(timing);
        }
    }

    public static ChatTiming.Stage currentStage() {
        return CURRENT_STAGE.get();
    }

    public static void setCurrentStage(ChatTiming.Stage stage) {
        if (stage == null) {
            CURRENT_STAGE.remove();
        } else {
            CURRENT_STAGE.set(stage);
        }
    }

    public static ChatTiming end(String sessionId) {
        CURRENT.remove();
        CURRENT_STAGE.remove();
        if (sessionId == null) {
            return null;
        }
        return ACTIVE.remove(sessionId);
    }

    public static void clearThread() {
        CURRENT.remove();
        CURRENT_STAGE.remove();
    }
}
