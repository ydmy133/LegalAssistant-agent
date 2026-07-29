package com.legalassistant.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.legalassistant.entity.Conversation;
import com.legalassistant.entity.LegalCase;
import com.legalassistant.entity.Message;
import com.legalassistant.mapper.ConversationMapper;
import com.legalassistant.mapper.LegalCaseMapper;
import com.legalassistant.mapper.MessageMapper;
import com.legalassistant.service.DocumentService;
import com.legalassistant.service.RAGService;
import com.legalassistant.timing.ChatTiming;
import com.legalassistant.timing.ChatTimingRegistry;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class LegalTools {

    private final RAGService ragService;
    private final DocumentService documentService;
    private final LegalCaseMapper caseMapper;
    private final MessageMapper messageMapper;
    private final ConversationMapper conversationMapper;

    @Tool("检索法律知识库（条文/要点），并自动附带最多2条相关判例摘要。常规问题只调用本工具一次即可；勿再调 searchCases/getCaseDetail，除非摘要明确不足。")
    public String searchLegalKnowledge(
            @ToolMemoryId String sessionId,
            @P("检索关键词或完整问题（优先一次写全）") String query) {
        log.info("Tool: searchLegalKnowledge called with query='{}'", query);
        ChatTiming.Stage stage = beginTool(sessionId, "searchLegalKnowledge",
                truncate(query, 80));
        try {
            String knowledge;
            String source;
            List<TextSegment> results = ragService.search(query, null);
            if (!results.isEmpty()) {
                knowledge = formatSegments(results);
                source = "lightrag";
            } else {
                ChatTiming timing = resolveTiming(sessionId);
                ChatTiming.Stage fallback = timing != null
                        ? timing.startChildStage("keywordFallback", "预置文档关键词检索")
                        : null;
                try {
                    knowledge = documentService.searchPresetDocumentsByKeyword(query);
                    if (fallback != null) {
                        fallback.end("chars=" + (knowledge != null ? knowledge.length() : 0));
                    }
                    source = "keywordFallback";
                } catch (RuntimeException e) {
                    if (fallback != null) {
                        fallback.end("error=" + e.getMessage());
                    }
                    throw e;
                }
            }

            String casesBlock = formatRelatedCases(query, 2);
            endTool(stage, "hits=" + (results != null ? results.size() : 0)
                    + ", source=" + source
                    + ", casesAttached=" + (casesBlock != null && !casesBlock.isBlank()));
            if (casesBlock == null || casesBlock.isBlank()) {
                return knowledge;
            }
            return knowledge + "\n\n---\n【相关判例摘要】（已附带，一般无需再调 searchCases/getCaseDetail）\n"
                    + casesBlock;
        } catch (Exception e) {
            log.warn("LightRAG search failed, fallback to keyword search: {}", e.getMessage());
            ChatTiming timing = resolveTiming(sessionId);
            ChatTiming.Stage fallback = timing != null
                    ? timing.startChildStage("keywordFallback", "LightRAG失败后关键词回退")
                    : null;
            try {
                String text = documentService.searchPresetDocumentsByKeyword(query);
                String casesBlock = formatRelatedCases(query, 2);
                if (fallback != null) {
                    fallback.end("chars=" + (text != null ? text.length() : 0));
                }
                endTool(stage, "error=" + e.getMessage() + ", source=keywordFallback");
                if (casesBlock == null || casesBlock.isBlank()) {
                    return text;
                }
                return text + "\n\n---\n【相关判例摘要】\n" + casesBlock;
            } catch (RuntimeException fallbackError) {
                if (fallback != null) {
                    fallback.end("error=" + fallbackError.getMessage());
                }
                endTool(stage, "error=" + e.getMessage());
                throw fallbackError;
            }
        }
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

    @Tool("按案由/关键词搜索相关判例。仅当 searchLegalKnowledge 未附带判例时使用；摘要已够用时不要再调 getCaseDetail。")
    public String searchCases(
            @ToolMemoryId String sessionId,
            @P("案由或短关键词（如：未签劳动合同、双倍工资）；可用空格分隔多个词") String keyword) {
        log.info("Tool: searchCases called with keyword='{}'", keyword);
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
                .map(seg -> String.format("[来源: %s] %s",
                        seg.metadata().getString("file_name") != null ? seg.metadata().getString("file_name") : "未知",
                        seg.text()))
                .collect(Collectors.joining("\n\n"));
    }

    @Tool("获取指定案件的详细信息。仅当 searchLegalKnowledge/searchCases 摘要不足以回答时，对已给出的案件 id 调用一次；常规问答禁止调用。")
    public String getCaseDetail(
            @ToolMemoryId String sessionId,
            @P("案件ID") Long caseId) {
        log.info("Tool: getCaseDetail called with caseId={}", caseId);
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

    @Tool("获取本会话已落库的对话历史。仅当用户明确要求回顾上文时使用；参数请传当前会话的 sessionId（UUID），不要传 current。")
    public String getConversationHistory(
            @ToolMemoryId String memoryId,
            @P("当前会话 sessionId（UUID 字符串）；可与系统记忆 id 相同") String sessionId) {
        String resolvedSession = (sessionId != null && !sessionId.isBlank()
                && !"current".equalsIgnoreCase(sessionId.trim()))
                ? sessionId.trim()
                : memoryId;
        log.info("Tool: getConversationHistory called with sessionId='{}' (resolved='{}')",
                sessionId, resolvedSession);
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
        ChatTiming timing = resolveTiming(sessionId);
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
