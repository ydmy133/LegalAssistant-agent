package com.legalassistant.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.legalassistant.entity.LegalCase;
import com.legalassistant.entity.Message;
import com.legalassistant.mapper.LegalCaseMapper;
import com.legalassistant.mapper.MessageMapper;
import com.legalassistant.service.DocumentService;
import com.legalassistant.service.RAGService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class LegalTools {

    private final RAGService ragService;
    private final DocumentService documentService;
    private final LegalCaseMapper caseMapper;
    private final MessageMapper messageMapper;

    @Tool("检索法律知识库中的文档内容，用于回答法律问题")
    public String searchLegalKnowledge(
            @P("检索关键词或问题描述") String query) {
        log.info("Tool: searchLegalKnowledge called with query='{}'", query);

        try {
            List<TextSegment> results = ragService.search(query, null);
            if (!results.isEmpty()) {
                return formatSegments(results);
            }
        } catch (Exception e) {
            log.warn("RAGFlow search failed, fallback to keyword search: {}", e.getMessage());
        }

        return documentService.searchPresetDocumentsByKeyword(query);
    }

    @Tool("搜索相关法律判例，按案由或关键词检索")
    public String searchCases(
            @P("案由或关键词") String keyword) {
        log.info("Tool: searchCases called with keyword='{}'", keyword);
        List<LegalCase> cases = caseMapper.selectList(
                new LambdaQueryWrapper<LegalCase>()
                        .like(LegalCase::getTitle, keyword)
                        .or()
                        .like(LegalCase::getSummary, keyword)
                        .or()
                        .like(LegalCase::getCaseType, keyword)
                        .or()
                        .like(LegalCase::getContent, keyword)
                        .last("LIMIT 5")
        );
        if (cases.isEmpty() && keyword != null && keyword.length() > 2) {
            String sub = keyword.length() > 4 ? keyword.substring(0, 4) : keyword.substring(0, 2);
            cases = caseMapper.selectList(
                    new LambdaQueryWrapper<LegalCase>()
                            .like(LegalCase::getTitle, sub)
                            .or()
                            .like(LegalCase::getSummary, sub)
                            .or()
                            .like(LegalCase::getCaseType, sub)
                            .or()
                            .like(LegalCase::getContent, sub)
                            .last("LIMIT 5")
            );
        }
        if (cases.isEmpty()) {
            return "未找到相关判例。";
        }
        return cases.stream()
                .map(c -> String.format("[案号: %s] %s | 法院: %s | 类型: %s | 判决日期: %s | 摘要: %s",
                        c.getCaseNumber(), c.getTitle(),
                        c.getCourt() != null ? c.getCourt() : "未知",
                        c.getCaseType() != null ? c.getCaseType() : "未知",
                        c.getJudgmentDate() != null ? c.getJudgmentDate().toString() : "未知",
                        c.getSummary() != null ? c.getSummary() : "无"))
                .collect(Collectors.joining("\n---\n"));
    }

    private String formatSegments(List<TextSegment> results) {
        return results.stream()
                .map(seg -> String.format("[来源: %s] %s",
                        seg.metadata().getString("file_name") != null ? seg.metadata().getString("file_name") : "未知",
                        seg.text()))
                .collect(Collectors.joining("\n\n"));
    }

    @Tool("获取指定案件的详细信息")
    public String getCaseDetail(
            @P("案件ID") Long caseId) {
        log.info("Tool: getCaseDetail called with caseId={}", caseId);
        LegalCase c = caseMapper.selectById(caseId);
        if (c == null) {
            return "案件不存在，ID: " + caseId;
        }
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
    }

    @Tool("获取对话历史记录，用于理解上下文")
    public String getConversationHistory(
            @P("会话ID(sessionId)") String sessionId) {
        log.info("Tool: getConversationHistory called with sessionId='{}'", sessionId);
        List<Message> messages = messageMapper.selectList(
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getConversationId,
                                Long.parseLong(sessionId))
                        .orderByAsc(Message::getCreateTime)
                        .last("LIMIT 20")
        );
        if (messages.isEmpty()) {
            return "暂无对话历史。";
        }
        return messages.stream()
                .map(m -> String.format("[%s]: %s", m.getRole(), m.getContent()))
                .collect(Collectors.joining("\n"));
    }
}