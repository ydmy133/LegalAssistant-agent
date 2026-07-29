package com.legalassistant.service.impl;

import com.legalassistant.config.WebSearchProperties;
import com.legalassistant.retrieval.KnowledgeCandidate;
import com.legalassistant.retrieval.KnowledgeSourceType;
import com.legalassistant.retrieval.LocalRetrievalOutcome;
import com.legalassistant.retrieval.WebSearchHit;
import com.legalassistant.service.KnowledgeFusionService;
import com.legalassistant.timing.ChatTiming;
import com.legalassistant.timing.ChatTimingRegistry;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class KnowledgeFusionServiceImpl implements KnowledgeFusionService {

    private final WebSearchProperties properties;

    @Override
    public String fuse(String query, LocalRetrievalOutcome localOutcome, List<WebSearchHit> webHits) {
        ChatTiming timing = ChatTimingRegistry.current();
        ChatTiming.Stage stage = timing != null
                ? timing.startChildStage("KnowledgeFusion", "local+web")
                : null;

        try {
            List<KnowledgeCandidate> localCandidates = toLocalCandidates(localOutcome, query);
            List<KnowledgeCandidate> webCandidates = toWebCandidates(webHits, query);
            List<KnowledgeCandidate> fused = mergeWithLocalQuota(localCandidates, webCandidates);
            String formatted = formatCandidates(fused);
            if (stage != null) {
                stage.end("segments=" + fused.size()
                        + ", local=" + localCandidates.size()
                        + ", web=" + webCandidates.size());
            }
            return formatted;
        } catch (RuntimeException e) {
            if (stage != null) {
                stage.end("error=" + e.getMessage());
            }
            throw e;
        }
    }

    @Override
    public String formatWebHits(List<WebSearchHit> webHits) {
        if (webHits == null || webHits.isEmpty()) {
            return "联网检索未找到相关内容。";
        }
        List<KnowledgeCandidate> candidates = toWebCandidates(webHits, "");
        return formatCandidates(candidates);
    }

    private List<KnowledgeCandidate> toLocalCandidates(LocalRetrievalOutcome outcome, String query) {
        List<KnowledgeCandidate> candidates = new ArrayList<>();
        if (outcome == null) {
            return candidates;
        }
        double weight = properties.getSourceWeights().getLocal();
        double relevanceBase = computeRelevance(query, outcome.getRawText());

        if (outcome.getSegments() != null && !outcome.getSegments().isEmpty()) {
            int rank = 1;
            for (TextSegment segment : outcome.getSegments()) {
                if (segment == null || segment.text() == null || segment.text().isBlank()) {
                    continue;
                }
                String fileName = segment.metadata() != null
                        ? segment.metadata().getString("file_name")
                        : null;
                candidates.add(buildCandidate(
                        segment.text(),
                        fileName != null ? fileName : "本地知识库",
                        null,
                        KnowledgeSourceType.LOCAL,
                        weight,
                        computeRelevance(query, segment.text()),
                        rank++));
            }
            return candidates;
        }

        if (outcome.getRawText() != null && !outcome.getRawText().isBlank()) {
            candidates.add(buildCandidate(
                    outcome.getRawText(),
                    "本地知识库",
                    null,
                    KnowledgeSourceType.LOCAL,
                    weight,
                    relevanceBase,
                    1));
        }
        return candidates;
    }

    private List<KnowledgeCandidate> toWebCandidates(List<WebSearchHit> webHits, String query) {
        List<KnowledgeCandidate> candidates = new ArrayList<>();
        if (webHits == null || webHits.isEmpty()) {
            return candidates;
        }
        for (WebSearchHit hit : webHits) {
            if (hit == null) {
                continue;
            }
            String text = hit.displayText();
            if (text == null || text.isBlank()) {
                continue;
            }
            double weight = hit.getSourceType() == KnowledgeSourceType.GOV
                    ? properties.getSourceWeights().getGov()
                    : properties.getSourceWeights().getWeb();
            String label = hit.getTitle() != null && !hit.getTitle().isBlank()
                    ? hit.getTitle()
                    : (hit.getDomain() != null ? hit.getDomain() : "网页");
            candidates.add(buildCandidate(
                    text,
                    label,
                    hit.getUrl(),
                    hit.getSourceType(),
                    weight,
                    computeRelevance(query, text),
                    hit.getRank()));
        }
        return candidates;
    }

    private KnowledgeCandidate buildCandidate(String content,
                                              String sourceLabel,
                                              String url,
                                              KnowledgeSourceType sourceType,
                                              double sourceWeight,
                                              double relevanceScore,
                                              int rankInSource) {
        double rrfScore = 1.0 / (60.0 + rankInSource);
        double finalScore = rrfScore * sourceWeight * (1.0 + relevanceScore);
        return KnowledgeCandidate.builder()
                .content(content)
                .sourceLabel(sourceLabel)
                .url(url)
                .sourceType(sourceType)
                .sourceWeight(sourceWeight)
                .relevanceScore(relevanceScore)
                .rrfScore(rrfScore)
                .finalScore(finalScore)
                .rankInSource(rankInSource)
                .build();
    }

    List<KnowledgeCandidate> mergeWithLocalQuota(List<KnowledgeCandidate> localCandidates,
                                                 List<KnowledgeCandidate> webCandidates) {
        int maxSegments = properties.getMaxFusedSegments();
        int localQuota = Math.min(localCandidates.size(), maxSegments / 2);

        List<KnowledgeCandidate> selected = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        localCandidates.stream()
                .sorted(Comparator.comparingDouble(KnowledgeCandidate::getFinalScore).reversed())
                .limit(localQuota)
                .forEach(candidate -> addIfUnique(selected, seen, candidate));

        List<KnowledgeCandidate> remaining = new ArrayList<>();
        localCandidates.stream()
                .filter(candidate -> !selected.contains(candidate))
                .forEach(remaining::add);
        remaining.addAll(webCandidates);

        remaining.stream()
                .sorted(Comparator.comparingDouble(KnowledgeCandidate::getFinalScore).reversed())
                .forEach(candidate -> {
                    if (selected.size() >= maxSegments) {
                        return;
                    }
                    addIfUnique(selected, seen, candidate);
                });

        return selected;
    }

    private static void addIfUnique(List<KnowledgeCandidate> selected,
                                    Set<String> seen,
                                    KnowledgeCandidate candidate) {
        String key = normalizeContent(candidate.getContent());
        if (key.isBlank() || !seen.add(key)) {
            return;
        }
        selected.add(candidate);
    }

    private static String normalizeContent(String content) {
        if (content == null) {
            return "";
        }
        return content.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    double computeRelevance(String query, String content) {
        if (query == null || query.isBlank() || content == null || content.isBlank()) {
            return 0.0;
        }
        List<String> keywords = LightRAGServiceImpl.extractHighLevelKeywords(query);
        if (keywords.isEmpty()) {
            return content.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)) ? 0.5 : 0.0;
        }
        int hits = 0;
        String lowerContent = content.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank()
                    && lowerContent.contains(keyword.toLowerCase(Locale.ROOT))) {
                hits++;
            }
        }
        return (double) hits / keywords.size();
    }

    private String formatCandidates(List<KnowledgeCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "未找到可用的法律知识片段。";
        }
        StringBuilder sb = new StringBuilder();
        for (KnowledgeCandidate candidate : candidates) {
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(formatCandidate(candidate));
        }
        return sb.toString();
    }

    private static String formatCandidate(KnowledgeCandidate candidate) {
        String typeLabel = switch (candidate.getSourceType()) {
            case LOCAL -> "本地库";
            case GOV -> "权威网页";
            case WEB -> "网页";
        };
        StringBuilder header = new StringBuilder("[来源: ")
                .append(candidate.getSourceLabel())
                .append(" | 类型: ")
                .append(typeLabel)
                .append(" | 得分: ")
                .append(String.format(Locale.ROOT, "%.2f", candidate.getFinalScore()));
        if (candidate.getUrl() != null && !candidate.getUrl().isBlank()) {
            header.append(" | URL: ").append(candidate.getUrl());
        }
        header.append("]\n");
        String body = candidate.getContent() != null ? candidate.getContent() : "";
        int maxBody = candidate.getSourceType() == KnowledgeSourceType.LOCAL ? 600 : 400;
        if (body.length() > maxBody) {
            body = body.substring(0, maxBody) + "...";
        }
        return header + body;
    }
}
