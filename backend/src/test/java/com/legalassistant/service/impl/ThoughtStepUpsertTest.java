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
    void upsertMergesSameCallIdRunningThenDone() {
        List<Map<String, Object>> steps = new ArrayList<>();
        Map<String, Object> running = new LinkedHashMap<>();
        running.put("type", "tool");
        running.put("name", "searchWeb");
        running.put("callId", "searchWeb#1");
        running.put("label", "联网搜索");
        running.put("status", "running");
        ChatServiceImpl.upsertThoughtStep(steps, running);

        Map<String, Object> done = new LinkedHashMap<>();
        done.put("type", "tool");
        done.put("name", "searchWeb");
        done.put("callId", "searchWeb#1");
        done.put("label", "联网搜索");
        done.put("status", "done");
        done.put("detail", "hits=5");
        done.put("durationMs", 9000L);
        ChatServiceImpl.upsertThoughtStep(steps, done);

        assertEquals(1, steps.size());
        assertEquals("done", steps.get(0).get("status"));
        assertEquals("hits=5", steps.get(0).get("detail"));
    }

    @Test
    void differentCallIdsStaySeparate() {
        List<Map<String, Object>> steps = new ArrayList<>();
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("type", "tool");
        first.put("name", "searchWeb");
        first.put("callId", "searchWeb#1");
        first.put("status", "done");
        ChatServiceImpl.upsertThoughtStep(steps, first);

        Map<String, Object> second = new LinkedHashMap<>();
        second.put("type", "tool");
        second.put("name", "searchWeb");
        second.put("callId", "searchWeb#2");
        second.put("status", "done");
        ChatServiceImpl.upsertThoughtStep(steps, second);

        assertEquals(2, steps.size());
    }
}
