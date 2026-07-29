package com.legalassistant.agent;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 会话级工具防抖：抑制同轮换词重复检索（类 grok doom-loop）。
 */
@Component
public class SessionToolGuard {

    private static final int DEFAULT_MAX = 1;
    private static final int SEARCH_WEB_MAX = 2;

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

    /**
     * @return null 表示允许；非空为拒绝原因文案
     */
    public String check(String sessionId, String toolName) {
        if (sessionId == null || sessionId.isBlank() || toolName == null) {
            return null;
        }
        TurnState state = turns.computeIfAbsent(sessionId, k -> new TurnState());
        int max = "searchWeb".equals(toolName) ? SEARCH_WEB_MAX : DEFAULT_MAX;
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
        if (sessionId == null || sessionId.isBlank() || toolName == null) {
            return;
        }
        TurnState state = turns.computeIfAbsent(sessionId, k -> new TurnState());
        state.counts.computeIfAbsent(toolName, k -> new AtomicInteger(0)).incrementAndGet();
    }

    public void markCasesAttached(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        TurnState state = turns.computeIfAbsent(sessionId, k -> new TurnState());
        state.casesAlreadyAttached.set(true);
    }

    private static final class TurnState {
        private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
        private final AtomicBoolean casesAlreadyAttached = new AtomicBoolean(false);
    }
}
