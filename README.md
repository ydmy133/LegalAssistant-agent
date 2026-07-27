# LegalAssistant-agent

基于 **LangChain4j + OpenAI + Milvus** 的智能法律助手 Agent 平台，覆盖 RAG 检索、Agent 工具调用、多模型支持、对话记忆等 AI 应用核心模式。

## 技术栈

| 组件 | 技术 | 版本 |
|------|------|------|
| 语言 & 框架 | Java + Spring Boot | JDK 17 / Spring Boot 3.2.7 |
| AI 框架 | LangChain4j Spring Boot Starter | 1.0.0-beta5 |
| LLM | OpenAI (兼容 API) | gpt-4o-mini / text-embedding-3-small |
| 多模型支持 | DeepSeek / 智谱 / Moonshot / 通义千问 / SiliconFlow | 用户自行配置 API Key |
| 向量数据库 | Milvus (standalone) 或 LightRAG | Milvus 2.4+ / LightRAG latest |
| Embedding（LightRAG） | Ollama **bge-m3** | 1024 维 |
| 关系数据库 | MySQL | 8.0 |
| 缓存 & 会话 | Redis | 7.x |
| ORM | MyBatis-Plus | 3.5.7 |
| 文档解析 | Apache Tika | 3.0.0 |
| 认证 | JJWT + BCrypt | 0.12.3 |
| 前端 | Vue 3 + Element Plus + Pinia + Axios | Vue 3.5 / Element Plus 2.14 |

## 功能

- **用户认证** — 注册/登录，JWT 鉴权，BCrypt 密码加密，对话历史按用户隔离
- **多模型支持** — 用户自行配置 API Key，支持 OpenAI / DeepSeek / 智谱 / Moonshot / 通义千问 / SiliconFlow 及任意 OpenAI 兼容接口，提问时自由切换模型
- **法律文档上传** — 支持 PDF/DOCX/TXT/MD，自动解析、分块、向量化存入 Milvus
- **RAG 检索增强** — 基于用户问题检索知识库，将相关文档片段注入 Agent 上下文
- **法律问答** — 自然语言法律咨询，Agent 自主检索知识库后作答
- **案件分析** — 录入案件信息，Agent 检索知识库 + 判例库后给出综合分析意见
- **Agent 工具调用** — 4 个 `@Tool` 方法，Agent 根据用户意图自主决策调用哪个工具
- **对话记忆** — 侧边栏历史会话列表，`@MemoryId` 管理上下文窗口，MySQL 持久化消息
- **流式输出** — SSE (Server-Sent Events) 实时逐字返回 Agent 回答

## 项目结构

