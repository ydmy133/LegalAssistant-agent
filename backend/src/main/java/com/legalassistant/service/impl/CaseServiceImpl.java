package com.legalassistant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.legalassistant.agent.LegalAssistantAgent;
import com.legalassistant.entity.LegalCase;
import com.legalassistant.exception.BusinessException;
import com.legalassistant.mapper.LegalCaseMapper;
import com.legalassistant.service.CaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CaseServiceImpl implements CaseService {

    private final LegalCaseMapper caseMapper;
    private final LegalAssistantAgent agent;

    @Override
    public LegalCase create(LegalCase legalCase) {
        caseMapper.insert(legalCase);
        return legalCase;
    }

    @Override
    public LegalCase update(LegalCase legalCase) {
        LegalCase existing = caseMapper.selectById(legalCase.getId());
        if (existing == null) {
            throw new BusinessException(404, "案件不存在");
        }
        caseMapper.updateById(legalCase);
        return caseMapper.selectById(legalCase.getId());
    }

    @Override
    public void delete(Long id) {
        if (caseMapper.selectById(id) == null) {
            throw new BusinessException(404, "案件不存在");
        }
        caseMapper.deleteById(id);
    }

    @Override
    public LegalCase getById(Long id) {
        LegalCase c = caseMapper.selectById(id);
        if (c == null) {
            throw new BusinessException(404, "案件不存在");
        }
        return c;
    }

    @Override
    public Page<LegalCase> list(int page, int size, String caseType) {
        LambdaQueryWrapper<LegalCase> wrapper = new LambdaQueryWrapper<>();
        if (caseType != null && !caseType.isBlank()) {
            wrapper.eq(LegalCase::getCaseType, caseType);
        }
        wrapper.orderByDesc(LegalCase::getCreateTime);
        return caseMapper.selectPage(new Page<>(page, size), wrapper);
    }

    @Override
    public String analyze(Long caseId) {
        LegalCase c = getById(caseId);
        String prompt = String.format("""
                        请分析以下法律案件：
                        案号: %s
                        标题: %s
                        审理法院: %s
                        案件类型: %s
                        当事人: %s
                        案件摘要: %s
                        详细内容: %s

                        请从以下几个方面进行分析：
                        1. 案件争议焦点
                        2. 适用的法律法规
                        3. 类似判例参考
                        4. 初步法律意见
                        5. 风险提示""",
                c.getCaseNumber(), c.getTitle(),
                c.getCourt() != null ? c.getCourt() : "未知",
                c.getCaseType() != null ? c.getCaseType() : "未知",
                c.getParties() != null ? c.getParties() : "未知",
                c.getSummary() != null ? c.getSummary() : "无",
                c.getContent() != null ? c.getContent() : "无");
        return agent.chat("case-analysis-" + caseId, prompt);
    }
}
