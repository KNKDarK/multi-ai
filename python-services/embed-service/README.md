# embed-service

Short-lived Python embeddings/RAG microservice, launched on demand as a subprocess
(not a permanent daemon). CPU-only: GPU is reserved for llama.cpp.

- `POST /embed` — `{"text": "..."}` or `{"texts": ["...", "..."]}` → embedding vector(s)
- `GET /health` — `200` once the model is loaded, `503` before
- Model `all-MiniLM-L6-v2` (sentence-transformers) is lazy-loaded on the first
  `/embed` request to keep startup fast.
- Managed with `uv` (venv lives in `.venv`, created by `uv sync`).

## Setup

```bash
cd python-services/embed-service
uv sync
```

## Run standalone

```bash
./run.sh                 # default port 8100
./run.sh --port 8300     # custom port
```

## Smoke test (standalone)

```bash
./run.sh &
curl -s -o /dev/null -w "health before load: %{http_code}\n" http://127.0.0.1:8100/health   # 503

# first /embed triggers the (slow) one-time model download + load
curl -s -X POST http://127.0.0.1:8100/embed -H 'Content-Type: application/json' \
  -d '{"text": "hello world"}'                     # {"embedding":[...],"dimensions":384,"count":1}

curl -s http://127.0.0.1:8100/health                # {"status":"ok","model":"all-MiniLM-L6-v2"}

curl -s -X POST http://127.0.0.1:8100/embed -H 'Content-Type: application/json' \
  -d '{"texts": ["rag", "retrieval", "chunk 1"]}'   # {"embeddings":[...],"dimensions":384,"count":3}

# clean shutdown (SIGTERM is graceful)
kill %1
sleep 1
ps aux | grep embed-service || echo "no embed-service process lingering"
```

## Lifecycle from the Java controller

`EmbedServiceExecutionBackend` (in `ai-os-controller`) spawns `run.sh --port <port>`
via `ProcessBuilder` on first use, waits for `/health`, calls `POST /embed`, and
kills the subprocess when idle (`ai-os.embed.idle-timeout`, default `5m`) or when the
Spring context closes (`@PreDestroy`). So after `shutdown()` no Python process remains.

E2E check:

```bash
cd ai-os-controller
ps aux | grep embed-service || echo "before: no embed-service"
export JAVA_HOME=$HOME/tools/jdk-21.0.12.1+1
setsid nohup ./mvnw spring-boot:run > /tmp/ai-os-run.log 2>&1 < /dev/null &

curl -s -X POST http://localhost:8080/task -H 'Content-Type: application/json' \
  -d '{"prompt": "summarize with embedding-based retrieval"}'   # DISPATCHED, backend output in message
ps aux | grep embed-service    # now running (spawned on first /task)

# to watch idle eviction quickly, start with:
#   ./mvnw spring-boot:run -Dspring-boot.run.arguments=--ai-os.embed.idle-timeout=2s,--ai-os.embed.idle-check-interval=2s
# then ~4s after the last request: ps aux | grep embed-service  -> empty
```