```
├── backend/                             # Spring Boot 后端
│   ├── src/main/java/com/legalassistant/
│   │   ├── LegalAssistantApplication.java
│   │   ├── agent/
│   │   │   ├── LegalAssistantAgent.java  # @AiService — 静态 Agent (AgentController 使用)
│   │   │   ├── ChatAgent.java            # 程序化 Agent 接口 (ChatController 动态模型使用)
│   │   │   └── LegalTools.java           # @Tool — Agent 可调用的 4 个工具方法
│   │   ├── common/
│   │   │   ├── JwtUtils.java             # JWT 生成/验证/解析
│   │   │   └── UserContext.java          # ThreadLocal 持有当前用户 ID
│   │   ├── config/
│   │   │   ├── LangChain4jConfig.java    # ContentRetriever Bean
│   │   │   ├── MilvusConfig.java         # MilvusEmbeddingStore Bean
│   │   │   ├── MyBatisPlusConfig.java    # 自动填充 createTime/updateTime
│   │   │   ├── SecurityConfig.java       # BCryptPasswordEncoder Bean
│   │   │   └── WebConfig.java            # CORS + JWT 拦截器
│   │   ├── controller/
│   │   │   ├── AgentController.java      # 统一 Agent 对话入口 (静态模型)
│   │   │   ├── AuthController.java       # 注册 / 登录 / 当前用户
│   │   │   ├── CaseController.java       # 案件 CRUD + 分析
│   │   │   ├── ChatController.java       # 对话管理 (动态模型, SSE 流式)
│   │   │   ├── DocumentController.java   # 文档上传/列表/删除 (用户隔离)
│   │   │   └── ModelConfigController.java # 用户模型配置 CRUD
│   │   ├── dto/                          # 请求/响应 DTO (7 个)
│   │   ├── entity/
│   │   │   ├── User.java                 # 用户
│   │   │   ├── UserModelConfig.java      # 模型配置 (API Key/端点/模型名)
│   │   │   ├── Document.java             # 法律文档元数据
│   │   │   ├── Conversation.java          # 对话会话
│   │   │   ├── Message.java              # 聊天消息
│   │   │   └── LegalCase.java            # 法律案件
│   │   ├── exception/
│   │   ├── interceptor/
│   │   │   └── JwtInterceptor.java       # JWT 鉴权拦截器
│   │   ├── mapper/                       # MyBatis-Plus Mapper (6 个)
│   │   ├── service/
│   │   │   ├── ChatService.java / impl/  # 对话 + Agent 调用 (动态模型)
│   │   │   ├── DocumentService.java / impl/
│   │   │   ├── CaseService.java / impl/
│   │   │   ├── ModelConfigService.java / impl/
│   │   │   ├── ModelService.java / impl/  # 动态 ChatModel 工厂
│   │   │   ├── RAGService.java / impl/
│   │   │   └── UserService.java / impl/
│   │   └── vo/Result.java
│   └── sql/init.sql                      # 6 张表
│
├── frontend/                             # Vue 3 前端
│   └── src/
│       ├── api/
│       │   ├── request.js                # Axios 封装 + JWT 拦截器
│       │   ├── auth.js                   # login() / register()
│       │   ├── chat.js                   # sendMessage() / getSessions() / getMessages()
│       │   ├── document.js               # uploadDocument() / getDocuments()
│       │   └── modelConfig.js            # 模型配置 CRUD
│       ├── store/user.js                 # Pinia 用户状态
│       ├── router/index.js               # 路由 + 导航守卫
│       └── views/
│           ├── Login.vue                 # 登录页
│           ├── Register.vue              # 注册页
│           ├── Layout.vue                # 侧边栏 + 顶栏布局
│           ├── ChatView.vue              # 聊天主界面 (模型选择 + 消息 + 上传)
│           ├── DocumentsView.vue          # 文档管理页
│           └── SettingsView.vue          # 模型配置页
│
└── docker-compose.yml                    # MySQL + Redis + Milvus
```

## 核心设计

### 多模型动态切换

```
用户配置模型 → MySQL user_model_config (provider, api_key, base_url, model_name)
                     │
用户提问 → ChatController → ModelService.buildChatModel(configId)
                     │
          OpenAiChatModel.builder()
              .apiKey(config.apiKey)
              .baseUrl(config.baseUrl)    ← 指向 DeepSeek/Zhipu/... 端点
              .modelName(config.modelName)
              .build()
                     │
          AiServices.builder(ChatAgent.class)
              .chatModel(dynamicModel)     ← 每次请求动态构建
              .tools(legalTools)           ← @Tool 自动发现
              .chatMemory(windowMemory)
              .build()
```

支持的模型提供商（预设 base URL）：

| 提供商 | 默认模型 | API 端点 |
|--------|----------|----------|
| OpenAI | gpt-4o-mini | `https://api.openai.com/v1` |
| DeepSeek | deepseek-chat | `https://api.deepseek.com/v1` |
| 智谱 (Zhipu) | glm-4-flash | `https://open.bigmodel.cn/api/paas/v4` |
| Moonshot | moonshot-v1-8k | `https://api.moonshot.cn/v1` |
| 通义千问 | qwen-plus | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| SiliconFlow | — | `https://api.siliconflow.cn/v1` |
| 自定义 | — | 用户自行填写 |

所有兼容 OpenAI API 格式的第三方模型均可通过配置 `base_url` 接入。

### RAG 管道

```
文档上传 → Apache Tika 解析文本
       → DocumentSplitters.recursive(500, 50) 分块
       → OpenAI text-embedding-3-small 向量化
       → MilvusEmbeddingStore 持久化

用户提问 → embeddingModel.embed(query)
       → embeddingStore.search(embedding, maxResults=5, minScore=0.7)
       → 将 top-K 文本片段注入 Agent 对话上下文
```

### Agent 工具调用

Agent 使用 LangChain4j 的 Function Calling 机制，4 个工具方法由 Spring 自动发现：

| 工具方法 | 触发场景 | 数据来源 |
|----------|----------|----------|
| `searchLegalKnowledge` | 法律知识问答 | Milvus 向量检索 |
| `searchCases` | 判例/先例搜索 | MySQL legal_case 表 |
| `getCaseDetail` | 查看具体案件详情 | MySQL legal_case 表 |
| `getConversationHistory` | 需要历史上下文 | MySQL message 表 |

### 认证与隔离

