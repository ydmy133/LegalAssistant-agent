# `lightrag` 分支技术详解（面试精读）

> **目的**：带你把本仓库「法律助手 Agent + LightRAG RAG」整条链路吃透，并能对着代码讲清楚「为什么这么设计」。  
> **配套基准数据**：[`benchmark/LightRAG调优记录.md`](./benchmark/LightRAG调优记录.md)、[`benchmark/劳动合同问答基准测试.md`](./benchmark/劳动合同问答基准测试.md)  
> **开发速查**：[`../CLAUDE.md`](../CLAUDE.md)

文中链接均可在 IDE 中 Cmd/Ctrl+点击跳转源码。行号以当前 `lightrag` 分支为准，若略有漂移，以符号名为准定位。

---

## 0. 一句话项目定位（面试开场）

这是一个 **Spring Boot + LangChain4j Function Calling Agent** 的法律问答系统：LLM **自己决定何时调工具**；主检索走 **LightRAG hybrid（图谱实体/关系 + 文本 chunk + 向量）**；本地置信度不足时 **自动/显式联网（SearXNG）**；判例走 **MySQL LIKE**；前端用 **SSE 流式**展示 Cursor 风格 Thought 与文内引用芯片。

和「纯向量库 RAG」的差别：LightRAG 额外建了 **实体-关系图谱**，查询可同时拿 chunks / entities / relationships；我们在 Java 侧再做 **关键词预抽取、缓存、门控联网、融合排序、工具防抖**。

---

## 1. 总体架构

### 1.1 组件拓扑

| 组件 | 作用 | 入口 |
|------|------|------|
| Vue 前端 | 聊天 UI、Thought、引用芯片、SSE | [`frontend/src/views/ChatView.vue`](../frontend/src/views/ChatView.vue) |
| Spring Boot | API、Agent、工具、持久化 | [`backend/`](../backend/) |
| MySQL | 会话/消息/文档元数据/判例 | [`backend/sql/init.sql`](../backend/sql/init.sql) |
| Redis | 基础设施（对话热记忆不依赖它） | `docker-compose.yml` |
| Ollama `bge-m3` | LightRAG Embedding（1024 维） | compose `ollama` + `ollama-init` |
| LightRAG Server | 切块、抽实体关系、索引、hybrid 检索 | 端口 `9621` |
| SearXNG | 元搜索（替代易被反爬的 DDG） | 宿主机 `8088` |
| DeepSeek / OpenAI 兼容 | 对话 LLM；LightRAG 索引侧也用 DeepSeek | 用户模型配置 / `.env` |

Compose 定义见 [`docker-compose.yml`](../docker-compose.yml)。

### 1.2 一次问答的端到端数据流

```text
用户提问
  → ChatController /api/chat/stream
  → ChatServiceImpl.sendMessageStream
       ├ SessionToolGuard.beginTurn
       ├ ThoughtEventBus：分析问题 / 回顾上文 …
       └ AiServices(ChatAgent) + LegalTools + MessageWindowChatMemory(20)
            │ LLM function calling
            ▼
       LegalTools.@Tool
            ├ searchLegalKnowledge
            │     → LightRAGServiceImpl.search (hl/ll keywords + cache + query/data)
            │     → LocalConfidenceEvaluator? → WebSearch + KnowledgeFusion
            │     → 附带最多 2 条 MySQL 判例摘要
            ├ searchWeb（显式联网）
            ├ searchCases / getCaseDetail
            └ getConversationHistory
       → onToolExecuted：sources[] + SSE [EVENT]
       → 流式 token → metadata(timing, thoughtSteps, sources) 入库
  → 前端 formatMessage：Markdown 链接 → 引用芯片
```

核心编排：[`ChatServiceImpl.java`](../backend/src/main/java/com/legalassistant/service/impl/ChatServiceImpl.java)  
工具实现：[`LegalTools.java`](../backend/src/main/java/com/legalassistant/agent/LegalTools.java)  
系统提示（聊天主路径）：[`ChatAgent.java`](../backend/src/main/java/com/legalassistant/agent/ChatAgent.java)

