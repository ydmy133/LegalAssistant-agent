#!/usr/bin/env bash
# Bootstrap RAGFlow: register admin, API key, DeepSeek + Ollama embedding, dataset.
# Usage:
#   DEEPSEEK_API_KEY=sk-... ./scripts/ragflow-bootstrap.sh [base_url]
# Defaults: Web/API http://127.0.0.1:9380 (API) ; admin@legal.local
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE_URL="${1:-http://127.0.0.1:9380}"
EMAIL="${RAGFLOW_ADMIN_EMAIL:-admin@legal.local}"
PASSWORD="${RAGFLOW_ADMIN_PASSWORD:-LegalAdmin123!}"
NICKNAME="${RAGFLOW_ADMIN_NICKNAME:-LegalAdmin}"
DATASET_NAME="${RAGFLOW_DATASET_NAME:-legal_assistant}"
OLLAMA_BASE="${RAGFLOW_OLLAMA_BASE:-http://host.docker.internal:11434}"
OUT_ENV="${ROOT}/deploy/ragflow/.env"
OUT_ROOT_ENV="${ROOT}/.env.ragflow"

# Prefer explicit env; else reuse DEEPSEEK_API_KEY previously written to deploy/ragflow/.env
if [[ -z "${DEEPSEEK_API_KEY:-}" && -f "${OUT_ENV}" ]]; then
  DEEPSEEK_API_KEY="$(grep -E '^DEEPSEEK_API_KEY=' "${OUT_ENV}" | cut -d= -f2- || true)"
fi
if [[ -z "${DEEPSEEK_API_KEY:-}" ]]; then
  echo "WARN: DEEPSEEK_API_KEY is empty. Bootstrap can still create API key/dataset," >&2
  echo "      but chat model provider setup will be skipped. Export DEEPSEEK_API_KEY=sk-..." >&2
fi

PUBLIC_PEM="${ROOT}/deploy/ragflow/public.pem"
if [[ ! -f "${PUBLIC_PEM}" ]]; then
  cat > "${PUBLIC_PEM}" <<'EOF'
-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEArq9XTUSeYr2+N1h3Afl/
z8Dse/2yD0ZGrKwx+EEEcdsBLca9Ynmx3nIB5obmLlSfmskLpBo0UACBmB5rEjBp
2Q2f3AG3Hjd4B+gNCG6BDaawuDlgANIhGnaTLrIqWrrcm4EMzJOnAOI1fgzJRsOO
UEfaS318Eq9OVO3apEyCCt0lOQK6PuksduOjVxtltDav+guVAA068NrPYmRNabVK
RNLJpL8w4D44sfth5RvZ3q9t+6RTArpEtc5sh5ChzvqPOzKGMXW83C95TxmXqpbK
6olN4RevSfVjEAgCydH6HN6OhtOQEcnrU97r9H0iZOWwbw3pVrZiUkuRD1R56Wzs
2wIDAQAB
-----END PUBLIC KEY-----
EOF
fi

echo "RAGFlow bootstrap | base=${BASE_URL} | email=${EMAIL}"

python3 - <<PY
import base64, json, os, sys, time, urllib.error, urllib.request
from pathlib import Path

from cryptography.hazmat.backends import default_backend
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

BASE = os.environ.get("BASE_URL", "${BASE_URL}")
EMAIL = "${EMAIL}"
PASSWORD = "${PASSWORD}"
NICKNAME = "${NICKNAME}"
DATASET_NAME = "${DATASET_NAME}"
OLLAMA_BASE = "${OLLAMA_BASE}"
DEEPSEEK_API_KEY = os.environ.get("DEEPSEEK_API_KEY", "${DEEPSEEK_API_KEY:-}")
PUBLIC_PEM = Path("${PUBLIC_PEM}").read_text()
OUT_ENV = Path("${OUT_ENV}")
OUT_ROOT = Path("${OUT_ROOT_ENV}")

def rsa_encrypt(password: str) -> str:
    key = serialization.load_pem_public_key(PUBLIC_PEM.encode(), backend=default_backend())
    payload = base64.b64encode(password.encode("utf-8"))
    encrypted = key.encrypt(payload, padding.PKCS1v15())
    return base64.b64encode(encrypted).decode("utf-8")

def http(method, path, body=None, token=None, timeout=60):
    url = BASE.rstrip("/") + path
    data = None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if body is not None:
        data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            auth = resp.headers.get("Authorization")
            return resp.status, (json.loads(raw) if raw else {}), auth
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            payload = json.loads(raw) if raw else {}
        except Exception:
            payload = {"message": raw}
        return e.code, payload, None

