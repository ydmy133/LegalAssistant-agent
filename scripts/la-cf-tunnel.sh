#!/usr/bin/env bash
# LegalAssistant Cloudflare Quick Tunnel 开关
# 用法:
#   la-cf-tunnel on|start|开
#   la-cf-tunnel off|stop|关
#   la-cf-tunnel status|状态
#   la-cf-tunnel restart|重启
#   la-cf-tunnel url|地址
#
# 可选环境变量:
#   LA_CF_URL   本地目标，默认 http://127.0.0.1:3080（生产 nginx，勿用 Vite:3000）

set -euo pipefail

SERVICE="legalassistant-cloudflared"
LOG="/var/log/legalassistant-cloudflared.log"
UNIT_DST="/etc/systemd/system/${SERVICE}.service"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
UNIT_SRC="${SCRIPT_DIR}/legalassistant-cloudflared.service"
LOCAL_URL="${LA_CF_URL:-http://127.0.0.1:3080}"
PUBLIC_CONTAINER="legal-frontend-tunnel"
PUBLIC_PORT="3080"
DOCKER_NETWORK="${LA_CF_NETWORK:-legalassistant-agent_default}"


red()  { printf '\033[31m%s\033[0m\n' "$*"; }
green(){ printf '\033[32m%s\033[0m\n' "$*"; }
yellow(){ printf '\033[33m%s\033[0m\n' "$*"; }

need_root() {
  if [[ "${EUID}" -ne 0 ]]; then
    red "请使用 root 运行，例如: sudo $0 $*"
    exit 1
  fi
}

find_cloudflared() {
  if [[ -x /usr/bin/cloudflared ]]; then
    echo /usr/bin/cloudflared
  elif [[ -x /usr/local/bin/cloudflared ]]; then
    echo /usr/local/bin/cloudflared
  elif command -v cloudflared >/dev/null 2>&1; then
    command -v cloudflared
  else
    return 1
  fi
}

install_unit() {
  local bin
  if ! bin="$(find_cloudflared)"; then
    red "未找到 cloudflared，请先安装: https://developers.cloudflare.com/cloudflare-one/connections/connectors/cloudflared/"
    exit 1
  fi

  if [[ -f "$UNIT_SRC" ]]; then
    sed -e "s|/usr/bin/cloudflared|${bin}|g" \
        -e "s|http://127.0.0.1:3000|${LOCAL_URL}|g" \
        "$UNIT_SRC" > "$UNIT_DST"
  else
    cat > "$UNIT_DST" <<EOF
[Unit]
Description=LegalAssistant Cloudflare Quick Tunnel
After=network-online.target docker.service
Wants=network-online.target

[Service]
Type=simple
ExecStart=${bin} tunnel --no-autoupdate --url ${LOCAL_URL}
Restart=always
RestartSec=5
StandardOutput=append:${LOG}
StandardError=append:${LOG}

[Install]
WantedBy=multi-user.target
EOF
  fi

  touch "$LOG"
  chmod 644 "$UNIT_DST"
  systemctl daemon-reload
  green "已安装 systemd 服务: $SERVICE → $LOCAL_URL"
}

ensure_service() {
  if ! systemctl cat "$SERVICE" >/dev/null 2>&1; then
    yellow "未找到服务 $SERVICE，正在安装..."
    install_unit
  fi
}

# Vite 开发服经 Quick Tunnel 加载大模块易超时白屏；公网走生产构建 + nginx
ensure_public_frontend() {
  if ! command -v docker >/dev/null 2>&1; then
    yellow "未找到 docker，请确认本机已有 ${LOCAL_URL} 可访问。"
    return 0
  fi
  if [[ ! -f "${REPO_ROOT}/frontend/dist/index.html" ]]; then
    red "缺少前端构建产物: ${REPO_ROOT}/frontend/dist"
    yellow "请先执行: cd frontend && npm ci && npm run build"
    exit 1
  fi
  if docker ps --format '{{.Names}}' | grep -qx "$PUBLIC_CONTAINER"; then
    return 0
  fi
  yellow "正在启动公网前端容器 ${PUBLIC_CONTAINER} → :${PUBLIC_PORT} ..."
  docker rm -f "$PUBLIC_CONTAINER" >/dev/null 2>&1 || true
  docker run -d --name "$PUBLIC_CONTAINER" \
    --restart unless-stopped \
    --network "$DOCKER_NETWORK" \
    -p "${PUBLIC_PORT}:80" \
    -v "${REPO_ROOT}/frontend/dist:/usr/share/nginx/html:ro" \
    -v "${REPO_ROOT}/frontend/nginx.conf:/etc/nginx/conf.d/default.conf:ro" \
    nginx:1.27-alpine >/dev/null
  sleep 1
  if curl -fsS -o /dev/null --connect-timeout 3 "http://127.0.0.1:${PUBLIC_PORT}/"; then
    green "公网前端已就绪: http://127.0.0.1:${PUBLIC_PORT}"
  else
    red "公网前端启动失败，请检查 docker 日志: docker logs ${PUBLIC_CONTAINER}"
    exit 1
  fi
}