**面试易错点**：仓库里还有 [`LegalAssistantAgent.java`](../backend/src/main/java/com/legalassistant/agent/LegalAssistantAgent.java)（`@AiService`），主要用于**案件分析**等路径；**日常聊天流**是 `ChatServiceImpl` **手动** `AiServices.builder(ChatAgent.class)`，prompt 以 `ChatAgent.SYSTEM_PROMPT` 为准。

---

## 2. 如何使用 LightRAG（运维 + 代码）

### 2.1 启动依赖

```bash
# 1) LightRAG 密钥（索引 LLM 用 DeepSeek）
cp deploy/lightrag/env.example deploy/lightrag/.env
# 填 LLM_BINDING_API_KEY / OPENAI_API_KEY（LightRAG 绑定会读）

# 2) 起全套
docker compose up -d

# 3) 初始化表（首次）
mysql -u root -p123123 < backend/sql/init.sql

# 4) 后端（或用 compose 的 backend 服务）
cd backend && export OPENAI_API_KEY=... && ./mvnw spring-boot:run
```

LightRAG 环境变量模板：[`deploy/lightrag/env.example`](../deploy/lightrag/env.example)

| 变量 | 含义 |
|------|------|
| `LIGHTRAG_API_KEY` | 后端 ↔ LightRAG 鉴权（与 Java `legal.lightrag.api-key` 一致） |
| `LLM_BINDING_*` | 索引时抽实体/关系的 LLM（DeepSeek） |
| `EMBEDDING_BINDING=ollama` + `bge-m3` | 向量模型，维度 1024 |
| `WORKSPACE=legal_assistant` | LightRAG 工作区隔离 |

Java 侧配置：[`application.yml` → `legal.lightrag`](../backend/src/main/resources/application.yml) · 绑定类 [`LightRAGProperties.java`](../backend/src/main/java/com/legalassistant/config/LightRAGProperties.java)

### 2.2 文档如何进 LightRAG

```text
用户上传 / 预置种子
  → DocumentServiceImpl：落盘 + MySQL document 行
  → RAGService.ingestDocument
  → LightRAGClient.uploadDocument (/documents/upload)
  → pollUntilDone(track_id)
  → countChunksByFileSource → 回写 chunkCount
```

- 入库：[`LightRAGServiceImpl.ingestDocument`](../backend/src/main/java/com/legalassistant/service/impl/LightRAGServiceImpl.java)（约 L41–60）  
- HTTP：[`LightRAGClient.uploadDocument` / `pollUntilDone`](../backend/src/main/java/com/legalassistant/client/LightRAGClient.java)（约 L37–113）  
- 启动同步预置法条 markdown：[`LightRAGSyncSeeder.java`](../backend/src/main/java/com/legalassistant/config/LightRAGSyncSeeder.java)  
- 预置文档：`backend/src/main/resources/legal-documents/*.md`（如劳动合同法要点）

`file_source` 命名：`legal-doc:{documentId}:{fileName}`，便于按文档删除索引（[`buildFileSource`](../backend/src/main/java/com/legalassistant/service/impl/LightRAGServiceImpl.java)）。

### 2.3 检索如何调 LightRAG

Java 只调 **`POST /query/data`**，并设 `only_need_context=true`（只要上下文，不要 LightRAG 自己再生成整段回答——**回答由我们的对话 LLM 生成**）。

请求体关键字段（[`LightRAGClient.queryData`](../backend/src/main/java/com/legalassistant/client/LightRAGClient.java) L123–157）：

| 字段 | 默认/来源 | 作用 |
|------|-----------|------|
| `mode` | `hybrid` | 混合图谱 + 向量/chunk |
| `top_k` | 15 | 图谱侧 top |
| `chunk_top_k` | 6 | 文本块数量 |
| `enable_rerank` | **false** | 关重排，显著降延迟 |
| `hl_keywords` / `ll_keywords` | 后端抽取 | **跳过 LightRAG 内再调 LLM 抽词** |
| `only_need_context` | true | 只要检索上下文 |

