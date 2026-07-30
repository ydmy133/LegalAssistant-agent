package com.legalassistant.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.legalassistant.entity.Conversation;
import com.legalassistant.entity.LegalCase;
import com.legalassistant.entity.Message;
import com.legalassistant.mapper.ConversationMapper;
import com.legalassistant.mapper.LegalCaseMapper;
import com.legalassistant.mapper.MessageMapper;
import com.legalassistant.retrieval.LocalConfidenceEvaluator;
import com.legalassistant.retrieval.LocalRetrievalOutcome;
import com.legalassistant.retrieval.WebSearchHit;
import com.legalassistant.service.DocumentService;
import com.legalassistant.service.KnowledgeFusionService;
import com.legalassistant.service.RAGService;
import com.legalassistant.service.WebSearchService;
import com.legalassistant.service.impl.SelfHostedWebSearchServiceImpl;
import com.legalassistant.timing.ChatTiming;
import com.legalassistant.timing.ChatTimingRegistry;
import com.legalassistant.timing.ThoughtEventBus;
import com.legalassistant.timing.ToolCallContext;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class LegalTools {

    private static final int SEGMENT_BODY_MAX = 600;
    private static final int TOTAL_KNOWLEDGE_MAX = 4500;

    private final RAGService ragService;
    private final DocumentService documentService;
    private final WebSearchService webSearchService;
    private final LocalConfidenceEvaluator confidenceEvaluator;
    private final KnowledgeFusionService knowledgeFusionService;
    private final SessionToolGuard sessionToolGuard;
    private final LegalCaseMapper caseMapper;
    private final MessageMapper messageMapper;
    private final ConversationMapper conversationMapper;

    @Tool("检索法律知识库（条文/要点），并自动附带最多2条相关判例摘要。本地置信度不足时会自动联网补充。本轮最多调用1次；已附带判例时勿再调 searchCases。")
    public String searchLegalKnowledge(
            @ToolMemoryId String sessionId,
            @P("检索关键词或完整问题（优先一次写全）") String query) {
        log.info("Tool: searchLegalKnowledge called with query='{}'", query);
        String blocked = sessionToolGuard.check(sessionId, "searchLegalKnowledge", query);
        if (blocked != null) {
            return blocked;
        }
        sessionToolGuard.record(sessionId, "searchLegalKnowledge", query);
        ChatTiming.Stage stage = beginTool(sessionId, "searchLegalKnowledge",
                truncate(query, 80));
        try {
            LocalRetrievalOutcome localOutcome = performLocalRetrieval(sessionId, query);
            String knowledge;
            boolean webFallback = false;
            int fusedSegments = localOutcome.hitCount();

            if (confidenceEvaluator.needsWebFallback(localOutcome)) {
                webFallback = true;
                List<WebSearchHit> webHits = webSearchService.search(query);
                noteWebOutcome(sessionId, webHits);
                knowledge = knowledgeFusionService.fuse(query, localOutcome, webHits);
                fusedSegments = countFormattedSegments(knowledge);
            } else if (localOutcome.getSegments() != null && !localOutcome.getSegments().isEmpty()) {
                knowledge = formatSegments(localOutcome.getSegments());
            } else {
                knowledge = localOutcome.getRawText() != null ? localOutcome.getRawText() : "未找到相关法律知识。";
            }
            knowledge = capText(knowledge, TOTAL_KNOWLEDGE_MAX);

            String casesBlock = formatRelatedCases(query, 2);
            boolean casesAttached = casesBlock != null && !casesBlock.isBlank();
            if (casesAttached) {
                sessionToolGuard.markCasesAttached(sessionId);
            }
            endTool(stage, "hits=" + localOutcome.hitCount()
                    + ", source=" + localOutcome.getSource().name().toLowerCase()
                    + ", webFallback=" + webFallback
                    + ", fusedSegments=" + fusedSegments
                    + ", casesAttached=" + casesAttached);
            return attachCasesBlock(knowledge, casesBlock);
        } catch (Exception e) {
            log.warn("LightRAG search failed, fallback to keyword search: {}", e.getMessage());
            LocalRetrievalOutcome fallbackOutcome = performKeywordFallback(sessionId, query, true, e.getMessage());
            String knowledge;
            boolean webFallback = false;
            int fusedSegments = 0;

            if (confidenceEvaluator.needsWebFallback(fallbackOutcome)) {
                webFallback = true;
                List<WebSearchHit> webHits = webSearchService.search(query);
                noteWebOutcome(sessionId, webHits);
                knowledge = knowledgeFusionService.fuse(query, fallbackOutcome, webHits);
                fusedSegments = countFormattedSegments(knowledge);
            } else {
                knowledge = fallbackOutcome.getRawText();
            }
            knowledge = capText(knowledge, TOTAL_KNOWLEDGE_MAX);

            String casesBlock = formatRelatedCases(query, 2);
            if (casesBlock != null && !casesBlock.isBlank()) {
                sessionToolGuard.markCasesAttached(sessionId);
            }
            endTool(stage, "error=" + e.getMessage()
                    + ", source=keywordFallback"
                    + ", webFallback=" + webFallback
                    + ", fusedSegments=" + fusedSegments
                    + ", casesAttached=" + (casesBlock != null && !casesBlock.isBlank()));
            return attachCasesBlock(knowledge, casesBlock);
        }
    }

    @Tool("联网检索官方法律信息。仅当本地库明显不足或用户问最新/修订/某地规定时调用；默认本轮最多1次，空结果后可换不同意图再试1次，禁止同词重搜。返回含 URL 的短摘要。")
    public String searchWeb(
            @ToolMemoryId String sessionId,
            @P("检索关键词或完整问题") String query) {
        log.info("Tool: searchWeb called with query='{}'", query);
        String blocked = sessionToolGuard.check(sessionId, "searchWeb", query);
        if (blocked != null) {
            return blocked;
        }
        sessionToolGuard.record(sessionId, "searchWeb", query);
        ChatTiming.Stage stage = beginTool(sessionId, "searchWeb", truncate(query, 80));
        try {
            if (!webSearchService.isAvailable()) {
                sessionToolGuard.markWebExhausted(sessionId);
                endTool(stage, "disabled");
                return "联网搜索未启用（请设置 WEB_SEARCH_ENABLED=true），无法检索。";
            }
            List<WebSearchHit> hits = webSearchService.search(query);
            noteWebOutcome(sessionId, hits);
            String reason = null;
            if (webSearchService instanceof SelfHostedWebSearchServiceImpl selfHosted) {
                reason = selfHosted.lastErrorReason();
            }
            String formatted = knowledgeFusionService.formatWebHits(hits, reason);
            endTool(stage, "hits=" + hits.size()
                    + (reason != null ? ", reason=" + truncate(reason, 60) : ""));
            return formatted;
        } catch (RuntimeException e) {
            endTool(stage, "error=" + e.getMessage());
            throw e;
        }
    }

    private void noteWebOutcome(String sessionId, List<WebSearchHit> hits) {
        if (hits != null && !hits.isEmpty()) {
            sessionToolGuard.markWebHadHits(sessionId);
        } else {
            sessionToolGuard.markWebEmpty(sessionId);
        }
    }

    private LocalRetrievalOutcome performLocalRetrieval(String sessionId, String query) {
        try {
            List<TextSegment> results = ragService.search(query, null);
            if (!results.isEmpty()) {
                return LocalRetrievalOutcome.builder()
                        .segments(results)
                        .rawText(formatSegments(results))
                        .source(LocalRetrievalOutcome.LocalRetrievalSource.LIGHTRAG)
                        .lightragFailed(false)
                        .build();
            }
            return performKeywordFallback(sessionId, query, false, null);
        } catch (RuntimeException e) {
            return performKeywordFallback(sessionId, query, true, e.getMessage());
        }
    }

    private LocalRetrievalOutcome performKeywordFallback(String sessionId,
                                                         String query,
                                                         boolean lightragFailed,
                                                         String errorMessage) {
        ChatTiming timing = resolveTiming(sessionId);
        ChatTiming.Stage fallback = timing != null
                ? timing.startChildStage("keywordFallback",
                lightragFailed ? "LightRAG失败后关键词回退" : "预置文档关键词检索")
                : null;
        try {
            String knowledge = documentService.searchPresetDocumentsByKeyword(query);
            if (fallback != null) {
                fallback.end("chars=" + (knowledge != null ? knowledge.length() : 0));
            }
            return LocalRetrievalOutcome.builder()
                    .segments(List.of())
                    .rawText(knowledge)
                    .source(LocalRetrievalOutcome.LocalRetrievalSource.KEYWORD_FALLBACK)
                    .errorMessage(errorMessage)
                    .lightragFailed(lightragFailed)
                    .build();
        } catch (RuntimeException e) {
            if (fallback != null) {
                fallback.end("error=" + e.getMessage());
            }
            throw e;
        }
    }

    private String attachCasesBlock(String knowledge, String casesBlock) {
        if (casesBlock == null || casesBlock.isBlank()) {
            return knowledge;
        }
        return knowledge + "\n\n---\n【相关判例摘要】（已附带，一般无需再调 searchCases/getCaseDetail）\n"
                + casesBlock;
    }

    private static int countFormattedSegments(String knowledge) {
        if (knowledge == null || knowledge.isBlank()) {
            return 0;
        }
        return (int) knowledge.lines()
                .filter(line -> line.startsWith("[来源:"))
                .count();
    }

    /**
     * 自动附带判例，减少额外 tool 轮次。
     */
    private String formatRelatedCases(String query, int limit) {
        List<LegalCase> cases = searchCasesByKeyword(query);
        if (cases.isEmpty()) {
            return "";
        }
        return cases.stream()
                .limit(limit)
                .map(c -> String.format(
                        "[id=%d][案号: %s] %s | 法院: %s | 摘要: %s",
                        c.getId(),
                        c.getCaseNumber(), c.getTitle(),
                        c.getCourt() != null ? c.getCourt() : "未知",
                        c.getSummary() != null ? c.getSummary() : "无"))
                .collect(Collectors.joining("\n---\n"));
    }

    @Tool("按案由/关键词搜索相关判例。仅当 searchLegalKnowledge 未附带判例时使用；本轮最多1次。")
    public String searchCases(
            @ToolMemoryId String sessionId,
            @P("案由或短关键词（如：未签劳动合同、双倍工资）；可用空格分隔多个词") String keyword) {
        log.info("Tool: searchCases called with keyword='{}'", keyword);
        String blocked = sessionToolGuard.check(sessionId, "searchCases", keyword);
        if (blocked != null) {
            return blocked;
        }
        sessionToolGuard.record(sessionId, "searchCases", keyword);
        ChatTiming.Stage stage = beginTool(sessionId, "searchCases", truncate(keyword, 80));
        try {
            List<LegalCase> cases = searchCasesByKeyword(keyword);
            if (cases.isEmpty()) {
                endTool(stage, "hits=0");
                return "未找到相关判例。";
            }
            endTool(stage, "hits=" + cases.size());
            return cases.stream()
                    .map(c -> String.format(
                            "[id=%d][案号: %s] %s | 法院: %s | 类型: %s | 判决日期: %s | 摘要: %s",
                            c.getId(),
                            c.getCaseNumber(), c.getTitle(),
                            c.getCourt() != null ? c.getCourt() : "未知",
                            c.getCaseType() != null ? c.getCaseType() : "未知",
                            c.getJudgmentDate() != null ? c.getJudgmentDate().toString() : "未知",
                            c.getSummary() != null ? c.getSummary() : "无"))
                    .collect(Collectors.joining("\n---\n"));
        } catch (RuntimeException e) {
            endTool(stage, "error=" + e.getMessage());
            throw e;
        }
    }

    /**
     * 归一化 + 同义词扩展 + 中文滑窗，提高「未签劳动合同」命中「未签订书面劳动合同」等样例案。
     */
    private List<LegalCase> searchCasesByKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        String normalized = keyword
                .replaceAll("[\\p{Punct}？?！!。，、；;：:（）()【】\\[\\]《》\"']+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        for (String part : normalized.split(" ")) {
            if (part.length() >= 2) {
                terms.add(part);
            }
        }
        expandCaseSynonyms(terms, normalized);

        // 长句无空格：滑窗 2～4 字
        String compact = normalized.replace(" ", "");
        if (compact.length() >= 4) {
            for (int len = 4; len >= 2; len--) {
                for (int i = 0; i + len <= Math.min(compact.length(), 24); i++) {
                    terms.add(compact.substring(i, i + len));
                }
            }
        }

        List<LegalCase> cases = queryCasesLikeAny(new ArrayList<>(terms));
        if (!cases.isEmpty()) {
            return cases;
        }
        // 最后回退：劳动争议类型下按「双倍/未签」等宽匹配
        return queryCasesLikeAny(List.of("双倍工资", "未签订", "劳动合同"));
    }

    private static void expandCaseSynonyms(Set<String> terms, String normalized) {
        String n = normalized.replace(" ", "");
        if (n.contains("未签")) {
            terms.add("未签订");
            terms.add("未签订书面劳动合同");
            terms.add("书面劳动合同");
            terms.add("双倍工资");
        }
        if (n.contains("双倍")) {
            terms.add("双倍工资");
            terms.add("二倍的工资");
        }
        if (n.contains("无固定")) {
            terms.add("无固定期限");
        }
        if (n.contains("违法解除") || n.contains("解除")) {
            terms.add("违法解除");
            terms.add("赔偿金");
        }
    }

    private List<LegalCase> queryCasesLikeAny(List<String> terms) {
        if (terms == null || terms.isEmpty()) {
            return List.of();
        }
        // 去重并限制词数，避免 SQL 过大
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String term : terms) {
            if (term != null && term.length() >= 2) {
                unique.add(term);
            }
            if (unique.size() >= 16) {
                break;
            }
        }
        if (unique.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<LegalCase> qw = new LambdaQueryWrapper<>();
        boolean first = true;
        for (String term : unique) {
            if (first) {
                qw.and(w -> w.like(LegalCase::getTitle, term)
                        .or().like(LegalCase::getSummary, term)
                        .or().like(LegalCase::getCaseType, term)
                        .or().like(LegalCase::getContent, term)
                        .or().like(LegalCase::getCaseNumber, term));
                first = false;
            } else {
                qw.or(w -> w.like(LegalCase::getTitle, term)
                        .or().like(LegalCase::getSummary, term)
                        .or().like(LegalCase::getCaseType, term)
                        .or().like(LegalCase::getContent, term)
                        .or().like(LegalCase::getCaseNumber, term));
            }
        }
        return caseMapper.selectList(qw.last("LIMIT 5"));
    }

    private String formatSegments(List<TextSegment> results) {
        return results.stream()
                .map(seg -> {
                    String name = seg.metadata().getString("file_name") != null
                            ? seg.metadata().getString("file_name") : "未知";
                    String body = seg.text() != null ? seg.text() : "";
                    if (body.length() > SEGMENT_BODY_MAX) {
                        body = body.substring(0, SEGMENT_BODY_MAX) + "...";
                    }
                    return String.format("[来源: %s] %s", name, body);
                })
                .collect(Collectors.joining("\n\n"));
    }

    private static String capText(String text, int max) {
        if (text == null) {
            return null;
        }
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "\n…(已截断)";
    }

    @Tool("获取指定案件的详细信息。仅当摘要不足时对已给出的案件 id 调用；本轮最多1次。")
    public String getCaseDetail(
            @ToolMemoryId String sessionId,
            @P("案件ID") Long caseId) {
        log.info("Tool: getCaseDetail called with caseId={}", caseId);
        String blocked = sessionToolGuard.check(sessionId, "getCaseDetail", String.valueOf(caseId));
        if (blocked != null) {
            return blocked;
        }
        sessionToolGuard.record(sessionId, "getCaseDetail", String.valueOf(caseId));
        ChatTiming.Stage stage = beginTool(sessionId, "getCaseDetail", "caseId=" + caseId);
        try {
            LegalCase c = caseMapper.selectById(caseId);
            if (c == null) {
                endTool(stage, "notFound");
                return "案件不存在，ID: " + caseId;
            }
            endTool(stage, "ok");
            return String.format("""
                            案号: %s
                            标题: %s
                            审理法院: %s
                            案件类型: %s
                            当事人: %s
                            案件摘要: %s
                            判决日期: %s
                            详细内容: %s""",
                    c.getCaseNumber(), c.getTitle(),
                    c.getCourt() != null ? c.getCourt() : "未知",
                    c.getCaseType() != null ? c.getCaseType() : "未知",
                    c.getParties() != null ? c.getParties() : "未知",
                    c.getSummary() != null ? c.getSummary() : "无",
                    c.getJudgmentDate() != null ? c.getJudgmentDate().toString() : "未知",
                    c.getContent() != null ? c.getContent() : "无");
        } catch (RuntimeException e) {
            endTool(stage, "error=" + e.getMessage());
            throw e;
        }
    }

    @Tool("获取本会话已落库的对话历史。仅当用户明确要求回顾上文时使用；本轮最多1次。")
    public String getConversationHistory(
            @ToolMemoryId String memoryId,
            @P("当前会话 sessionId（UUID 字符串）；可与系统记忆 id 相同") String sessionId) {
        String resolvedSession = (sessionId != null && !sessionId.isBlank()
                && !"current".equalsIgnoreCase(sessionId.trim()))
                ? sessionId.trim()
                : memoryId;
        log.info("Tool: getConversationHistory called with sessionId='{}' (resolved='{}')",
                sessionId, resolvedSession);
        String blocked = sessionToolGuard.check(memoryId, "getConversationHistory", resolvedSession);
        if (blocked != null) {
            return blocked;
        }
        sessionToolGuard.record(memoryId, "getConversationHistory", resolvedSession);
        ChatTiming.Stage stage = beginTool(memoryId, "getConversationHistory", truncate(resolvedSession, 40));
        try {
            Long conversationId = resolveConversationId(resolvedSession);
            if (conversationId == null) {
                endTool(stage, "noConversation");
                return "暂无对话历史。";
            }
            List<Message> messages = messageMapper.selectList(
                    new LambdaQueryWrapper<Message>()
                            .eq(Message::getConversationId, conversationId)
                            .orderByAsc(Message::getCreateTime)
                            .last("LIMIT 20")
            );
            if (messages.isEmpty()) {
                endTool(stage, "hits=0");
                return "暂无对话历史。";
            }
            endTool(stage, "hits=" + messages.size());
            return messages.stream()
                    .map(m -> String.format("[%s]: %s", m.getRole(), m.getContent()))
                    .collect(Collectors.joining("\n"));
        } catch (RuntimeException e) {
            endTool(stage, "error=" + e.getMessage());
            throw e;
        }
    }

    private Long resolveConversationId(String sessionOrId) {
        if (sessionOrId == null || sessionOrId.isBlank()) {
            return null;
        }
        String raw = sessionOrId.trim();
        if (raw.matches("\\d+")) {
            return Long.parseLong(raw);
        }
        Conversation conv = conversationMapper.selectOne(
                new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getSessionId, raw)
                        .last("LIMIT 1")
        );
        return conv != null ? conv.getId() : null;
    }

    private ChatTiming.Stage beginTool(String sessionId, String toolName, String detail) {
        ChatTimingRegistry.bind(sessionId);
        ThoughtEventBus.bindSession(sessionId);
        ChatTiming timing = resolveTiming(sessionId);
        int seq = sessionToolGuard.nextCallSeq(sessionId, toolName);
        String callId = toolName + "#" + seq;
        ToolCallContext.setCallId(callId);
        Map<String, Object> running = new LinkedHashMap<>();
        running.put("type", "tool");
        running.put("name", toolName);
        running.put("callId", callId);
        running.put("label", toolLabel(toolName));
        running.put("status", "running");
        if (detail != null && !detail.isBlank()) {
            running.put("query", detail);
            running.put("detail", "执行中…");
        }
        ThoughtEventBus.emit(sessionId, running);
        if (timing == null) {
            return null;
        }
        return timing.startToolStage(toolName, detail);
    }

    private void endTool(ChatTiming.Stage stage, String detail) {
        if (stage != null) {
            stage.end(detail);
        }
        ChatTiming timing = ChatTimingRegistry.current();
        if (timing != null) {
            timing.markToolEnded();
        }
        ChatTimingRegistry.setCurrentStage(null);
        // callId 保留到 ChatServiceImpl.onToolExecuted 读取后再 clear
    }

    private static String toolLabel(String toolName) {
        if (toolName == null) {
            return "调用工具";
        }
        return switch (toolName) {
            case "searchLegalKnowledge" -> "检索法律知识库";
            case "searchWeb" -> "联网搜索";
            case "searchCases" -> "搜索相关判例";
            case "getCaseDetail" -> "获取案件详情";
            case "getConversationHistory" -> "查阅对话历史";
            default -> "调用工具 " + toolName;
        };
    }

    private static ChatTiming resolveTiming(String sessionId) {
        ChatTiming timing = ChatTimingRegistry.get(sessionId);
        if (timing == null) {
            timing = ChatTimingRegistry.current();
        }
        if (timing != null) {
            ChatTimingRegistry.bind(timing);
        }
        return timing;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= max) {
            return normalized;
        }
        return normalized.substring(0, max) + "...";
    }
}
