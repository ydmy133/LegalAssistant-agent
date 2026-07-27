#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

docker-compose -f docker-compose.yml -f docker-compose.dev.yml down