返回 `data.chunks` / `data.entities` / `data.relationships` → Java 格式化为 `TextSegment` 列表给 Agent。

---

## 3. LightRAG 内部 vs 本仓库边界（高频面试题）

### 3.1 在 LightRAG 容器里做的事（本 repo **没有**源码）

1. **文档切块（chunking）**：按 LightRAG 自身策略切文本块并向量化。  
2. **实体 / 关系抽取**：索引 LLM（DeepSeek）从块中抽「劳动合同法第 82 条」等实体及关系，写入图存储。  
3. **Embedding**：Ollama `bge-m3` 1024 维。  
4. **hybrid 检索**：同时走向量相似 + 图谱扩展，返回 chunks/entities/relationships。

### 3.2 在本仓库 Java 里做的事

1. 上传轮询、失败处理、按 `file_source` 删除。  
2. **查询侧关键词预抽取**（规则短语 + 汉字 n-gram），写入 `hl_keywords`/`ll_keywords`。  
3. **进程内语义缓存**（TTL 默认 600s）。  
4. **结果整形与配额**：chunks 优先，再实体（偏「条」），再关系；总段数封顶。  
5. **失败回退**：LightRAG 挂了 → 预置 markdown 关键词扫描。  
6. **置信度门控联网 + RRF 融合**。  
7. **Agent 工具协议 + 防抖 + Thought/耗时**。

> 面试官若问「你们怎么做中文分词？」要诚实说：  
> **没有接 Jieba/HanLP**；索引侧切词/图谱在 LightRAG 内；查询侧我们用 **领域短语表 + 汉字 2–4 元 n-gram** 给 LightRAG 喂关键词，并做缓存键归一。

---

## 4. 「分词 / 关键词」逻辑（务必吃透）

相关代码全部在 [`LightRAGServiceImpl.java`](../backend/src/main/java/com/legalassistant/service/impl/LightRAGServiceImpl.java)。

### 4.1 为什么要自己抽关键词？

LightRAG 若未收到 `hl_keywords`/`ll_keywords`，可能 **再调一次 LLM 抽词**，冷启动可多出数秒。配置项：

```text
legal.lightrag.provide-keywords: true   # LightRAGProperties.provideKeywords
```

见 [`LightRAGProperties`](../backend/src/main/java/com/legalassistant/config/LightRAGProperties.java) L25–29 注释。

### 4.2 高层关键词 `extractHighLevelKeywords`（约 L205–232）

1. 去标点，得到干净 query。  
2. 用**劳动法领域短语表**做子串匹配（如「未签劳动合同」「双倍工资」「第十四条第三款」）。  
3. `containsLoose`：去掉「订」「书面」再比，提高「未签订书面劳动合同」类召回。  
4. 特例：含「未签」→ 强制加入标准短语；含「双倍」→「双倍工资」。  
5. 若仍为空且长度 ≥4 → 截断取 query 前缀当 HL。

**面试话术**：这是 **domain lexicon + soft match**，不是通用分词；收益是稳定命中基准题法条相关实体。

### 4.3 低层关键词 `extractLowLevelKeywords`（约 L234–258）

1. 先匹配短术语表（「劳动合同」「第八十二条」「用工」…）。  
2. 再对纯汉字串做 **len=4→2 的滑动窗口 n-gram**，最多追加 6 个，且字符必须是 `UnicodeScript.HAN`。

作用：给 LightRAG 更细的字面线索，弥补 HL 过粗。

### 4.4 缓存键「语义归一」`normalizeQuery` / `buildCacheKey`（约 L174–203）

- 同义替换：「签订」→「签」，「书面劳动合同」→「劳动合同」。  
- 去标点空白；截断 48 字。  
- 优先用 **排序后的 HL 指纹** 做 cache key（`mode|sem=...|tk=...`），避免 Agent 改写问句导致次次 miss。  
- TTL 默认 600s；容量 >256 时淘汰过期/最旧一半（`putCache` L152–171）。

