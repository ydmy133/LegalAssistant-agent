package com.legalassistant.dto;

import lombok.Data;

@Data
public class CaseAnalysisRequest {
    private Long caseId;
    private String additionalContext;
}
