package com.legalassistant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "legal.lightrag")
public class LightRAGProperties {

    private String baseUrl = "http://localhost:9621";
    private String apiKey = "";
    private String queryMode = "hybrid";
    private long ingestPollIntervalMs = 2000;
    private long ingestPollTimeoutSeconds = 600;
    private String fileSourcePrefix = "legal-doc";

    /** 传给 LightRAG top_k；较小可加快图谱检索 */
    private int topK = 15;
    /** 初始文本块数量 */
    private int chunkTopK = 6;
    /** 关闭重排可明显缩短 query/data */
    private boolean enableRerank = false;
    /**
     * 为 true 时由后端从 query 抽取关键词填入 hl/ll_keywords，
     * 避免 LightRAG 再调 LLM 抽词（冷启动可节省数秒）。
     */
    private boolean provideKeywords = true;
    /** 写入 Agent 上下文的最大 TextSegment 数（实体+关系+块合计） */
    private int maxSegments = 16;
    /** 同语义短缓存 TTL（秒）；0 关闭。宜 ≥ 数分钟，避免复测冷路径回升到数秒 */
    private long queryCacheTtlSeconds = 600;
    /** format 时优先保留的 chunk 上限 */
    private int maxChunkSegments = 8;
    /** format 时实体段上限 */
    private int maxEntitySegments = 5;
    /** format 时关系段上限 */
    private int maxRelationSegments = 3;
}