### 4.5 检索结果如何「切」给 LLM（不是分词，是上下文预算）

[`formatQueryData`](../backend/src/main/java/com/legalassistant/service/impl/LightRAGServiceImpl.java)（约 L287–305）：

1. **chunks** 最多 `maxChunkSegments`（默认 8）— 原文依据优先。  
2. **entities** 最多 5：含「条」或「劳动合同」的描述优先（更像法条）。  
3. **relationships** 最多 3。  
4. 再总封顶 `maxSegments`（默认 16）。

Agent 侧再把 segment 拼成带 `[来源: 文件名]` 的文本，并 `capText(..., 4500)`（[`LegalTools`](../backend/src/main/java/com/legalassistant/agent/LegalTools.java) `TOTAL_KNOWLEDGE_MAX`）。

### 4.6 判例侧的「类分词」

[`LegalTools.searchCasesByKeyword` 一带](../backend/src/main/java/com/legalassistant/agent/LegalTools.java)：同义词扩展 + 2–4 字滑窗 → MySQL `LIKE` OR 链（title/summary/content/…），**不是向量检索**。

关键词失败回退预置文档：[`DocumentServiceImpl.searchPresetDocumentsByKeyword`](../backend/src/main/java/com/legalassistant/service/impl/DocumentServiceImpl.java)。

---

## 5. 给 LLM 调用的工具（Function Calling）

全部在 [`LegalTools.java`](../backend/src/main/java/com/legalassistant/agent/LegalTools.java)。LangChain4j 扫描 Spring Bean 上的 `@Tool`；描述字符串会进模型的 tool schema。

### 5.1 工具一览

| 工具 | 职责 | 硬限制（SessionToolGuard + Prompt） |
|------|------|-------------------------------------|
| `searchLegalKnowledge` | 主 RAG；可自动联网；附带 ≤2 判例摘要 | 本轮通常 ≤1；指纹防重 |
| `searchWeb` | 显式联网，返回含 URL 摘要 | 默认 1；空结果可换意图再 1；有命中则耗尽 |
| `searchCases` | 单独搜判例列表 | 若知识工具已 `casesAttached` 则拒 |
| `getCaseDetail` | 按 ID 取全文 | Prompt：摘要够用禁止 |
| `getConversationHistory` | 最近消息 | 仅用户明确回顾时 |

#### `searchLegalKnowledge`（约 L56–127）— 主路径

```text
check/record(guard)
→ performLocalRetrieval → ragService.search
→ needsWebFallback?
     yes → webSearchService.search → knowledgeFusionService.fuse
     no  → formatSegments
→ cap 4500
→ formatRelatedCases(limit=2) → markCasesAttached
→ 返回「知识块 + 相关判例」
```

失败 catch：keywordFallback，同样可再门控联网。

#### `searchWeb`（约 L129–160）

独立工具；`formatWebHits` 产出带 `URL:` 的文案，供 Thought sources 解析与正文引用。

### 5.2 System Prompt 如何约束工具

[`ChatAgent.SYSTEM_PROMPT`](../backend/src/main/java/com/legalassistant/agent/ChatAgent.java)：

- `<tool_calling>`：次数、禁止换词连搜、已有判例勿再 searchCases。  
- `<citation_and_conflict>`：联网结论旁写 `[标题](URL)`，禁止文末堆链接。  
- `<labor_law_notes>`：第 7/82/14③；禁止未签直接推第 38 条。

**面试点**：工具描述 + System Prompt + SessionToolGuard **三层**限制，单靠模型自觉不够。

### 5.3 SessionToolGuard（防 doom-loop）

[`SessionToolGuard.java`](../backend/src/main/java/com/legalassistant/agent/SessionToolGuard.java)

- `beginTurn` / `endTurn`：每轮对话隔离状态。  
- `fingerprint(tool, normalize(query))`：同意图拒重搜。  
- `searchWeb`：`webHadHits` / `webExhausted` / 空后额度升到 2。  
- `nextCallSeq` → Thought `callId = searchWeb#1`（避免同名工具 UI 合并）。  

