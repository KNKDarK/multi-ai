#!/usr/bin/env bash
# GGUF quantization sweep (PERFORMANCE.md item 1).
#
# Benchmarks the SAME model at multiple quant levels on the local machine and
# writes one CSV row per (quant, prompt) with load latency, throughput, and
# VRAM. Quality scoring is done afterwards from the saved outputs (grade each
# quant's answer per prompt against the others; see PERFORMANCE.md).
#
# Usage:
#   ./sweep-quant.sh                                    # defaults below
#   QUA="Q4_K_M Q5_K_M Q8_0" ./sweep-quant.sh
#   BASE=/path/to/model QUA="Q4_K_M" ./sweep-quant.sh
#
# Expects files named like: <BASE>-<QUANT>.gguf  e.g. Qwen2.5-7B-Q4_K_M.gguf
# Results:  benchmark/results-quant.csv + benchmark/outputs/<quant>/<prompt-id>.txt
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
RESULTS_CSV="${RESULTS_CSV:-$HERE/results-quant.csv}"
PROMPTS_CSV="$HERE/quality-prompts.csv"
OUTDIR="$HERE/outputs"

# ─── Config (override via env) ────────────────────────────────────────────────
LLAMA_BENCH="${LLAMA_BENCH:-llama-bench}"       # llama.cpp benchmark binary
LLAMA_SERVER="${LLAMA_SERVER:-llama-server}"    # llama.cpp server binary
BASE="${BASE:-Qwen2.5-7B-Instruct}"             # model basename (without quant tag)
QUA="${QUA:-Q4_K_M Q5_K_M Q8_0}"                # quant levels to sweep
MODEL_DIR="${MODEL_DIR:-$HOME/models}"
PORT="${PORT:-8099}"
GPU_LAYERS="${GPU_LAYERS:--1}"                  # -1 = all layers on GPU; tune after flags sweep
THREADS="${THREADS:-$(nproc)}"
CTX="${CTX:-8192}"
ROCM_SMI="${ROCM_SMI:-rocm-smi}"

mkdir -p "$OUTDIR"

echo "=== GGUF quant sweep ==="
echo "base=$BASE quants=$QUA model_dir=$MODEL_DIR"

