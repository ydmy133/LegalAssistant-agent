package com.legalassistant.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionToolGuardTest {

    private SessionToolGuard guard;

    @BeforeEach
    void setUp() {
        guard = new SessionToolGuard();
        guard.beginTurn("s1");
    }

    @Test
    void rejectsSecondCallOfSameTool() {
        assertNull(guard.check("s1", "searchLegalKnowledge", "未签合同"));
        guard.record("s1", "searchLegalKnowledge", "未签合同");

        String blocked = guard.check("s1", "searchLegalKnowledge", "其他问题");
        assertNotNull(blocked);
        assertTrue(blocked.contains("searchLegalKnowledge"));
        assertTrue(blocked.contains("上限"));
    }

    @Test
    void rejectsSameFingerprintEvenIfUnderNameCap() {
        assertNull(guard.check("s1", "searchWeb", "主播 劳动关系"));
        guard.record("s1", "searchWeb", "主播 劳动关系");
        guard.markWebEmpty("s1");

        String blocked = guard.check("s1", "searchWeb", "主播  劳动关系");
        assertNotNull(blocked);
        assertTrue(blocked.contains("相同意图"));
    }

    @Test
    void allowsSecondSearchWebOnlyAfterEmptyWithDifferentQuery() {
        assertNull(guard.check("s1", "searchWeb", "q1"));
        guard.record("s1", "searchWeb", "q1");
        guard.markWebEmpty("s1");

        assertNull(guard.check("s1", "searchWeb", "q2 不同"));
        guard.record("s1", "searchWeb", "q2 不同");
        guard.markWebEmpty("s1");

        String blocked = guard.check("s1", "searchWeb", "q3");
        assertNotNull(blocked);
        assertTrue(blocked.contains("无命中") || blocked.contains("上限"));
    }

    @Test
    void rejectsSecondSearchWebAfterHits() {
        assertNull(guard.check("s1", "searchWeb", "q1"));
        guard.record("s1", "searchWeb", "q1");
        guard.markWebHadHits("s1");

        String blocked = guard.check("s1", "searchWeb", "q2");
        assertNotNull(blocked);
        assertTrue(blocked.contains("已有联网") || blocked.contains("上限"));
    }

    @Test
    void rejectsWhenWebExhausted() {
        guard.markWebExhausted("s1");
        String blocked = guard.check("s1", "searchWeb", "anything");
        assertNotNull(blocked);
        assertTrue(blocked.contains("无命中") || blocked.contains("未启用"));
    }

    @Test
    void rejectsSearchCasesWhenKnowledgeAttachedCases() {
        guard.markCasesAttached("s1");
        String blocked = guard.check("s1", "searchCases");
        assertNotNull(blocked);
        assertTrue(blocked.contains("已附带相关判例"));
    }

    @Test
    void endTurnClearsCounters() {
        guard.record("s1", "searchLegalKnowledge", "x");
        guard.endTurn("s1");
        guard.beginTurn("s1");
        assertNull(guard.check("s1", "searchLegalKnowledge", "x"));
    }

    @Test
    void nextCallSeqIncrementsPerTool() {
        assertEquals(1, guard.nextCallSeq("s1", "searchWeb"));
        assertEquals(2, guard.nextCallSeq("s1", "searchWeb"));
        assertEquals(1, guard.nextCallSeq("s1", "searchLegalKnowledge"));
    }

    @Test
    void normalizeQueryCollapsesWhitespace() {
        assertEquals(
                SessionToolGuard.normalizeQuery("主播  劳动关系"),
                SessionToolGuard.normalizeQuery("主播 劳动关系"));
    }
}
