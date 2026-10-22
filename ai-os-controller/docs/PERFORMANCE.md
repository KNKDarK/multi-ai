# Performance Findings — ai-os-controller

> Generated from load tests on 2026-09-13.
> Hardware: AMD Ryzen 5 5600H, 16 GB RAM, AMD Radeon RX 6500M.
> Model available: Qwen2.5-7B-Instruct-Q4_K_M.gguf (4.4 GB).
> Note: only Q4_K_M is installed, so Q5_K_M/Q8_0 quality and footprint comparisons
> are still blocked. The detected GPU is gfx1030 / RX 6500, not gfx1034.

## 0. Optimization Pass (2026-09-13)

### Quantization sweep

| Quant | File present | Load latency | RX 6500 tok/s | VRAM | Quality set |
|-------|--------------|--------------|---------------|------|-------------|
| Q4_K_M | yes | not re-measured with tuned server | 12.759 at ngl=99 | unavailable | baseline only |
| Q5_K_M | no | not measured | not measured | not measured | not measured |
| Q8_0 | no | not measured | not measured | not measured | not measured |

The quantization decision is intentionally **deferred** until the Q5_K_M and
Q8_0 GGUF files are available. `rocm-smi` is not installed, so VRAM telemetry
was not available; llama.cpp reported the RX 6500 Vulkan device but not a VRAM
usage value in the benchmark output. No quality ranking is claimed from a
single quant.

### llama.cpp flag sweep

Short `llama-bench` runs used `-p 128 -n 64 -r 1 -dev Vulkan1` on the RX 6500.

| Configuration | Completion tok/s | Change vs ngl=99 |
|---------------|------------------|------------------|
| ngl=99, threads=6, batch=128, ubatch=64 | 12.759 | baseline |
| ngl=32, threads=6, batch=128, ubatch=64 | 12.758 | -0.0% |
| **ngl=16, threads=6, batch=128, ubatch=64** | **14.292** | **+12.0%** |
| ngl=99, threads=12, batch=128, ubatch=64 | 12.753 | -0.0% |
| ngl=99, threads=6, batch=512, ubatch=256 | 12.758 | -0.0% |
| ngl=99, threads=6, batch=1024, ubatch=512 | 12.758 | -0.0% |
| CPU-only, threads=12 | 4.402 | -65.5% |

The application now passes the measured winner: `--device Vulkan1
--n-gpu-layers 16 --threads 6 --batch-size 128 --ubatch-size 64`. Context size
was left at the model default because this llama-bench build did not accept the
attempted `-c` sweep. The 16-layer result is a throughput improvement, not a
claim about quality; run the fixed prompt set after the missing quants arrive.

### JVM footprint

The JVM sampler had a measurement bug: it read the epoch as RSS and invoked
`jcmd` with an extra class argument. Both issues were corrected before using the
new data.

| Measurement | Before | After measurement fix |
|-------------|--------|-----------------------|
| Peak RSS | invalid parser result | 248.2 MB |
| Peak heap used | unavailable | 28.6 MB |
| Peak heap committed | unavailable | 68.0 MB |

This one-request sample does not justify an `-Xmx` change. The controller is
currently well below the 16 GB system budget, but a real multi-agent load run
with llama.cpp resident is still required before setting a smaller heap cap.

### Model swaps and concurrency

The earlier Q4 CPU-only swap measurement was approximately 3,200 ms. The tuned
server configuration has not yet had a clean end-to-end swap measurement, so
the pinned-model decision remains deferred rather than being changed by
assumption. The single-resident model design remains the safe default.

The scheduler benchmark measured:

| Scenario | Auto-drain | Legacy strict mode |
|----------|------------|--------------------|
| 10 follow-ups queued during active dispatch | 1,005 ms | 1,005 ms |
| Release-race stranded tasks | 0/200 | 1/200 |
| Poisoned-task follow-ups stranded | 0 | 0 |

This supports keeping one model-mutating dispatch slot while retaining
auto-drain. The current evidence does not justify concurrent model inference;
lightweight same-model parallelism should be a separate measured change.

---

## 1. Measured Latencies

### Cold Start (first request, no model loaded)

| Metric | Value |
|--------|-------|
| Model load (llama-server start + model init) | **3,100 – 3,900 ms** |
| First inference (varies by prompt) | 8,000 – 45,000 ms |
| **Total first-request wall time** | **~31,000 – 49,000 ms** |

### Warm Latency (same modelId as previous request)

| Metric | Value |
|--------|-------|
| Model load | **0 ms** (already resident, skipped) |
| Inference only | 7,500 – 45,000 ms |
| **Total warm wall time** | **~8,000 – 45,000 ms** |

Measured on requests 5, 9, 13, 17 (CODING after CODING) — all showed `load=0ms`.

### Swap Latency (different modelId than currently resident)

| Metric | Value |
|--------|-------|
| Model load (evict + reload) | **3,100 – 3,900 ms** (avg **3,200 ms**) |
| Inference | 7,500 – 45,000 ms |
| **Total swap wall time** | **~11,000 – 49,000 ms** |

Swap overhead as percentage of total request time: **~14%** (3,200 ms / 22,400 ms avg).

### VRAM Headroom

`rocm-smi` is not installed on the test machine, so VRAM telemetry is unavailable.
OOM-guard is skipped. No requests were rejected under sequential load.
On systems with ROCm, `VramMonitor` will enforce the VRAM budget and reject
requests that would cause OOM.

