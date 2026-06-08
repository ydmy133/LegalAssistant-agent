package com.legalassistant.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.legalassistant.entity.LegalCase;

public interface CaseService {
    LegalCase create(LegalCase legalCase);
    LegalCase update(LegalCase legalCase);
    void delete(Long id);
    LegalCase getById(Long id);
    Page<LegalCase> list(int page, int size, String caseType);
    String analyze(Long caseId);
}