def wait_ready(retries=90):
    for i in range(retries):
        try:
            status, payload, _ = http("GET", "/api/v1/system/healthz", timeout=5)
            if status == 200:
                print(f"RAGFlow API ready (attempt {i+1})")
                return
        except Exception as e:
            pass
        # also try root
        try:
            urllib.request.urlopen(BASE, timeout=3)
            print(f"RAGFlow HTTP up (attempt {i+1})")
            return
        except Exception:
            pass
        time.sleep(2)
    raise SystemExit("RAGFlow API not ready")

wait_ready()
enc = rsa_encrypt(PASSWORD)

# Register (ignore if already exists)
status, payload, auth = http("POST", "/api/v1/users", {
    "email": EMAIL, "password": enc, "nickname": NICKNAME
})
print("register:", status, payload.get("code"), payload.get("message", "")[:120])
jwt = auth
if not jwt or (isinstance(payload, dict) and payload.get("code") not in (0, None) and "already" not in str(payload.get("message","")).lower()):
    status, payload, auth = http("POST", "/api/v1/auth/login", {"email": EMAIL, "password": enc})
    print("login:", status, payload.get("code"), payload.get("message", "")[:120])
    if status >= 400 or payload.get("code") not in (0, None):
        raise SystemExit(f"login failed: {payload}")
    jwt = auth or (payload.get("data") or {}).get("access_token")
if not jwt:
    # registration may have returned auth header already
    if auth:
        jwt = auth
    else:
        raise SystemExit("no JWT from register/login")

# Create API token
status, payload, _ = http("POST", "/api/v1/system/tokens", {}, token=jwt)
print("token:", status, payload.get("code"))
if payload.get("code") not in (0, None):
    # list existing
    status, payload, _ = http("GET", "/api/v1/system/tokens", token=jwt)
    tokens = payload.get("data") or []
    if not tokens:
        raise SystemExit(f"create token failed: {payload}")
    api_key = tokens[0].get("token")
else:
    api_key = (payload.get("data") or {}).get("token")
if not api_key:
    raise SystemExit(f"missing api key in response: {payload}")
print("API_KEY acquired")

# Configure models via legacy llm endpoints (Bearer works)
def llm_post(path, body):
    return http("POST", path, body, token=api_key, timeout=120)

# DeepSeek chat (built-in factory)
if DEEPSEEK_API_KEY:
    st, pl, _ = llm_post("/v1/llm/set_api_key", {
        "llm_factory": "DeepSeek",
        "api_key": DEEPSEEK_API_KEY,
    })
    print("DeepSeek set_api_key:", st, pl.get("code"), str(pl.get("message", ""))[:160])
else:
    print("WARN: DEEPSEEK_API_KEY empty — skip DeepSeek provider (parse may still work with naive chunking)")

OLLAMA_INSTANCE = "ollama-local"
EMBD_ID = "bge-m3@Ollama"

def ensure_provider(provider_name):
    st, pl, _ = http("PUT", "/api/v1/providers", {"provider_name": provider_name}, token=api_key)
    msg = str(pl.get("message", ""))
    if pl.get("code") in (0, None) or "already exists" in msg.lower():
        print(f"provider {provider_name}:", st, pl.get("code"), msg[:120])
        return True
    print(f"provider {provider_name} failed:", st, pl)
    return False

def ensure_ollama_embedding():
    """RAGFlow v0.26+ requires a provider *instance* (not legacy add_llm alone)."""
    if not ensure_provider("Ollama"):
        return False

    st, pl, _ = http("GET", "/api/v1/providers/Ollama/instances", token=api_key)
    instances = (pl.get("data") or []) if pl.get("code") in (0, None) else []
    has_instance = any(i.get("instance_name") == OLLAMA_INSTANCE for i in instances)
    if not has_instance:
        st, pl, _ = http("POST", "/api/v1/providers/Ollama/instances", {
            "instance_name": OLLAMA_INSTANCE,
            "api_key": "ollama",
            "base_url": OLLAMA_BASE,
            "model_info": [{
                "model_name": "bge-m3",
                "model_type": ["embedding"],
                "max_tokens": 8192,
            }],
        }, token=api_key, timeout=120)
        print("Ollama instance:", st, pl.get("code"), str(pl.get("message", ""))[:200])
        if pl.get("code") not in (0, None):
            return False
    else:
        print("Ollama instance exists:", OLLAMA_INSTANCE)

    # Legacy add_llm keeps my_llms in sync for older UI paths
    st, pl, _ = llm_post("/v1/llm/add_llm", {
        "llm_factory": "Ollama",
        "llm_name": "bge-m3",
        "model_type": "embedding",
        "api_base": OLLAMA_BASE,
        "api_key": "ollama",
        "max_tokens": 8192,
    })
    print("Ollama bge-m3 (legacy add_llm):", st, pl.get("code"), str(pl.get("message", ""))[:200])
    return True

