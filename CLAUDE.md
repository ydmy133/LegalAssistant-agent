# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Development Commands

All commands run from `backend/`:

```bash
# Build & compile
./mvnw compile
./mvnw package

# Run the app (MySQL, Redis, Ollama, RAGFlow must be running)
export OPENAI_API_KEY="sk-..."
export RAGFLOW_API_KEY="..."
export RAGFLOW_DATASET_ID="..."
./mvnw spring-boot:run

# Run tests
./mvnw test
```

**Infrastructure** (from repo root):
```bash
docker compose up -d                                    # MySQL + Redis + Ollama
cd deploy/ragflow && cp .env.example .env && docker compose --env-file .env up -d   # RAGFlow v0.26.4
# Bootstrap API key + dataset (needs DEEPSEEK_API_KEY for chat model provider):
DEEPSEEK_API_KEY=sk-... ./scripts/ragflow-bootstrap.sh
mysql -u root -p123123 < backend/sql/init.sql         # Initialize database tables
```

## Architecture

The app is a LangChain4j Agent over legal documents and cases. The key paths:

### Agent + Tool Calling (the core)

```
@AiService interface (LegalAssistantAgent)
    │
    ▼
OpenAI ChatModel (gpt-4o-mini with function calling)
    │  LLM decides WHEN to call tools
    ▼
@Tool methods in LegalTools (auto-discovered by Spring)
    ├── searchLegalKnowledge() → RAGService (RAGFlowServiceImpl) → RAGFlow /api/v1/retrieval
    ├── searchCases() → MySQL legal_case LIKE query
    ├── getCaseDetail() → MySQL legal_case by ID
    └── getConversationHistory() → MySQL message history
```

**Critical**: `@AiService` discovers `@Tool`-annotated methods on any Spring `@Component`/`@Service` bean automatically. The `@SystemMessage` on the interface tells the LLM what tools are available and when to use them. The `@MemoryId` parameter triggers LangChain4j to maintain a per-session `ChatMemory` (in-memory by default).

### RAG Pipeline (RAGFlow)

```
Document upload (MultipartFile)
 → persist file + MySQL document row
 → RAGFlowClient.uploadDocument → parse chunks → poll until done
 → RAGFlowSyncSeeder ensures preset docs are indexed on startup

Query (via @Tool searchLegalKnowledge)
 → RAGFlowClient.retrieve() → POST /api/v1/retrieval (chunks only)
 → format chunks as TextSegment list for the LLM
 → keyword fallback on preset markdown if RAGFlow fails
```

Document display name convention: `legal-doc:{mysqlDocumentId}:{fileName}`.

### Conversation Memory

- **Hot memory**: LangChain4j `ChatMemory` keyed by `@MemoryId(sessionId)` — automatically manages context window for the agent
- **Cold storage**: MySQL `conversation` + `message` tables — persists history for browsing past sessions
- `ChatServiceImpl.sendMessage()` saves user + assistant messages to MySQL after each agent call
- First user message in a conversation becomes its title

### LangChain4j Version Caution

This project uses **1.0.0-beta5** (the `langchain4j-spring-boot-starter` version), not a GA release. Key API differences from newer docs/guides:
- `Metadata` uses typed accessors (`getString()`, `getInteger()`) — no generic `get()` or `getOrDefault()`
- `TextSegment.text()` returns `String`, `TextSegment.metadata()` returns `Metadata`

### Configuration Wiring

- `RAGFlowProperties` + `RAGFlowClient` call RAGFlow HTTP API (`RAGFLOW_BASE_URL`, `RAGFLOW_API_KEY`, `RAGFLOW_DATASET_ID`)
- `RAGFlowServiceImpl` is the sole `RAGService` implementation
- RAGFlow runs as an **independent** compose stack under `deploy/ragflow/` (host ports Web 9385 / API 9380 / MySQL 5455 / Redis 6380 / ES 1200)
- LangChain4j auto-configures `OpenAiChatModel`, `OpenAiStreamingChatModel` beans from `application.yml` — no manual config needed for chat
- `MyBatisPlusConfig` implements `MetaObjectHandler` for `createTime`/`updateTime` auto-fill on entity insert/update
- `WebConfig` enables CORS for all origins (dev mode)

### Service Layer Pattern

All services use Interface/Impl split with `@Service` + `@RequiredArgsConstructor` (Lombok constructor injection). Dependencies are `private final`. Business errors throw `BusinessException(message)` which maps to HTTP 400 via `GlobalExceptionHandler`. MyBatis-Plus `LambdaQueryWrapper<T>` is used for type-safe queries (no raw SQL strings).

### File Upload Constraints

`application.yml` limits multipart uploads to 50MB per file / 100MB per request. Supported document extensions: `pdf`, `docx`, `doc`, `txt`, `md`. Files are stored to `file.upload-dir` (default `./uploads`) with UUID filenames.
