package com.legalassistant.service;

import com.legalassistant.retrieval.LocalRetrievalOutcome;
import com.legalassistant.retrieval.WebSearchHit;

import java.util.List;

public interface KnowledgeFusionService {

    String fuse(String query, LocalRetrievalOutcome localOutcome, List<WebSearchHit> webHits);

    String formatWebHits(List<WebSearchHit> webHits);

    default String formatWebHits(List<WebSearchHit> webHits, String emptyReason) {
        if (webHits == null || webHits.isEmpty()) {
            if (emptyReason != null && !emptyReason.isBlank()) {
                return "联网检索未找到相关内容（" + emptyReason + "）。请基于已有信息作答，勿换词重复联网。";
            }
            return formatWebHits(webHits);
        }
        return formatWebHits(webHits);
    }
}