if not ensure_ollama_embedding():
    raise SystemExit("Ollama embedding provider setup failed")

# List tenant models and set default embedding before dataset creation
st, me, _ = http("GET", "/api/v1/users/me/models", token=api_key)
print("tenant models before:", st, me.get("code"))
tenant = (me.get("data") or {}) if isinstance(me.get("data"), dict) else {}
tenant_id = tenant.get("tenant_id") or tenant.get("id")

st, inst, _ = http("GET", "/api/v1/providers/Ollama/instances", token=api_key)
print("Ollama instances:", st, inst.get("code"), inst.get("data"))
st, models, _ = http("GET", f"/api/v1/providers/Ollama/instances/{OLLAMA_INSTANCE}/models", token=api_key)
print("Ollama instance models:", st, models.get("code"), models.get("data"))

patch = {
    "tenant_id": tenant_id,
    "llm_id": tenant.get("llm_id") or "deepseek-chat@DeepSeek",
    "embd_id": EMBD_ID,
    "asr_id": tenant.get("asr_id") or "",
    "img2txt_id": tenant.get("img2txt_id") or "",
}
for k in ("asr_id", "img2txt_id", "rerank_id", "tts_id"):
    if k in tenant and k not in patch:
        patch[k] = tenant[k]
st, pl, _ = http("PATCH", "/api/v1/users/me/models", patch, token=api_key)
print("set defaults:", st, pl.get("code"), str(pl.get("message", ""))[:160])
if pl.get("code") not in (0, None):
    raise SystemExit(f"set tenant defaults failed: {pl}")

# Create or find dataset
st, pl, _ = http("GET", f"/api/v1/datasets?page=1&page_size=50&name={DATASET_NAME}", token=api_key)
datasets = []
data = pl.get("data")
if isinstance(data, list):
    datasets = data
elif isinstance(data, dict):
    datasets = data.get("datasets") or data.get("docs") or []
dataset_id = None
for d in datasets:
    if d.get("name") == DATASET_NAME:
        dataset_id = d.get("id")
        break
if not dataset_id:
    # Omit embedding_model: tenant embd_id is set above; explicit model name triggers
    # "Instance default not found" on some RAGFlow builds when the instance row is missing.
    st, pl, _ = http("POST", "/api/v1/datasets", {
        "name": DATASET_NAME,
        "chunk_method": "naive",
        "permission": "me",
    }, token=api_key)
    print("create dataset:", st, pl.get("code"), str(pl.get("message", ""))[:160])
    if pl.get("code") not in (0, None):
        raise SystemExit(f"create dataset failed: {pl}")
    dataset_id = (pl.get("data") or {}).get("id")
else:
    print("dataset exists:", dataset_id)

if not dataset_id:
    raise SystemExit("dataset_id missing")

def upsert_env(path: Path, updates: dict):
    lines = []
    if path.exists():
        lines = path.read_text().splitlines()
    keys = set(updates)
    out = []
    seen = set()
    for line in lines:
        if "=" in line and not line.strip().startswith("#"):
            k = line.split("=", 1)[0].strip()
            if k in updates:
                out.append(f"{k}={updates[k]}")
                seen.add(k)
                continue
        out.append(line)
    for k, v in updates.items():
        if k not in seen:
            out.append(f"{k}={v}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(out) + "\n")

updates = {
    "RAGFLOW_API_KEY": api_key,
    "RAGFLOW_DATASET_ID": dataset_id,
    "RAGFLOW_BASE_URL": BASE,
    "DEEPSEEK_API_KEY": DEEPSEEK_API_KEY or "",
}
upsert_env(OUT_ENV, updates)
upsert_env(OUT_ROOT, updates)
print("Wrote", OUT_ENV, "and", OUT_ROOT)
print("RAGFLOW_API_KEY=", api_key)
print("RAGFLOW_DATASET_ID=", dataset_id)
PY
