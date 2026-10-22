#!/usr/bin/env bash
# JVM memory footprint measurement for ai-os-controller (PERFORMANCE.md item 3).
#
# Measures RSS (what the OS actually charges against 16 GB) and JVM heap
# (used vs committed) under a synthetic request load, then recommends -Xmx.
#
# Two modes:
#   ./jvm-footprint.sh --pid <PID>              # observe an already-running controller
#   ./jvm-footprint.sh --jar <path-to-jar>      # script launches the app itself
#       [--xmx 512m] [--port 8081] [--requests 12]
#
# The load phase POSTs /task with tiny prompts; responses may take seconds each
# with a real model. Metrics are sampled every second during the whole run.
#
# Output: benchmark/results-jvm.csv + a printed summary with -Xmx guidance.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
RESULTS_CSV="${RESULTS_CSV:-$HERE/results-jvm.csv}"
PORT="${PORT:-8081}"
INTERVAL=1

APP_PID=""
JAR=""
XMX=""
REQUESTS=12

while [ $# -gt 0 ]; do
  case "$1" in
    --pid) APP_PID="$2"; shift 2 ;;
    --jar) JAR="$2"; shift 2 ;;
    --xmx) XMX="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --requests) REQUESTS="$2"; shift 2 ;;
    *) echo "unknown arg: $1"; exit 1 ;;
  esac
done

if [ -z "$APP_PID" ] && [ -z "$JAR" ]; then
  echo "usage: $0 --pid <PID> | --jar <jar> [--xmx 512m] [--port 8081] [--requests 12]"
  exit 1
fi

# Attach works only against a JVM running with the same user; jcmd from the
# controller's own JDK is required.
JCMD="${JCMD:-jcmd}"
JAVA="${JAVA:-java}"

if [ -n "$JAR" ]; then
  echo "=== launching controller (jar=$JAR xmx=${XMX:-default} port=$PORT) ==="
  if [ -n "$XMX" ]; then
    "$JAVA" -Xmx"$XMX" -jar "$JAR" --server.port="$PORT" \
      --spring.main.web-application-type=servlet >/dev/null 2>&1 &
  else
    "$JAVA" -jar "$JAR" --server.port="$PORT" \
      --spring.main.web-application-type=servlet >/dev/null 2>&1 &
  fi
  APP_PID=$!
  # Wait for readiness
  ok=""
  for _ in $(seq 1 120); do
    if curl -sf "http://127.0.0.1:${PORT}/health" >/dev/null 2>&1; then ok=1; break; fi
    sleep 1
  done
  if [ -z "$ok" ]; then
    echo "ERROR: controller did not become healthy on port $PORT"
    kill "$APP_PID" 2>/dev/null || true; exit 1
  fi
  trap 'kill "$APP_PID" 2>/dev/null || true' EXIT
fi

# Resolve the main class number for this PID (needed by jcmd GC.heap_info)
main_class=$("$JCMD" "$APP_PID" 2>/dev/null | awk 'NR==2 {print $2}')
[ -n "$main_class" ] || main_class="$APP_PID"

echo "observing pid=$APP_PID for baseline (${INTERVAL}s interval, 30 samples)"
echo "phase,epoch_s,rss_kb,heap_used_kb,heap_committed_kb" > "$RESULTS_CSV"

sample() {
  local phase="$1"
  local rss heap_line heap_used heap_committed epoch
  rss=$(awk '/VmRSS/ {print $2}' "/proc/$APP_PID/status" 2>/dev/null || echo 0)
  heap_line=$("$JCMD" "$APP_PID" GC.heap_info 2>/dev/null || true)
  # G1 line looks like:  garbage-first heap   total 258048K, used 34560K [...]
  # Match fields positionally relative to the 'total' / 'used' keywords.
  heap_used=$(echo "$heap_line" | awk '/garbage-first heap/ {for(i=1;i<=NF;i++) if($i=="used"){v=$(i+1); gsub(/[^0-9]/,"",v); print v; exit}}' 2>/dev/null || echo 0)
  heap_committed=$(echo "$heap_line" | awk '/garbage-first heap/ {for(i=1;i<=NF;i++) if($i=="total"){v=$(i+1); gsub(/[^0-9]/,"",v); print v; exit}}' 2>/dev/null || echo 0)
  epoch=$(date +%s)
  echo "${phase},${epoch},${rss:-0},${heap_used:-0},${heap_committed:-0}" >> "$RESULTS_CSV"
}

# ─── Phase 1: idle baseline ───────────────────────────────────────────────────
for _ in $(seq 1 30); do sample "idle"; sleep "$INTERVAL"; done

# ─── Phase 2: synthetic load ──────────────────────────────────────────────────
echo "load phase: $REQUESTS sequential /task requests"
declare -A PROMPTS=(
  [CODING]="write a one-line Java method that returns the sum of two integers"
  [DATA]="write a SQL query returning the top 5 customers by revenue"
  [WRITING]="draft a 3-sentence polite email declining a meeting"
  [RESEARCH]="summarize the difference between TCP and UDP in 4 sentences"
)
TYPES=(CODING DATA WRITING RESEARCH)

for i in $(seq 1 "$REQUESTS"); do
  type_idx=$(( (i - 1) % 4 ))
  prompt="${PROMPTS[${TYPES[$type_idx]}]}"
  ( curl -s -X POST "http://127.0.0.1:${PORT}/task" \
      -H "Content-Type: application/json" \
      -d "{\"prompt\":\"$prompt\"}" >/dev/null 2>&1 ) &
  # sample aggressively while the request is in flight
  for _ in $(seq 1 30); do
    sample "load"
    sleep "$INTERVAL"
  done
  wait %1 2>/dev/null || true
done

# ─── Phase 3: post-load settle ────────────────────────────────────────────────
for _ in $(seq 1 20); do sample "settle"; sleep "$INTERVAL"; done

# ─── Summary ──────────────────────────────────────────────────────────────────
echo ""
echo "=== JVM footprint summary ==="
awk -F, 'NR>1 {
  if ($3 > max_rss) max_rss=$3
  if ($4 > max_heap_used) max_heap_used=$4
  if ($5 > max_heap_committed) max_heap_committed=$5
}
END {
  printf "  peak RSS:              %d MB\n", max_rss/1024
  printf "  peak heap used:        %d MB\n", max_heap_used/1024
  printf "  peak heap committed:   %d MB\n", max_heap_committed/1024
  printf "  guidance: -Xmx ~= peak_heap_used * 2 (headroom for GC), rounded up to 128MB steps\n"
  printf "  suggested: -Xmx%dm\n", int((max_heap_used/1024 * 2 + 127)/128)*128
}' "$RESULTS_CSV"
echo ""
echo "Raw data: $RESULTS_CSV"
echo "Update PERFORMANCE.md section 3 with these numbers (before/after -Xmx change)."