```
用户注册 → BCrypt 加密密码 → MySQL user 表
用户登录 → 验证密码 → 返回 JWT (7天有效期)
后续请求 → Authorization: Bearer <token>
         → JwtInterceptor 验证 → UserContext.setUserId()
         → Controller 读取 userId → 查询/操作仅限当前用户数据
```

对话、文档、模型配置均按 `user_id` 隔离，用户间数据不可见。

### 数据流

```
用户 → Vue 3 前端 (localhost:3000)
     → /api/auth/**          → AuthController (无需认证)
     → /api/chat/**           → JwtInterceptor → ChatController
     → /api/documents/**      → JwtInterceptor → DocumentController
     → /api/model-configs/**  → JwtInterceptor → ModelConfigController
     → /api/cases/**          → JwtInterceptor → CaseController
     → /api/agent/**          → JwtInterceptor → AgentController
                                    │
     ChatController → ModelService (动态构建 ChatModel)
                    → AiServices (程序化 Agent + LegalTools)
                    → RAGService (Milvus 向量检索)
                    → MySQL (消息持久化)
```

## 数据库

执行 `backend/sql/init.sql` 创建 6 张表：

| 表名 | 说明 |
|------|------|
| `user` | 用户 (id, username, password-bcrypt, email, phone) |
| `user_model_config` | 模型配置 (user_id, provider, model, api_key, base_url) |
| `document` | 上传的法律文档元数据（标题、文件路径、分块数、状态、所属用户） |
| `conversation` | 对话会话（session_id UUID、标题、所属用户 FK） |
| `message` | 聊天消息（角色 user/assistant、内容、时间） |
| `legal_case` | 法律案件（案号、法院、类型、当事人、摘要、判决日期） |

Milvus 集合 `legal_docs` 存储文档向量（维度 1536），携带元数据 `document_id`、`file_name`。

## 快速开始

### 方式 A：Docker 开发模式（改代码免重建镜像）

挂载源码，前端 Vite 热更新，后端 `spring-boot:run` + DevTools 自动重启：

```bash
# 一键启动（MySQL/Redis/Milvus + 开发态前后端）
./scripts/dev-up.sh

# 或手动
docker-compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build

# 停止
./scripts/dev-down.sh
```

| 改动类型 | 是否需要重建镜像 |
|----------|----------------|
| 改 `.vue` / `.js` | 否，浏览器自动刷新 |
| 改 Java 源码 | 否，DevTools 约数秒内重启 |
| 改 `pom.xml` / `package.json` | 重启对应容器即可（`docker-compose ... restart backend`） |
| 改 `Dockerfile.*` / compose 本身 | 需要 `up --build` |

切回生产镜像部署：

```bash
docker-compose -f docker-compose.yml -f docker-compose.dev.yml down
docker-compose up -d --build backend frontend
```

### 方式 B：本地直接跑（最快）

### 1. 启动基础设施

```bash
# 启动 MySQL + Redis + Milvus（默认 RAG provider=milvus）
docker-compose up -d

# 初始化数据库表
mysql -h 127.0.0.1 -u root -p123123 < backend/sql/init.sql
```

### 可选：启用 LightRAG（图谱 + 向量混合检索）

LightRAG 使用 Ollama `bge-m3` 做 Embedding，DeepSeek OpenAI 兼容 API 做实体/关系抽取。与 Milvus 可并存，通过 `LEGAL_RAG_PROVIDER` 切换。

```bash
# 1) 复制环境模板并填写 DeepSeek Key（勿提交 .env）
cp deploy/lightrag/env.example deploy/lightrag/.env
# 编辑 deploy/lightrag/.env：填入 LLM_BINDING_API_KEY
# 或在 shell 中导出：
export DEEPSEEK_API_KEY="sk-..."
export LIGHTRAG_API_KEY="change-me-lightrag-api-key"

# 2) 启动 Ollama + 拉取 bge-m3 + LightRAG（首次拉取模型可能需数分钟）
docker-compose up -d ollama ollama-init lightrag

# 3) 健康检查
curl -s http://127.0.0.1:9621/health
curl -s http://127.0.0.1:11434/api/tags | grep bge-m3

# 4) 后端切换到 LightRAG
export LEGAL_RAG_PROVIDER=lightrag
export LIGHTRAG_BASE_URL=http://localhost:9621
export LIGHTRAG_API_KEY=change-me-lightrag-api-key
# 若用 Docker backend：
# LEGAL_RAG_PROVIDER=lightrag docker-compose up -d --build backend
```

说明：

