#!/usr/bin/env bash
# llama.cpp runtime flag sweep for gfx1034 (PERFORMANCE.md item 2).
#
# Measures the effect of individual flag changes against a measured baseline,
# using llama-bench for throughput and the server + health probe for load
# latency. Each candidate must BEAT the baseline on the chosen metric to be
# kept; nothing is adopted on theory (AGENTS.md rule 14).
#
# Usage:
#   MODEL=/path/to/model.gguf ./sweep-flags.sh            # full matrix
#   MODEL=... TRIALS=3 ./sweep-flags.sh                   # more trials per config
#   MODEL=... CFGS="-ngl 0|-ngl 16|-ngl 32|-ngl 99" ./sweep-flags.sh
#
# Results: benchmark/results-flags.csv  (one row per config x trial)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
RESULTS_CSV="${RESULTS_CSV:-$HERE/results-flags.csv}"
MODEL="${MODEL:?Set MODEL=/path/to/model.gguf}"

LLAMA_BENCH="${LLAMA_BENCH:-llama-bench}"
LLAMA_SERVER="${LLAMA_SERVER:-llama-server}"
PORT="${PORT:-8099}"
TRIALS="${TRIALS:-3}"
THREADS="${THREADS:-$(nproc)}"

# Sweep matrix: name|llama-bench args|server args
# Defaults target gfx1034 (RX 6500M, 4GB): find the real GPU-layer ceiling,
# a context size that fits VRAM with headroom, and the thread count sweet spot.
CFGS="${CFGS:-\
baseline|-t ${THREADS}|--threads ${THREADS}|\
ngl-auto|-ngl 99 -t ${THREADS}|--n-gpu-layers 99 --threads ${THREADS}|\
ngl-0|-ngl 0 -t ${THREADS}|--n-gpu-layers 0 --threads ${THREADS}|\
ngl-16|-ngl 16 -t ${THREADS}|--n-gpu-layers 16 --threads ${THREADS}|\
ngl-32|-ngl 32 -t ${THREADS}|--n-gpu-layers 32 --threads ${THREADS}|\
ctx-4096|-c 4096 -t ${THREADS}|--ctx-size 4096 --threads ${THREADS}|\
ctx-8192|-c 8192 -t ${THREADS}|--ctx-size 8192 --threads ${THREADS}|\
ctx-16384|-c 16384 -t ${THREADS}|--ctx-size 16384 --threads ${THREADS}|\
batch-128|-b 128 -ub 64 -t ${THREADS}|--batch-size 128 --ubatch-size 64 --threads ${THREADS}|\
batch-1024|-b 1024 -ub 512 -t ${THREADS}|--batch-size 1024 --ubatch-size 512 --threads ${THREADS}|\
threads-4|-t 4|--threads 4|\
threads-8|-t 8|--threads 8|\
threads-12|-t 12|--threads 12}"

mkdir -p "$HERE"

echo "=== llama.cpp flag sweep (gfx1034) ==="
echo "model=$MODEL trials=$TRIALS"

header="config,trial,metric,value,unit"
if [ ! -f "$RESULTS_CSV" ]; then
  echo "$header" > "$RESULTS_CSV"
fi

# Extract the tg128 throughput value from llama-bench pipe-table output.
# Header:  | model | size | params | backend | test | t/s |
# Data:    | ...   | ...  | ...    | ...     | tg128 | 45.32 ± 0.51 |
bench_tg128() {
  local args="$1"
  "$LLAMA_BENCH" -m "$MODEL" $args 2>/dev/null | awk -F'|' '
    NR==1 { for (i=1;i<=NF;i++) if ($i ~ /t\/s/) col=i; next }
    !done && $0 ~ /tg128/ && col {
      v=$col; gsub(/[[:space:]]/, "", v); split(v, a, "±"); print a[1]; done=1
    }
  ' 2>/dev/null || echo ""
}

server_load_ms() {
  local args="$1"
  "$LLAMA_SERVER" -m "$MODEL" --port "$PORT" $args >/dev/null 2>&1 &
  local pid=$!
  local t0 t1
  t0=$(date +%s%N)
  for _ in $(seq 1 240); do
    if curl -sf "http://127.0.0.1:${PORT}/health" >/dev/null 2>&1; then
      t1=$(date +%s%N)
      kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true
      echo $(( (t1 - t0) / 1000000 ))
      return 0
    fi
    kill -0 "$pid" 2>/dev/null || { wait "$pid" 2>/dev/null || true; echo "FAIL"; return 0; }
    sleep 0.25
  done
  kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true
  echo "TIMEOUT"
}

for entry in $CFGS; do
  IFS='|' read -r name bench_args server_args <<< "$entry"
  echo ""
  echo "--- config: $name ---"
  echo "  bench args:  $bench_args"
  echo "  server args: $server_args"

  # throughput: TR trials of llama-bench tg128
  for t in $(seq 1 "$TRIALS"); do
    v=$(bench_tg128 "$bench_args")
    echo "${name},${t},tg128_tps,${v},t/s" >> "$RESULTS_CSV"
    echo "  tg128 t/s (trial $t): ${v:-n/a}"
  done

  # load latency: TR trials of cold server start to /health 200
  for t in $(seq 1 "$TRIALS"); do
    v=$(server_load_ms "$server_args")
    echo "${name},${t},load_ms,${v},ms" >> "$RESULTS_CSV"
    echo "  load ms  (trial $t): ${v:-n/a}"
    sleep 2
  done
done

echo ""
echo "Done. Results: $RESULTS_CSV"
echo "Summarize with:"
echo "  awk -F, 'NR>1 && \$3==\"tg128_tps\" && \$4!=\"\" {s[\$1]+=\$4; n[\$1]++} END {for (c in s) printf \"%-12s avg_tg128=%.2f t/s\\n\", c, s[c]/n[c]}' $RESULTS_CSV"
echo "  awk -F, 'NR>1 && \$3==\"load_ms\" && \$4!=\"\" {s[\$1]+=\$4; n[\$1]++} END {for (c in s) printf \"%-12s avg_load=%d ms\\n\", c, s[c]/n[c]}' $RESULTS_CSV"
