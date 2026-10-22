#!/usr/bin/env bash
set -euo pipefail

# ─── Configuration ────────────────────────────────────────────────────────────
REQUESTS="${REQUESTS:-12}"          # total requests to fire
DELAY="${DELAY:-0}"                 # seconds between requests (0 = as fast as possible)
PORT="${PORT:-8081}"                # Spring Boot server port
APP_DIR="$(cd "$(dirname "$0")" && pwd)"

# Model config — all map to same GGUF to measure warm latency; change to test swap
CATALOG_ARGS=(
  --ai-os.model-manager.model-catalog.coding-small=Qwen2.5-7B-Instruct-Q4_K_M.gguf
  --ai-os.model-manager.model-catalog.research-small=Qwen2.5-7B-Instruct-Q4_K_M.gguf
  --ai-os.model-manager.model-catalog.data-small=Qwen2.5-7B-Instruct-Q4_K_M.gguf
  --ai-os.model-manager.model-catalog.writing-small=Qwen2.5-7B-Instruct-Q4_K_M.gguf
)

# ─── Helpers ──────────────────────────────────────────────────────────────────
BASE_URL="http://127.0.0.1:${PORT}"

declare -A PROMPTS=(
  [CODING]="write a Java method to compute factorial iteratively"
  [RESEARCH]="research the differences between ROCm and CUDA on Linux"
  [DATA]="write a SQL query to find the top 10 customers by revenue"
  [WRITING]="draft a short paragraph explaining the benefits of local AI inference"
)
TYPES=(CODING RESEARCH DATA WRITING)

start_app() {
  echo "Starting app on port ${PORT} ..."
  cd "$APP_DIR"
  JAVA_HOME=~/jdk21 PATH=~/jdk21/bin:$PATH \
    ./mvnw spring-boot:run -q \
    -Dspring-boot.run.arguments="--server.port=${PORT} \
      --ai-os.llama.binary-path=/home/knk/models/llama.cpp/build/bin/llama-server \
      --ai-os.llama.port=8099 \
      --ai-os.llama.model-dir=/home/knk/models \
      --ai-os.model-manager.idle-timeout=10m \
      --ai-os.model-manager.idle-check-interval=10m \
      ${CATALOG_ARGS[*]}" \
    > /tmp/loadtest-app.log 2>&1 &
  APP_PID=$!

  echo "Waiting for app (PID ${APP_PID}) to become healthy ..."
  for i in $(seq 1 40); do
    if curl -sf "${BASE_URL}/health" >/dev/null 2>&1; then
      echo "App ready after ${i}s"
      return 0
    fi
    sleep 1
  done
  echo "ERROR: app did not start within 40s"
  kill $APP_PID 2>/dev/null; wait $APP_PID 2>/dev/null
  exit 1
}

stop_app() {
  if [ -n "${APP_PID:-}" ]; then
    echo "Stopping app (PID ${APP_PID}) ..."
    kill $APP_PID 2>/dev/null; wait $APP_PID 2>/dev/null
  fi
}
trap stop_app EXIT

# ─── Main ─────────────────────────────────────────────────────────────────────
start_app

echo ""
echo "═══════════════════════════════════════════════════════════════════════"
echo " Load test: ${REQUESTS} requests, ${DELAY}s delay, mixed types"
echo "═══════════════════════════════════════════════════════════════════════"
echo ""

# CSV header
CSV="/tmp/loadtest-results.csv"
echo "req,type,status,modelLoadMs,inferenceMs,modelSwap" > "$CSV"

total_start=$(date +%s%N)

for i in $(seq 1 "$REQUESTS"); do
  type_idx=$(( (i - 1) % 4 ))
  type="${TYPES[$type_idx]}"
  prompt="${PROMPTS[$type]}"

  wall_start=$(date +%s%N)
  response=$(curl -sf -X POST "${BASE_URL}/task" \
    -H "Content-Type: application/json" \
    -d "{\"prompt\":\"${prompt}\"}" 2>&1) || true
  wall_end=$(date +%s%N)
  wall_ms=$(( (wall_end - wall_start) / 1000000 ))

  # Parse JSON response
  status=$(echo "$response" | jq -r '.dispatchStatus // "ERROR"' 2>/dev/null || echo "ERROR")
  load_ms=$(echo "$response" | jq -r '.modelLoadMs // 0' 2>/dev/null || echo "0")
  infer_ms=$(echo "$response" | jq -r '.inferenceMs // 0' 2>/dev/null || echo "0")
  swap=$(echo "$response" | jq -r '.modelSwap // false' 2>/dev/null || echo "false")

  printf "  [%2d/%d] %-10s  status=%-10s  load=%6dms  infer=%6dms  swap=%-5s  wall=%6dms\n" \
    "$i" "$REQUESTS" "$type" "$status" "$load_ms" "$infer_ms" "$swap" "$wall_ms"

  echo "${i},${type},${status},${load_ms},${infer_ms},${swap}" >> "$CSV"

  if [ "$DELAY" -gt 0 ] 2>/dev/null; then
    sleep "$DELAY"
  fi
done

total_end=$(date +%s%N)
total_wall=$(( (total_end - total_start) / 1000000 ))

echo ""
echo "═══════════════════════════════════════════════════════════════════════"
echo " Summary"
echo "═══════════════════════════════════════════════════════════════════════"

# Compute stats from CSV
awk -F, 'NR>1 && $3=="DISPATCHED" {
  count++
  load_sum+=$4; infer_sum+=$5
  if (count==1 || $4<load_min) load_min=$4
  if (count==1 || $4>load_max) load_max=$4
  if (count==1 || $5<infer_min) infer_min=$5
  if (count==1 || $5>infer_max) infer_max=$5
  swap_count+=($6=="true")
}
END {
  if (count==0) { print "No dispatched requests"; exit }
  printf "  Dispatched requests: %d\n", count
  printf "  Model-swap count:    %d\n", swap_count
  printf "  Load  — min=%dms  max=%dms  avg=%dms\n", load_min, load_max, load_sum/count
  printf "  Infer — min=%dms  max=%dms  avg=%dms\n", infer_min, infer_max, infer_sum/count
}' "$CSV"

echo "  Total wall time: ${total_wall}ms"
echo "  Results CSV: ${CSV}"
echo ""