单测：[`SessionToolGuardTest.java`](../backend/src/test/java/com/legalassistant/agent/SessionToolGuardTest.java)

---

## 6. 本地置信度门控与知识融合

### 6.1 何时自动联网？

[`LocalConfidenceEvaluator.needsWebFallback`](../backend/src/main/java/com/legalassistant/retrieval/LocalConfidenceEvaluator.java)：

| 条件 | 是否 fallback |
|------|----------------|
| `autoFallback` 关或未配置联网 | 否 |
| LightRAG **有 hit** | **否**（本地够用） |
| LightRAG 失败 / keywordFallback / 0 hit | 是 |
| 有效字数 `< minLocalChars`(200) | 是 |
| 文案含「未在知识库中找到相关内容」 | 是 |

**设计意图**：标准劳动合同题应 **稳定不触发** `webFallback`（基准 T010+ 验收）；新业态（抖音劳动关系）才走联网。

### 6.2 融合算法

[`KnowledgeFusionServiceImpl`](../backend/src/main/java/com/legalassistant/service/impl/KnowledgeFusionServiceImpl.java)

1. 本地 segment / 网页 hit → `KnowledgeCandidate`。  
2. 分数：`finalScore = RRF(1/(60+rank)) × sourceWeight × (1+relevance)`  
   - local 权重 1.0，gov 0.90，web 0.65（`application.yml`）。  
3. `mergeWithLocalQuota`：先留约一半本地配额，再按分数填满 `maxFusedSegments`(16)。  
4. 格式化成 `[来源: xxx]\n...\nURL: https://...`（供解析与引用）。

联网实现：[`SelfHostedWebSearchServiceImpl`](../backend/src/main/java/com/legalassistant/service/impl/SelfHostedWebSearchServiceImpl.java)  
→ SearXNG → 域名偏好 gov.cn → 空则 [`CuratedLegalWebFallback`](../backend/src/main/java/com/legalassistant/websearch/CuratedLegalWebFallback.java)  
→ Jsoup 抓正文 + [`UrlSafety`](../backend/src/main/java/com/legalassistant/websearch/UrlSafety.java) SSRF 防护 + [`HtmlContentExtractor`](../backend/src/main/java/com/legalassistant/websearch/HtmlContentExtractor.java) 截断 footer。

---

## 7. 本分支在 LightRAG / Agent 上做了哪些优化

按调优文档 T001–T012 归纳（细节与耗时数字见 [`benchmark/LightRAG调优记录.md`](./benchmark/LightRAG调优记录.md)）：

| 主题 | 做法 | 代码/配置 |
|------|------|-----------|
| 密钥与索引可用性 | 修好 LightRAG `OPENAI_API_KEY`/DeepSeek，预置文档 SyncSeeder | `deploy/lightrag`、`LightRAGSyncSeeder` |
| 降 queryData 延迟 | `enable_rerank=false`；后端 `provideKeywords`；`chunk_top_k`/`top_k` 收紧 | `LightRAGProperties`、`LightRAGClient` |
| 语义缓存 | TTL 600s；HL 指纹键；预热 | `LightRAGServiceImpl` cache |
| 上下文预算 | maxSegments=16；chunks>entities>relations；Agent 4500 字封顶 | formatQueryData、LegalTools |
| 减工具轮次 | 知识工具内附带判例；Prompt+Guard 禁止重复 searchCases | LegalTools、SessionToolGuard |
| 篇幅 | maxTokens=8192；自适应长短答 | ChatAgent、`application.yml` |
| 劳动法正确性 | Prompt 禁第 38 条过度推断；知识库补条款 | ChatAgent、`legal-documents` |
| 联网 | SearXNG+Fetch+curated；置信度门控；指纹防抖 | websearch 包、T010–T012 |
| 可观测性 | ChatTiming 分阶段；Thought SSE；metadata 回显 | `timing/*`、ChatServiceImpl |
| UX | Cursor Thought；sources 展开；文内 cite chip | frontend `ChatView`、`formatMessage` |

