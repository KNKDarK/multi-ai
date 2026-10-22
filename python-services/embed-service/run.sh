#!/usr/bin/env bash
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PORT=8100
if [[ "${1:-}" == "--port" && -n "${2:-}" ]]; then PORT="$2"; fi

if [[ ! -x "$DIR/.venv/bin/python" ]]; then
  echo "[embed-service] .venv missing, bootstrapping with uv sync..."
  (cd "$DIR" && uv sync)
fi

echo "[embed-service] starting uvicorn on 127.0.0.1:${PORT} (model lazy-loaded on first request)"
cd "$DIR"
exec "$DIR/.venv/bin/python" -m uvicorn embed_server:app --host 127.0.0.1 --port "$PORT"