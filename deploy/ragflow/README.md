# RAGFlow 独立部署（LegalAssistant）

基于官方 RAGFlow **v0.26.4**（cpu + elasticsearch profile），与主项目 `docker-compose.yml` **隔离**，避免与 `legal-mysql:3306` / `legal-redis:6379` 端口冲突。

## 主机端口映射

| 服务 | 容器端口 | 主机端口 |
|------|----------|----------|
| Web UI | 80 | **9385** |
| HTTP API | 9380 | **9380** |
| MySQL | 3306 | **5455** |
| Redis | 6379 | **6380** |
| Elasticsearch | 9200 | **1200** |
| MinIO API / Console | 9000 / 9001 | 9000 / 9001 |

## 前置条件

```bash
# Elasticsearch 需要
sudo sysctl -w vm.max_map_count=262144
```

本机需已启动主栈中的 **Ollama + bge-m3**（`docker compose up -d ollama ollama-init`），供 RAGFlow 作 Embedding。

## 启动

```bash
cd deploy/ragflow
cp .env.example .env   # 如尚未有 .env
# 默认使用华为云镜像：swr.cn-north-4.myhuaweicloud.com/infiniflow/ragflow:v0.26.4
# 若失败可改为：RAGFLOW_IMAGE=infiniflow/ragflow:v0.26.4

docker compose -f docker-compose.yml --env-file .env up -d
# 等待 ragflow-cpu / es01 / mysql 健康（首次拉镜像与启动可能需数分钟）
```

Web：http://127.0.0.1:9385  
API：http://127.0.0.1:9380

## Bootstrap（管理员 / API Key / 模型 / Dataset）

推荐使用仓库脚本（读取环境变量中的 DeepSeek Key，**不会提交密钥**）：

```bash
export DEEPSEEK_API_KEY='sk-...'
./scripts/ragflow-bootstrap.sh
```

**Bootstrap 阻塞条件**：若未设置 `DEEPSEEK_API_KEY`，脚本仍可注册管理员并创建 API Key / dataset，但无法配置 DeepSeek 聊天模型；文档解析依赖 Embedding（Ollama `bge-m3`）仍可工作。

脚本会：

1. 注册管理员（默认 `admin@legal.local` / `LegalAdmin123!`）
2. 登录并创建 **API Key**
3. 配置 **DeepSeek** 聊天模型 + **Ollama bge-m3** Embedding（`http://host.docker.internal:11434`）
4. 创建 dataset `legal_assistant`（embedding：`bge-m3@Ollama`）
5. 将 `RAGFLOW_API_KEY` / `RAGFLOW_DATASET_ID` 写入 `deploy/ragflow/.env` 与仓库根 `.env.ragflow`

也可手动：打开 Web → 注册 → Model providers 配置 DeepSeek / Ollama → 创建知识库 → API Key。

## 对接 Java 后端

主 `docker-compose.yml` 中 backend 环境变量：

```bash
RAGFLOW_BASE_URL=http://host.docker.internal:9380
RAGFLOW_API_KEY=<from bootstrap>
RAGFLOW_DATASET_ID=<from bootstrap>
```

本地 `./mvnw spring-boot:run` 时默认 `http://localhost:9380`。

文档显示名约定：`legal-doc:{mysqlDocumentId}:{fileName}`。  
检索仅调用 `POST /api/v1/retrieval`（取 chunks，由 Agent LLM 生成最终回答）。

## 停止

```bash
cd deploy/ragflow
docker compose -f docker-compose.yml --env-file .env down
```
