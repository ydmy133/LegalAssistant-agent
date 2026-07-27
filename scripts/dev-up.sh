#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# 开发态前端跑 Vite(3000)，覆盖生产的 nginx(80) 端口映射
export FRONTEND_CONTAINER_PORT=3000

echo "启动开发环境（源码挂载 + 热更新）..."
docker-compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build

echo ""
echo "前端: http://127.0.0.1:${FRONTEND_HOST_PORT:-3000}  (Vite 热更新)"
echo "后端: http://127.0.0.1:8080  (改 Java 后 DevTools 自动重启，首次启动需编译)"
echo ""
echo "查看日志: docker-compose -f docker-compose.yml -f docker-compose.dev.yml logs -f backend frontend"