**可量化口径**：标准问「未签劳动合同有什么后果？」前端总耗时约 **8–14s** 量级、准确度目标 **≥95**（以调优记录最新 T012 为准）。

---

## 8. 聊天、Thought、引用（前端 + 后端）

### 8.1 SSE 协议

[`frontend/src/api/chat.js`](../frontend/src/api/chat.js) 解析：

- 普通 chunk：流式正文  
- `[EVENT]...`：Thought 步骤（含 `sources`）  
- `[TIMING]...`：耗时明细  
- `[DONE]`：结束  

后端前缀常量在 `ChatServiceImpl`。

### 8.2 Thought sources

[`ToolResultSourceParser`](../backend/src/main/java/com/legalassistant/service/impl/ToolResultSourceParser.java) 从工具返回文案解析 `kind=local|web`、title、url、snippet → 写入步骤与 `metadata.sources`。

### 8.3 文内引用芯片

[`formatMessage.js`](../frontend/src/utils/formatMessage.js)：escape → 加粗 → `[text](https://...)` → `.cite-chip`；残留裸 URL 亦转芯片。Prompt 要求链接嵌在结论旁，而非文末列表。

### 8.4 Cloudflare 临时隧道（演示）

[`scripts/la-cf-tunnel.sh`](../scripts/la-cf-tunnel.sh)：公网指向 **生产 nginx:3080**（勿直连 Vite:3000，大模块易白屏）。说明见 [`scripts/la-cf.md`](../scripts/la-cf.md)（若有）。

---

## 9. 记忆与持久化

| 层 | 实现 |
|----|------|
| 热记忆 | LangChain4j `MessageWindowChatMemory`（窗口约 20），`@MemoryId = sessionId` |
| 冷存储 | MySQL `conversation` + `message`；助手消息 `metadata_json` 含 timing/thoughtSteps/sources |
| 标题 | 首条用户消息 |

注意：compose 有 Redis，但 **Agent 对话记忆主路径是进程内 ChatMemory + MySQL**，不要答成「用 Redis 做对话上下文」除非你另查到缓存用途。

---

## 10. 面试深挖题库（建议对照代码口述）

### Q1：为什么选 LightRAG 而不是纯 Milvus/PGVector？

答：法律文本适合 **实体（法条）+ 关系**；hybrid 可同时拿 chunk 原文与图谱结构化信息。我们实测过 Milvus 基线（见主基准 Run #001），LightRAG 分支在正确配置后准确度与可解释性更好；代价是索引依赖 LLM、运维多一个服务。

### Q2：hybrid mode 具体返回什么？你们怎么用？

答：`/query/data` 返回 entities、relationships、chunks。我们 **chunks 优先** 保证原文，实体里优先含「条」的描述，关系作补充，再配额封顶，避免撑爆上下文。

### Q3：Embedding 用的什么？和 application.yml 里 OpenAI embedding 什么关系？

答：RAG 检索 embedding 是 **Ollama bge-m3**（LightRAG 配置）。`application.yml` 里 LangChain4j embedding 配置与对话 RAG **解耦**；文档服务甚至可返回 null embeddingConfig（对话检索不走那条）。

### Q4：怎么防止 Agent 反复 searchWeb？

答：三层——Prompt 硬限制、`SessionToolGuard` 指纹+命中耗尽、工具描述写明上限。有命中 `markWebHadHits`，空结果 `markWebEmpty` 才允许换意图再试一次。

### Q5：本地有结果为什么还要置信度评估？

答：区分「LightRAG 真命中」与「空结果/失败回退/过短」。真命中 **禁止** 自动联网，避免标准题被噪声网页带偏；失败/过短才 fuse。

### Q6：融合里 RRF 是什么？

答：Reciprocal Rank Fusion：`1/(k+rank)`，再乘源权重与简单相关度，保证排序稳定且偏向本地/官源。

### Q7：判例为什么不用向量？

答：判例量小、要精确案号与摘要；LIKE + 同义词对 demo/预置数据足够，且可在知识工具内一次附带，减少一轮 function call。

