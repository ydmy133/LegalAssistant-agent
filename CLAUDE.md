# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Development Commands

All commands run from `backend/`:

```bash
# Build & compile
./mvnw compile
./mvnw package

# Run the app (MySQL, Redis, Ollama, LightRAG must be running)
export OPENAI_API_KEY="sk-..."
./mvnw spring-boot:run

# Run tests
./mvnw test
```

**Infrastructure** (from repo root):
```bash
cp deploy/lightrag/env.example deploy/lightrag/.env   # fill LLM_BINDING_API_KEY
docker compose up -d                                    # MySQL + Redis + Ollama + LightRAG
# Ollama 默认 OLLAMA_KEEP_ALIVE=-1（bge-m3 常驻）；若曾手动起过旧容器，需：
# docker-compose -f docker-compose.yml up -d --force-recreate ollama
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
    ├── searchLegalKnowledge() → RAGService (LightRAGServiceImpl) → LightRAG /query/data
    ├── searchCases() → MySQL legal_case LIKE query
    ├── getCaseDetail() → MySQL legal_case by ID
    └── getConversationHistory() → MySQL message history
```

**Critical**: `@AiService` discovers `@Tool`-annotated methods on any Spring `@Component`/`@Service` bean automatically. The `@SystemMessage` on the interface tells the LLM what tools are available and when to use them. The `@MemoryId` parameter triggers LangChain4j to maintain a per-session `ChatMemory` (in-memory by default).

### RAG Pipeline (LightRAG)

```
Document upload (MultipartFile)
 → persist file + MySQL document row
 → LightRAGClient.uploadDocument → LightRAG indexes (entities/relations/chunks)
 → LightRAGSyncSeeder ensures preset docs are indexed on startup

Query (via @Tool searchLegalKnowledge)
 → LightRAGClient.queryData(mode=hybrid)
 → format entities / relationships / chunks as TextSegment list for the LLM
 → keyword fallback on preset markdown if LightRAG fails
```

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

- `LightRAGProperties` + `LightRAGClient` call LightRAG HTTP API (`LIGHTRAG_BASE_URL`, `LIGHTRAG_API_KEY`)
- `LightRAGServiceImpl` is the sole `RAGService` implementation
- LangChain4j auto-configures `OpenAiChatModel`, `OpenAiStreamingChatModel` beans from `application.yml` — no manual config needed for chat
- `MyBatisPlusConfig` implements `MetaObjectHandler` for `createTime`/`updateTime` auto-fill on entity insert/update
- `WebConfig` enables CORS for all origins (dev mode)

### Service Layer Pattern

All services use Interface/Impl split with `@Service` + `@RequiredArgsConstructor` (Lombok constructor injection). Dependencies are `private final`. Business errors throw `BusinessException(message)` which maps to HTTP 400 via `GlobalExceptionHandler`. MyBatis-Plus `LambdaQueryWrapper<T>` is used for type-safe queries (no raw SQL strings).

### File Upload Constraints

`application.yml` limits multipart uploads to 50MB per file / 100MB per request. Supported document extensions: `pdf`, `docx`, `doc`, `txt`, `md`. Files are stored to `file.upload-dir` (default `./uploads`) with UUID filenames.