get_url() {
  if [[ -f "$LOG" ]]; then
    grep -Eo 'https://[a-zA-Z0-9.-]+\.trycloudflare\.com' "$LOG" | tail -1 || true
  fi
}

wait_url() {
  local i url=""
  for i in $(seq 1 25); do
    sleep 1
    url="$(get_url)"
    if [[ -n "$url" ]]; then
      echo "$url"
      return 0
    fi
  done
  return 1
}

cmd_on() {
  need_root
  ensure_public_frontend
  ensure_service
  : > "$LOG"
  systemctl enable "$SERVICE" >/dev/null 2>&1 || true
  systemctl start "$SERVICE"
  green "正在开启 Cloudflare 临时隧道..."
  local url
  if url="$(wait_url)"; then
    green "已开启"
    echo "访问地址: $url"
    yellow "注意: Quick Tunnel 每次启动域名可能变化；首次打开请等待静态资源加载（约十几秒）。"
  else
    yellow "服务已启动，但尚未读到地址，稍后再执行: $0 url"
    systemctl --no-pager --full status "$SERVICE" | head -15 || true
  fi
}

cmd_off() {
  need_root
  ensure_service
  systemctl stop "$SERVICE"
  systemctl disable "$SERVICE" >/dev/null 2>&1 || true
  green "已关闭 Cloudflare 隧道，公网地址不可再访问。"
  yellow "已取消开机自启；下次需要时执行: $0 开"
}

cmd_restart() {
  need_root
  ensure_public_frontend
  ensure_service
  : > "$LOG"
  systemctl enable "$SERVICE" >/dev/null 2>&1 || true
  systemctl restart "$SERVICE"
  green "正在重启 Cloudflare 隧道..."
  local url
  if url="$(wait_url)"; then
    green "已重启"
    echo "新访问地址: $url"
    yellow "注意: 重启后域名可能已变化，请使用新地址。"
  else
    yellow "服务已重启，但尚未读到地址，稍后再执行: $0 url"
  fi
}

cmd_status() {
  ensure_service
  local state
  state="$(systemctl is-active "$SERVICE" 2>/dev/null || true)"
  if [[ "$state" == "active" ]]; then
    green "状态: 开启 ($state)"
    local url
    url="$(get_url)"
    if [[ -n "$url" ]]; then
      echo "访问地址: $url"
    else
      yellow "服务运行中，但日志里还没有地址。"
    fi
  else
    yellow "状态: 关闭 ($state)"
  fi
  systemctl --no-pager --full status "$SERVICE" 2>/dev/null | head -12 || true
}

cmd_url() {
  local state url
  state="$(systemctl is-active "$SERVICE" 2>/dev/null || true)"
  url="$(get_url)"
  if [[ "$state" != "active" ]]; then
    yellow "隧道当前是关闭的。"
    exit 1
  fi
  if [[ -n "$url" ]]; then
    echo "$url"
  else
    yellow "暂未获取到地址，请稍后再试。"
    exit 1
  fi
}

cmd_install() {
  need_root
  install_unit
  green "安装完成。开启: $0 开"
}

usage() {
  cat <<EOF
LegalAssistant Cloudflare 临时隧道开关

用法:
  $0 on|start|开       开启公网地址（指向生产前端 ${LOCAL_URL}）
  $0 off|stop|关       关闭公网地址
  $0 restart|重启      重启（地址可能变化）
  $0 status|状态       查看开关状态
  $0 url|地址          只打印当前访问地址
  $0 install|安装      仅安装/更新 systemd 服务

说明:
  公网隧道使用 nginx 生产构建（:3080），不用 Vite 开发服（:3000），
  避免 element-plus 等大模块经隧道超时导致白屏。

示例:
  sudo $0 开
  sudo $0 状态
  sudo $0 url
  sudo $0 关

可选:
  sudo LA_CF_URL=http://127.0.0.1:8080 $0 install   # 改为直连后端（一般不需要）
EOF
}

main() {
  local action="${1:-}"
  case "$action" in
    on|start|开|开启)     cmd_on ;;
    off|stop|关|关闭)     cmd_off ;;
    restart|重启)         cmd_restart ;;
    status|状态)          cmd_status ;;
    url|地址)             cmd_url ;;
    install|安装)         cmd_install ;;
    -h|--help|help|帮助|"") usage ;;
    *)
      red "未知命令: $action"
      usage
      exit 1
      ;;
  esac
}

main "$@"
