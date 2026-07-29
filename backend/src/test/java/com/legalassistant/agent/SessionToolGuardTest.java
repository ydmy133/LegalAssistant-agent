package com.legalassistant.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        assertNull(guard.check("s1", "searchLegalKnowledge"));
        guard.record("s1", "searchLegalKnowledge");

        String blocked = guard.check("s1", "searchLegalKnowledge");
        assertNotNull(blocked);
        assertTrue(blocked.contains("searchLegalKnowledge"));
        assertTrue(blocked.contains("勿换词重搜"));
    }

    @Test
    void allowsSearchWebTwiceThenRejects() {
        assertNull(guard.check("s1", "searchWeb"));
        guard.record("s1", "searchWeb");
        assertNull(guard.check("s1", "searchWeb"));
        guard.record("s1", "searchWeb");

        String blocked = guard.check("s1", "searchWeb");
        assertNotNull(blocked);
        assertTrue(blocked.contains("上限 2"));
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
        guard.record("s1", "searchLegalKnowledge");
        guard.endTurn("s1");
        guard.beginTurn("s1");
        assertNull(guard.check("s1", "searchLegalKnowledge"));
    }
}
