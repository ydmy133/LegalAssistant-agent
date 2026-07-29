package com.legalassistant.service.impl;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThoughtStepUpsertTest {

    @Test
    void followUpDetection() {
        assertTrue(ChatServiceImpl.isFollowUpTurn("帮我生成一份详细回答"));
        assertTrue(ChatServiceImpl.isFollowUpTurn("请展开说明"));
        assertFalse(ChatServiceImpl.isFollowUpTurn("未签劳动合同有什么后果？"));
    }

    @Test
    void upsertMergesRunningThenDone() {
        List<Map<String, Object>> steps = new ArrayList<>();
        Map<String, Object> running = new LinkedHashMap<>();
        running.put("type", "tool");
        running.put("name", "searchLegalKnowledge");
        running.put("label", "检索法律知识库");
        running.put("status", "running");
        ChatServiceImpl.upsertThoughtStep(steps, running);

        Map<String, Object> done = new LinkedHashMap<>();
        done.put("type", "tool");
        done.put("name", "searchLegalKnowledge");
        done.put("label", "检索法律知识库");
        done.put("status", "done");
        done.put("detail", "hits=13");
        done.put("durationMs", 1300L);
        ChatServiceImpl.upsertThoughtStep(steps, done);

        assertEquals(1, steps.size());
        assertEquals("done", steps.get(0).get("status"));
        assertEquals("hits=13", steps.get(0).get("detail"));
        assertEquals(1300L, steps.get(0).get("durationMs"));
    }
}
