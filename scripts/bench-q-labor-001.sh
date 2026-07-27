#!/usr/bin/env bash
# Q-LABOR-001 benchmark: 未签劳动合同有什么后果？
# Usage: ./scripts/bench-q-labor-001.sh [base_url] [model_config_id]
set -euo pipefail

BASE_URL="${1:-http://127.0.0.1:8080}"
MODEL_CONFIG_ID="${2:-1}"
QUESTION="未签劳动合同有什么后果？"

TOKEN=$(curl -s -X POST "${BASE_URL}/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"123123"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["token"])')

SESSION_ID="bench-$(date +%s)"
OUT="/tmp/bench-q-labor-001-${SESSION_ID}.txt"

echo "Running Q-LABOR-001 (modelConfigId=${MODEL_CONFIG_ID})..."
START=$(date +%s.%N)

curl -sN -X POST "${BASE_URL}/api/chat/stream" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"${SESSION_ID}\",\"content\":\"${QUESTION}\",\"modelConfigId\":${MODEL_CONFIG_ID}}" \
  --max-time 300 | tee "${OUT}"

END=$(date +%s.%N)
ELAPSED=$(python3 -c "print(f'{$END - $START:.1f}')")

echo ""
echo "Elapsed: ${ELAPSED}s"
echo "Output saved to ${OUT}"