### Q8：流式 Thought 如何与工具对齐？

答：`beginTool` 发 running 事件并 `ToolCallContext.setCallId`；`onToolExecuted` 用同一 callId upsert 为 done，并解析 sources；前端按 callId 保留展开状态。

### Q9：如果面试官问「分词器在哪」？

答：澄清分层——索引切分在 LightRAG；查询侧是规则 HL/LL；不是通用 NLP 分词流水线。若要增强可接 jieba，但要评估与 LightRAG 抽词/缓存键的一致性。

### Q10：安全上注意什么？

答：抓取 URL 的 `UrlSafety`（拒私网/localhost/非 http(s)）；上传大小限制；JWT 登录；公网隧道仅演示、注意勿长期暴露。

---

## 11. 代码导航索引（按主题）

| 主题 | 文件 |
|------|------|
| Agent Prompt | [`ChatAgent.java`](../backend/src/main/java/com/legalassistant/agent/ChatAgent.java) |
| 工具 | [`LegalTools.java`](../backend/src/main/java/com/legalassistant/agent/LegalTools.java) |
| 防抖 | [`SessionToolGuard.java`](../backend/src/main/java/com/legalassistant/agent/SessionToolGuard.java) |
| 流式聊天 | [`ChatServiceImpl.java`](../backend/src/main/java/com/legalassistant/service/impl/ChatServiceImpl.java) |
| LightRAG HTTP | [`LightRAGClient.java`](../backend/src/main/java/com/legalassistant/client/LightRAGClient.java) |
| LightRAG 业务 | [`LightRAGServiceImpl.java`](../backend/src/main/java/com/legalassistant/service/impl/LightRAGServiceImpl.java) |
| 配置 | [`LightRAGProperties.java`](../backend/src/main/java/com/legalassistant/config/LightRAGProperties.java)、[`application.yml`](../backend/src/main/resources/application.yml) |
| 门控 | [`LocalConfidenceEvaluator.java`](../backend/src/main/java/com/legalassistant/retrieval/LocalConfidenceEvaluator.java) |
| 融合 | [`KnowledgeFusionServiceImpl.java`](../backend/src/main/java/com/legalassistant/service/impl/KnowledgeFusionServiceImpl.java) |
| 联网 | [`SelfHostedWebSearchServiceImpl.java`](../backend/src/main/java/com/legalassistant/service/impl/SelfHostedWebSearchServiceImpl.java)、[`SearxSearchClient.java`](../backend/src/main/java/com/legalassistant/websearch/SearxSearchClient.java) |
| Thought 来源 | [`ToolResultSourceParser.java`](../backend/src/main/java/com/legalassistant/service/impl/ToolResultSourceParser.java) |
| 前端聊天 | [`ChatView.vue`](../frontend/src/views/ChatView.vue)、[`formatMessage.js`](../frontend/src/utils/formatMessage.js)、[`chat.js`](../frontend/src/api/chat.js) |
| 部署 | [`docker-compose.yml`](../docker-compose.yml)、[`deploy/lightrag/env.example`](../deploy/lightrag/env.example) |
| 基准 | [`benchmark/LightRAG调优记录.md`](./benchmark/LightRAG调优记录.md) |

---

## 12. 建议学习顺序（1–2 天吃透）

1. 读本文 §1–§2，对着 compose 把服务起起来。  
2. 从 `ChatServiceImpl.sendMessageStream` 断点跟一轮标准问。  
3. 精读 `LegalTools.searchLegalKnowledge` → `LightRAGServiceImpl.search` → `formatQueryData`。  
4. 精读 `extractHighLevelKeywords` / cache key，能默写流程。  
5. 再跟一轮「抖音劳动关系」看 `searchWeb` + fusion + cite chip。  
6. 背调优记录里 **T009–T012** 的耗时/准确度与对应改动点。  
7. 用 §10 题库对着代码自问自答。

---

*文档版本：v1.0 | 路径：`docs/lightrag分支技术详解.md` | 对应分支：`lightrag`*
