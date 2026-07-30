package com.legalassistant.service.impl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultSourceParserTest {

    @Test
    void parsesLocalAndWebBlocks() {
        String result = """
                [来源: 劳动合同法要点.md | 类型: 本地库 | 得分: 1.00]
                《劳动合同法》第八十二条 双倍工资

                [来源: 指导性案例239号 | 类型: 权威网页 | 得分: 0.90 | URL: https://www.court.gov.cn/zixun/xiangqing/450741.html]
                支配性劳动管理是劳动关系的本质特征
                """;

        List<Map<String, Object>> sources = ToolResultSourceParser.parse(result);
        assertEquals(2, sources.size());
        assertEquals("local", sources.get(0).get("kind"));
        assertEquals("劳动合同法要点.md", sources.get(0).get("fileName"));
        assertEquals("web", sources.get(1).get("kind"));
        assertTrue(String.valueOf(sources.get(1).get("url")).contains("court.gov.cn"));
        assertTrue(String.valueOf(sources.get(1).get("snippet")).contains("支配性"));
    }

    @Test
    void ignoresGuardMessages() {
        assertTrue(ToolResultSourceParser.parse("本轮已调用过 searchWeb（上限 1 次），请基于已有结果作答，勿换词重搜。").isEmpty());
    }
}
