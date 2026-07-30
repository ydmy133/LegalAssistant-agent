package com.legalassistant.agent;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 会话级工具防抖（类 grok IdenticalToolCallRun）：
 * 按工具名计数 + (tool,query) 指纹去重；联网空结果后可换意图再试 1 次，再空则耗尽。
 */
@Component
public class SessionToolGuard {

    private static final int DEFAULT_MAX = 1;
    private static final int SEARCH_WEB_MAX_DEFAULT = 1;
    private static final int SEARCH_WEB_MAX_AFTER_EMPTY = 2;

    private final Map<String, TurnState> turns = new ConcurrentHashMap<>();

    public void beginTurn(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        turns.put(sessionId, new TurnState());
    }

    public void endTurn(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        turns.remove(sessionId);
    }

    public String check(String sessionId, String toolName) {
        return check(sessionId, toolName, null);
    }

    public String check(String sessionId, String toolName, String query) {
        if (sessionId == null || sessionId.isBlank() || toolName == null) {
            return null;
        }
        TurnState state = turns.computeIfAbsent(sessionId, k -> new TurnState());

        if ("searchWeb".equals(toolName) && state.webExhausted.get()) {
            return "联网已无命中或未启用，请基于已有结果作答，勿再换词联网。";
        }
        if ("searchWeb".equals(toolName) && state.webHadHits.get()) {
            return "本轮已有联网检索结果，请基于已有结果作答，勿再联网。";
        }

        String fingerprint = fingerprint(toolName, query);
        if (fingerprint != null && state.fingerprints.contains(fingerprint)) {
            return "本轮已检索过相同意图（" + toolName + "），请基于已有结果作答，勿换词重搜。";
        }

        int max = maxFor(toolName, state);
        int count = state.counts.getOrDefault(toolName, new AtomicInteger(0)).get();
        if (count >= max) {
            return "本轮已调用过 " + toolName + "（上限 " + max + " 次），请基于已有结果作答，勿换词重搜。";
        }
        if ("searchCases".equals(toolName) && state.casesAlreadyAttached.get()) {
            return "本轮 searchLegalKnowledge 已附带相关判例摘要，勿再调用 searchCases。";
        }
        return null;
    }

    public void record(String sessionId, String toolName) {
        record(sessionId, toolName, null);
    }

    public void record(String sessionId, String toolName, String query) {
        if (sessionId == null || sessionId.isBlank() || toolName == null) {
            return;
        }
        TurnState state = turns.computeIfAbsent(sessionId, k -> new TurnState());
        state.counts.computeIfAbsent(toolName, k -> new AtomicInteger(0)).incrementAndGet();
        String fingerprint = fingerprint(toolName, query);
        if (fingerprint != null) {
            state.fingerprints.add(fingerprint);
        }
    }

    /** 本轮工具调用序号（Thought callId = toolName#seq） */
    public int nextCallSeq(String sessionId, String toolName) {
        if (sessionId == null || sessionId.isBlank() || toolName == null) {
            return 1;
        }
        TurnState state = turns.computeIfAbsent(sessionId, k -> new TurnState());
        return state.callSeqs.computeIfAbsent(toolName, k -> new AtomicInteger(0)).incrementAndGet();
    }

    public void markCasesAttached(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        turns.computeIfAbsent(sessionId, k -> new TurnState()).casesAlreadyAttached.set(true);
    }

    /** 联网未启用 / 再次空结果：禁止继续 searchWeb */
    public void markWebExhausted(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        turns.computeIfAbsent(sessionId, k -> new TurnState()).webExhausted.set(true);
    }

    /** 首次联网有命中：不再允许第二次 searchWeb（max 保持 1） */
    public void markWebHadHits(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        turns.computeIfAbsent(sessionId, k -> new TurnState()).webHadHits.set(true);
    }

    /**
     * 联网无命中：若本轮 searchWeb 已达 2 次则耗尽；否则仅标记 emptyOnce，允许不同指纹再试 1 次。
     * 若由 knowledge auto-fallback 触发且尚未记过 searchWeb，则虚拟占用 1 次额度。
     */
    public void markWebEmpty(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        TurnState state = turns.computeIfAbsent(sessionId, k -> new TurnState());
        state.webEmptyOnce.set(true);
        AtomicInteger webCount = state.counts.computeIfAbsent("searchWeb", k -> new AtomicInteger(0));
        if (webCount.get() == 0) {
            webCount.compareAndSet(0, 1);
        }
        if (webCount.get() >= SEARCH_WEB_MAX_AFTER_EMPTY) {
            state.webExhausted.set(true);
        }
    }

    static String fingerprint(String toolName, String query) {
        if (toolName == null) {
            return null;
        }
        return toolName + "\u001f" + normalizeQuery(query);
    }

    static String normalizeQuery(String query) {
        if (query == null) {
            return "";
        }
        return query.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}&&[^\\u4e00-\\u9fff]]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static int maxFor(String toolName, TurnState state) {
        if (!"searchWeb".equals(toolName)) {
            return DEFAULT_MAX;
        }
        if (state.webHadHits.get()) {
            return SEARCH_WEB_MAX_DEFAULT;
        }
        if (state.webEmptyOnce.get()) {
            return SEARCH_WEB_MAX_AFTER_EMPTY;
        }
        return SEARCH_WEB_MAX_DEFAULT;
    }

    private static final class TurnState {
        private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> callSeqs = new ConcurrentHashMap<>();
        private final Set<String> fingerprints = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean casesAlreadyAttached = new AtomicBoolean(false);
        private final AtomicBoolean webExhausted = new AtomicBoolean(false);
        private final AtomicBoolean webEmptyOnce = new AtomicBoolean(false);
        private final AtomicBoolean webHadHits = new AtomicBoolean(false);
    }
}