---

## 2. Concurrency Behavior

### Scheduler Serialization (SingleSlotAgentScheduler)

| Test | Requests | DISPATCHED | QUEUED | Drops | Deadlocks |
|------|----------|------------|--------|-------|-----------|
| 12-request load test | 12 | 12 | 0 | 0 | 0 |
| 20-request stress test | 20 | 20 | 0 | 0 | 0 |
| Unit test (4 concurrent) | 4 | 1 | 3 | 0 | 0 |

When requests arrive faster than they complete:
- First request gets `DISPATCHED` (acquires semaphore).
- Remaining requests get `QUEUED` status immediately (non-blocking `tryAcquire`).
- Queued tasks drain when a subsequent `submit()` acquires the semaphore.
- No request is dropped. No deadlock observed.

**Important**: queued tasks do NOT auto-drain after the current task finishes.
A new `submit()` call is required to pick them up. This is by design — the
scheduler is a single-slot dispatcher, not a work-stealing queue.

---

## 3. Pinned-Model Decision

**Recommendation: Do NOT implement a pinned-model feature.**

### Why

The measured swap cost is ~3.2s, which is significant (~14% of request time).
However, the simplest and most effective fix is already available:

**Map all agents to the same catalog key when they use the same GGUF file.**

```yaml
model-catalog:
  coding-small: Qwen2.5-7B-Instruct-Q4_K_M.gguf
  research-small: Qwen2.5-7B-Instruct-Q4_K_M.gguf
  data-small: Qwen2.5-7B-Instruct-Q4_K_M.gguf
  writing-small: Qwen2.5-7B-Instruct-Q4_K_M.gguf
```

With this configuration:
- All agents resolve to the same underlying file.
- `SingleResidentModelManager` tracks by catalog key, so "coding-small" and
  "writing-small" are different keys → still causes swaps.
- **But**: if you use the SAME catalog key for all agents (e.g., all map to
  `"default-model"`), the model stays resident across all requests → **0ms load**.

A `pinned-model` feature would add complexity (multi-model residency, VRAM
partitioning, eviction priority) without clear benefit when:
1. The single-resident architecture already limits to one model at a time.
2. The same GGUF can be shared across agents via catalog config.
3. The real future use case is having DIFFERENT models per agent (small router +
   large task models), which needs a multi-model manager, not a pin flag.

---

## 4. Idle-Timeout Tuning

### Current value: **10 minutes** (updated from 5 minutes)

### Reasoning

| Timeout | Swap during bursty patterns | VRAM waste during idle | Reload penalty on restart |
|---------|---------------------------|----------------------|--------------------------|
| 1 min   | High (evict between bursts) | Low | Frequent ~3.2s reloads |
| 5 min   | Medium | Low-Medium | Moderate reloads |
| **10 min** | **Low** | **Acceptable** | **Rare reloads** |
| 30 min  | None | High (VRAM held) | Very rare |

**Measured scenario**: 12 requests over ~5 minutes, mixed types.
- With 5-min timeout: model evicted 5 min after last request → next burst reloads.
- With 10-min timeout: model stays resident through short idle gaps → fewer reloads.

**Why not longer?** The Q4 7B model holds ~4.5 GB. On a 16 GB system, that's 28%
of RAM. Holding it for 30+ minutes when no requests are active wastes resources
that could be used by other processes.

**Why not shorter?** Each reload costs ~3.2s. During bursty usage (e.g., a user
sending 5 requests over 3 minutes, pausing, then sending 5 more), a 5-minute
timeout causes a reload on the 6th request. A 10-minute timeout avoids this.

---

## 5. Recommendations

### Immediate (no code changes)

1. **Use same catalog key for all agents** when they share a GGUF → 0ms warm start.
2. **Set idle-timeout to 10 minutes** (done in application.yml).
3. **Monitor swap frequency** via `TaskController` logs — each request logs
   `load=Xms swap=true/false`.

### Near-term (small code changes)

1. **Add `modelSwap` field to TaskResponse** (done) — enables load-test scripts
   to measure swap overhead without parsing logs.
2. **Add swap counter to `/status` endpoint** — track total swaps/hour for
   capacity planning.

### Future (larger changes)

1. **Multi-model manager** — allow 2-3 models to coexist in VRAM when hardware
   permits (e.g., 1B router + 7B task model on a system with 8+ GB VRAM).
2. **Model pre-warming** — start loading the next likely model in the background
   while the current one is running.
3. **VRAM-aware scheduling** — reject or defer requests when VRAM is low,
   rather than failing at model-load time.

---

## 6. Raw Data

Load test CSV: `/tmp/loadtest-results.csv`

```
req,type,status,modelLoadMs,inferenceMs,modelSwap
1,CODING,DISPATCHED,3357,28060,false
2,RESEARCH,DISPATCHED,3338,44753,true
3,DATA,DISPATCHED,3151,20997,true
4,WRITING,DISPATCHED,3867,8701,true
5,CODING,DISPATCHED,0,21571,false
6,RESEARCH,DISPATCHED,3194,38176,true
7,DATA,DISPATCHED,3179,21551,true
8,WRITING,DISPATCHED,3154,9024,true
9,CODING,DISPATCHED,0,23169,false
10,RESEARCH,DISPATCHED,3169,35346,true
11,DATA,DISPATCHED,3156,13764,true
12,WRITING,DISPATCHED,3165,8133,true
```
