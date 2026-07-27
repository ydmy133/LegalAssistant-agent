#!/usr/bin/env bash
# Q-LABOR-001 benchmark: 未签劳动合同有什么后果？
# Usage:
#   ./scripts/bench-q-labor-001.sh [base_url] [model_config_id] [runs]
# Example:
#   ./scripts/bench-q-labor-001.sh http://127.0.0.1:8080 1 3
set -euo pipefail

BASE_URL="${1:-http://127.0.0.1:8080}"
MODEL_CONFIG_ID="${2:-1}"
RUNS="${3:-1}"
QUESTION="未签劳动合同有什么后果？"
RESULTS_DIR="${RESULTS_DIR:-/tmp/bench-q-labor-001}"
mkdir -p "${RESULTS_DIR}"

TOKEN=$(curl -s -X POST "${BASE_URL}/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"123123"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["token"])')

TIMES=()
echo "Q-LABOR-001 | base=${BASE_URL} | modelConfigId=${MODEL_CONFIG_ID} | runs=${RUNS}"
echo "----------------------------------------"

for ((i=1; i<=RUNS; i++)); do
  SESSION_ID="bench-$(date +%s)-${i}"
  OUT="${RESULTS_DIR}/${SESSION_ID}.txt"
  echo "Run ${i}/${RUNS} session=${SESSION_ID}"
  START=$(date +%s.%N)
  curl -sN -X POST "${BASE_URL}/api/chat/stream" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "{\"sessionId\":\"${SESSION_ID}\",\"content\":\"${QUESTION}\",\"modelConfigId\":${MODEL_CONFIG_ID}}" \
    --max-time 300 > "${OUT}"
  END=$(date +%s.%N)
  ELAPSED=$(python3 -c "print(f'{$END - $START:.1f}')")
  TIMES+=("${ELAPSED}")
  CHARS=$(wc -c < "${OUT}" | tr -d ' ')
  echo "  elapsed=${ELAPSED}s bytes=${CHARS} file=${OUT}"
  # brief cooldown to reduce rate-limit noise
  if (( i < RUNS )); then sleep 2; fi
done

AVG=$(python3 - <<PY
times = [float(x) for x in """${TIMES[*]}""".split()]
print(f"{sum(times)/len(times):.1f}")
PY
)
echo "----------------------------------------"
echo "Times: ${TIMES[*]} s"
echo "Average: ${AVG} s"
echo "Average=${AVG}" > "${RESULTS_DIR}/last-summary.txt"
printf '%s\n' "${TIMES[@]}" > "${RESULTS_DIR}/last-times.txt"