# ─── Helpers ──────────────────────────────────────────────────────────────────
vram_used_mb() {
  if command -v "$ROCM_SMI" >/dev/null 2>&1; then
    "$ROCM_SMI" --showmeminfo vram --json 2>/dev/null \
      | python3 -c 'import json,sys
data=json.load(sys.stdin)
def walk(n):
    if isinstance(n,dict):
        for k,v in n.items():
            if "vram" in k.lower() and "used" in k.lower():
                try: return int(v)
                except Exception: pass
            r=walk(v)
            if r is not None: return r
    elif isinstance(n,list):
        for c in n:
            r=walk(c)
            if r is not None: return r
    return None
u=walk(data)
print(int(u)//(1024*1024) if u else "")' 2>/dev/null || echo ""
  else
    echo ""
  fi
}

server_ready() {
  curl -sf "http://127.0.0.1:${PORT}/health" >/dev/null 2>&1
}

run_server_bench() {
  local quant="$1" gguf="$2" outdir_quant="$3"

  while IFS=, read -r pid category prompt; do
    [ "$pid" = "id" ] && continue
    # strip surrounding quotes from CSV prompt
    prompt="${prompt%\"}"; prompt="${prompt#\"}"

    local t0 t1 load_ms gen_ms tps vram
    t0=$(date +%s%N)
    "$LLAMA_SERVER" -m "$gguf" --port "$PORT" -ngl "$GPU_LAYERS" -t "$THREADS" -c "$CTX" \
      >/dev/null 2>&1 &
    local srv_pid=$!

    load_ms=""
    for _ in $(seq 1 240); do
      if server_ready; then
        t1=$(date +%s%N)
        load_ms=$(( (t1 - t0) / 1000000 ))
        break
      fi
      kill -0 "$srv_pid" 2>/dev/null || break
      sleep 0.25
    done

    if ! server_ready; then
      echo "${quant},${pid},${category},FAIL,,,," >> "$RESULTS_CSV"
      kill "$srv_pid" 2>/dev/null || true; wait "$srv_pid" 2>/dev/null || true
      continue
    fi

    vram=$(vram_used_mb)

    # timed completion via OpenAI-compatible endpoint
    local body resp
    body=$(python3 - "$prompt" <<'PYEOF'
import json,sys
print(json.dumps({
  "messages":[{"role":"user","content":sys.argv[1]}],
  "temperature":0.0,"max_tokens":512,"stream":False
}))
PYEOF
)
    t0=$(date +%s%N)
    resp=$(curl -s "http://127.0.0.1:${PORT}/v1/chat/completions" \
      -H "Content-Type: application/json" -d "$body") || resp=""
    t1=$(date +%s%N)
    gen_ms=$(( (t1 - t0) / 1000000 ))

    # parse response, write output file, append complete CSV row
    python3 - "$RESULTS_CSV" "$outdir_quant" "$pid" "$category" "$gen_ms" "$vram" "$resp" <<'PYEOF'
import json,sys,os
csv_path, outdir, pid, category, gen_ms, vram, resp = sys.argv[1:8]
quant = os.path.basename(outdir)
try:
    data = json.loads(resp)
    content = data["choices"][0]["message"]["content"]
    usage = data.get("usage", {})
    ct = usage.get("completion_tokens", 0)
    tps = round(ct * 1000.0 / int(gen_ms), 2) if ct and int(gen_ms) > 0 else ""
except Exception:
    content, ct, tps = "", "", ""
status = "OK" if content else "PARSE_FAIL"
with open(os.path.join(outdir, pid + ".txt"), "w") as f:
    f.write(content)
def esc(s):
    s = str(s).replace('"', "'")
    return '"' + s + '"' if ("," in s or "\n" in s) else s
with open(csv_path, "a") as f:
    f.write(f"{quant},{pid},{category},{status},{tps},{ct},{gen_ms},{vram}\n")
PYEOF
    kill "$srv_pid" 2>/dev/null || true; wait "$srv_pid" 2>/dev/null || true
    sleep 2
  done < "$PROMPTS_CSV"
}

# ─── llama-bench per quant (pp512 / tg128 standard rows) ─────────────────────
llama_bench_row() {
  local gguf="$1" quant="$2"
  echo "--- llama-bench: $quant ---"
  "$LLAMA_BENCH" -m "$gguf" -ngl "$GPU_LAYERS" -t "$THREADS" 2>/dev/null \
    | tail -n +2 | while read -r line; do
        echo "${quant},BENCH,${line//,/ }"
      done >> "$RESULTS_CSV" || true
}

# ─── Main ─────────────────────────────────────────────────────────────────────
if [ ! -f "$RESULTS_CSV" ]; then
  echo "quant,prompt_id,category,status,prompt_tps,completion_tokens,gen_ms,vram_used_mb" > "$RESULTS_CSV"
fi

for quant in $QUA; do
  gguf="$MODEL_DIR/${BASE}-${quant}.gguf"
  if [ ! -f "$gguf" ]; then
    echo "SKIP: $gguf not found"
    continue
  fi
  echo ""
  echo "=== $quant ($gguf) ==="
  mkdir -p "$OUTDIR/$quant"
  llama_bench_row "$gguf" "$quant"
  run_server_bench "$quant" "$gguf" "$OUTDIR/$quant"
done

echo ""
echo "Done. Raw rows:      $RESULTS_CSV"
echo "Saved outputs:       $OUTDIR/<quant>/<prompt-id>.txt"
echo "Next: grade outputs per PERFORMANCE.md section 1, then update the decision table."