- `legal.rag.provider=milvus`（默认）→ 本地 AllMiniLM + Milvus
- `legal.rag.provider=lightrag` → LightRAG `POST /query/data`（`mode=hybrid`），检索结果交给对话 Agent 生成回答
- 首次对 6 份预置文档建索引会调用 LLM 抽取实体/关系，耗时可能数分钟；启动后看日志中 `LightRAG preset sync` / `track` 进度
- 基准测试脚本：`./scripts/bench-q-labor-001.sh`（问题 `Q-LABOR-001`）

回退 Milvus 基线：

```bash
export LEGAL_RAG_PROVIDER=milvus
# 或 docker-compose 环境变量 LEGAL_RAG_PROVIDER=milvus
```

### 2. 启动后端

```bash
cd backend

# 可选: 设置默认 OpenAI API Key (用于 AgentController 静态模式)
export OPENAI_API_KEY="sk-your-key-here"

# 编译并启动 (默认 8080 端口)
./mvnw compile
./mvnw spring-boot:run
```

> 注意：如果不设置环境变量，ChatController 会使用用户在 Web 界面中配置的 API Key（动态模式），AgentController 仍需要默认 Key。

### 3. 启动前端

```bash
cd frontend
npm install
npm run dev          # → http://localhost:3000
```

### 4. 使用流程

1. 浏览器打开 `http://localhost:3000`，自动跳转到登录页
2. 点击「立即注册」创建账号，登录后进入聊天界面
3. 进入「设置」页面，添加模型配置（如 DeepSeek API Key）
4. 上传法律文档（支持 PDF/DOCX/TXT/MD），自动解析入库
5. 在聊天界面顶部选择模型，输入法律问题，Agent 自动检索知识库后作答
6. 侧边栏可切换历史对话，点击「+ 新对话」创建会话

## API 概览

### 认证
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/register` | 注册 `{"username","password","email","phone"}` |
| POST | `/api/auth/login` | 登录 `{"username","password"}` → JWT |
| GET | `/api/auth/me` | 当前用户信息 |

### 模型配置 (需要认证)
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/model-configs` | 模型配置列表 |
| POST | `/api/model-configs` | 添加配置 `{"providerName","modelName","apiKey","baseUrl","isDefault"}` |
| PUT | `/api/model-configs/{id}` | 更新配置 |
| DELETE | `/api/model-configs/{id}` | 删除配置 |

### 文档管理 (需要认证)
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/documents/upload` | 上传文档 (multipart/form-data) |
| GET | `/api/documents?page=1&size=10` | 文档列表 |
| DELETE | `/api/documents/{id}` | 删除文档 |

### 对话 (需要认证)
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat/send` | 发送消息 `{"sessionId","content","modelConfigId"}` |
| POST | `/api/chat/stream` | SSE 流式响应 |
| GET | `/api/chat/sessions` | 会话列表 |
| GET | `/api/chat/{sessionId}/messages` | 消息历史 |
| DELETE | `/api/chat/{sessionId}` | 删除会话 |
| POST | `/api/chat/new-session` | 创建新会话 |

### 案件 (需要认证)
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/cases` | 创建案件 |
| GET | `/api/cases` | 案件列表 |
| GET | `/api/cases/{id}` | 案件详情 |
| PUT | `/api/cases/{id}` | 更新案件 |
| DELETE | `/api/cases/{id}` | 删除案件 |
| POST | `/api/cases/{id}/analyze` | Agent 分析案件 |

### Agent
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/agent/chat` | 统一 Agent 对话 (静态模型) |

## 技术亮点

- **动态多模型** — 用户自行配置 API Key，运行时动态构建 `OpenAiChatModel`，支持任意 OpenAI 兼容接口
- **程序化 Agent** — `AiServices.builder()` 每次请求动态注入模型，保留 `@Tool` 自动发现和 `@MemoryId` 上下文管理
- **声明式 Agent** — `@AiService` 接口零样板代码，`@Tool` 方法自动注册
- **Function Calling** — Agent 自主决策调用哪个工具，而非硬编码路由
- **原子 RAG** — 文档解析 → 分块 → 向量化 → 存储 → 检索全链路
- **多模态文档** — Apache Tika 统一解析 PDF/DOCX/TXT 等多种格式
- **对话记忆** — `@MemoryId` 自动管理上下文窗口长度，历史消息 MySQL 持久化
- **流式输出** — SSE (Server-Sent Events) 实时逐字返回 Agent 回答
- **统一异常处理** — `@RestControllerAdvice` 全局兜底，`Result<T>` 统一响应格式
- **前后端分离** — Vue 3 + Vite 代理开发，JWT 鉴权，Pinia 状态管理
