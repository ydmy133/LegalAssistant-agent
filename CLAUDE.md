# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Development Commands

All commands run from `backend/`:

```bash
# Build & compile
./mvnw compile
./mvnw package

# Run the app (MySQL, Redis, Milvus must be running)
export OPENAI_API_KEY="sk-..."
./mvnw spring-boot:run

# Run tests
./mvnw test
```

**Infrastructure** (from repo root):
```bash
docker compose up -d                          # Start MySQL + Redis + Milvus
mysql -u root -p123123 < backend/sql/init.sql # Initialize database tables
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
    ├── searchLegalKnowledge() → RAGService → Milvus vector search
    ├── searchCases()          → MySQL legal_case LIKE query
    ├── getCaseDetail()        → MySQL legal_case by ID
    └── getConversationHistory() → MySQL message history
```

**Critical**: `@AiService` discovers `@Tool`-annotated methods on any Spring `@Component`/`@Service` bean automatically. The `@SystemMessage` on the interface tells the LLM what tools are available and when to use them. The `@MemoryId` parameter triggers LangChain4j to maintain a per-session `ChatMemory` (in-memory by default).

### RAG Pipeline

```
Document upload (MultipartFile)
    → ApacheTikaDocumentParser extracts text from PDF/DOCX/TXT
    → DocumentSplitters.recursive(500 chars, 50 overlap) chunks
    → OpenAiEmbeddingModel (text-embedding-3-small, dim=1536) embeds
    → MilvusEmbeddingStore.addAll() persists to Milvus collection "legal_docs"

Query (via @Tool searchLegalKnowledge)
    → embeddingModel.embed(query)
    → embeddingStore.search(EmbeddingSearchRequest maxResults=5, minScore=0.7)
    → returns List<TextSegment> formatted for the LLM
```

Each `TextSegment` carries metadata (`document_id`, `file_name`) set during ingestion. The `getString("file_name")` accessor is used because `Metadata` exposes typed accessors (`getString`, `getInteger`, etc.) not generic `get()`.

### Conversation Memory

- **Hot memory**: LangChain4j `ChatMemory` keyed by `@MemoryId(sessionId)` — automatically manages context window for the agent
- **Cold storage**: MySQL `conversation` + `message` tables — persists history for browsing past sessions
- `ChatServiceImpl.sendMessage()` saves user + assistant messages to MySQL after each agent call
- First user message in a conversation becomes its title

### LangChain4j Version Caution

This project uses **1.0.0-beta5** (the `langchain4j-spring-boot-starter` version), not a GA release. Key API differences from newer docs/guides:
- `Metadata` uses typed accessors (`getString()`, `getInteger()`) — no generic `get()` or `getOrDefault()`
- `EmbeddingStore.removeAll()` accepts `Collection<String>` or `Filter`, not a raw String expression
- `DocumentSplitters.recursive(int chars, int overlap)` uses character-based splitting (no `Tokenizer` parameter in this version)
- `TextSegment.text()` returns `String`, `TextSegment.metadata()` returns `Metadata`

### Configuration Wiring

- `MilvusConfig` manually creates `MilvusEmbeddingStore` bean (host/port/collection/dimension from `application.yml`)
- `LangChain4jConfig` creates `ContentRetriever` bean — this is for the "always-on RAG" pattern (injecting context before every request). The current agent uses **tool-based RAG** instead (agent decides when to call `searchLegalKnowledge`), so this bean is present but not currently wired into the agent.
- LangChain4j auto-configures `OpenAiChatModel`, `OpenAiStreamingChatModel`, `OpenAiEmbeddingModel` beans from `application.yml` properties — no manual config needed
- `MyBatisPlusConfig` implements `MetaObjectHandler` for `createTime`/`updateTime` auto-fill on entity insert/update
- `WebConfig` enables CORS for all origins (dev mode)

### Service Layer Pattern

All services use Interface/Impl split with `@Service` + `@RequiredArgsConstructor` (Lombok constructor injection). Dependencies are `private final`. Business errors throw `BusinessException(message)` which maps to HTTP 400 via `GlobalExceptionHandler`. MyBatis-Plus `LambdaQueryWrapper<T>` is used for type-safe queries (no raw SQL strings).

### File Upload Constraints

`application.yml` limits multipart uploads to 50MB per file / 100MB per request. Supported document extensions: `pdf`, `docx`, `doc`, `txt`, `md`. Files are stored to `file.upload-dir` (default `./uploads`) with UUID filenames.
