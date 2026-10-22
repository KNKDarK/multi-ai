# Multi-Agent Local AI System — Project Context

## Project Overview

Build a local, resource-aware, multi-agent AI system designed for a laptop with:

* CPU: AMD Ryzen 5 5600H
* RAM: 16 GB
* GPU: AMD Radeon RX 6500M
* OS: Linux / CachyOS
* Local inference: llama.cpp
* Primary backend language: Java
* AI/ML services: Python
* Communication: HTTP/REST
* Initial deployment: single local machine

The system must prioritize:

1. High response quality
2. Low RAM usage
3. Efficient GPU usage
4. Dynamic model loading
5. Model reuse when beneficial
6. Controlled concurrency
7. Clear separation between Java orchestration and Python AI services
8. Local-first operation
9. Reliability and graceful failure
10. Extensibility for future agents and models

---

# Core Architecture

The system consists of:

```text
USER
  |
  v
JAVA / SPRING BOOT
  |
  +--> API / Controller
  |
  +--> Router
  |
  +--> Planner
  |
  +--> Agent Scheduler
  |
  +--> Model Manager
  |
  +--> Resource Manager
  |
  +--> Memory Manager
  |
  v
AGENTS
  |
  +--> Coding Agent
  +--> Research Agent
  +--> Data Agent
  +--> Writing Agent
  +--> General Agent
  |
  v
PYTHON AI SERVICES
  |
  +--> RAG
  +--> Embeddings
  +--> Document Processing
  +--> Data Analysis
  |
  v
LLAMA.CPP
  |
  +--> Local GGUF Models
  |
  v
AMD GPU / CPU
```

---

# Major Design Principle

Agents are logical roles, NOT necessarily separate running model processes.

Do NOT permanently load five large language models.

Instead:

```text
Agent
  |
  v
Model Manager
  |
  +--> determine required model
  |
  +--> check if model is already loaded
  |
  +--> check available RAM
  |
  +--> check available VRAM
  |
  +--> load if required
  |
  +--> reuse if already loaded
  |
  +--> unload least useful model when memory pressure occurs
```

The system should dynamically decide which model to use.

---

# Technology Responsibilities

## Java

Java is the main orchestration layer.

Use Java for:

* Spring Boot API
* request handling
* routing
* task planning
* agent orchestration
* concurrency
* scheduling
* model lifecycle management
* resource management
* retries
* timeout handling
* agent state
* memory coordination
* logging
* configuration
* database integration
* final response aggregation

Preferred Java version:

```text
Java 21+
```

Use modern concurrency features where appropriate.

Preferred concurrency mechanisms:

* Virtual Threads
* CompletableFuture
* ExecutorService
* Semaphore
* structured task coordination where appropriate

Do not create unnecessary platform threads.

---

# Python

Python is the AI/ML support layer.

Use Python for:

* RAG
* embedding generation
* document parsing
* vector search
* data processing
* pandas
* NumPy
* machine-learning utilities
* specialized AI libraries unavailable or inconvenient in Java

Python should not duplicate responsibilities already handled efficiently by Java.

Do not use Python merely because it is an AI language.

Use it only when Python's ecosystem provides a real advantage.

---

# llama.cpp

Use llama.cpp for local LLM inference.

Models should preferably use GGUF format.

The model layer should support:

* local inference
* quantized models
* CPU inference
* GPU-assisted inference
* CPU/GPU hybrid inference when appropriate
* configurable context length
* configurable batch size
* configurable GPU offload
* model loading/unloading

Do not assume that every model should use maximum GPU offload.

Benchmark and choose settings based on actual hardware.

---

# Hardware Constraints

Target hardware:

```text
AMD Ryzen 5 5600H
16 GB RAM
AMD Radeon RX 6500M
```

This is a resource-constrained local AI environment.

The system must avoid:

* loading many large models simultaneously
* excessive RAM usage
* uncontrolled concurrent inference
* unnecessary background processes
* duplicated model copies
* excessive context sizes
* unnecessary vector database memory usage

---

# Resource Management

Create a dedicated Resource Manager.

Responsibilities:

```text
ResourceManager
├── getAvailableRam()
├── getUsedRam()
├── getAvailableGpuMemory()
├── getGpuLoad()
├── canLoadModel()
├── canStartInference()
├── reserveResources()
├── releaseResources()
└── memoryPressureState()
```

The exact implementation may evolve.

The important principle is:

```text
Never start expensive inference blindly.
Check resources first.
```

---

# Model Manager

Create a Model Manager responsible for model lifecycle.

Required responsibilities:

```text
ModelManager
├── registerModel()
├── loadModel()
├── unloadModel()
├── isLoaded()
├── getModel()
├── selectModel()
├── getModelMetadata()
├── lastUsed()
├── memoryRequirement()
└── healthCheck()
```

Each registered model should contain metadata such as:

```text
modelId
name
purpose
quantization
approxRamRequirement
approxVramRequirement
contextLength
preferredBackend
qualityLevel
speedLevel
priority
```

Example:

```json
{
  "modelId": "coding-model",
  "purpose": "coding",
  "quantization": "Q4",
  "qualityLevel": 4,
  "speedLevel": 4
}
```

Metadata values are examples only.

Do not hard-code hardware assumptions until they are benchmarked.

---

# Dynamic Model Loading

Model loading must be demand-driven.

Example:

```text
User:
"Debug this Java program"

        |
        v

Router
        |
        v

Coding Agent
        |
        v

Model Manager
        |
        +--> Coding model loaded?
                 |
             YES | NO
                 |
                 +--> Check resources
                         |
                         v
                     Load model
                         |
                         v
                    Run inference
```

When a model is already loaded and healthy:

```text
Reuse it.
```

Do not unload and reload the same model unnecessarily.

---

# Model Eviction

When memory pressure becomes high, unload a model using a policy such as:

1. model not currently running
2. least recently used
3. lowest priority
4. highest memory consumption

Never unload a model currently being used.

Use a safe lifecycle:

```text
LOADED
  |
  v
IDLE
  |
  v
EVICTION CANDIDATE
  |
  v
UNLOAD
```

---

# Agent Definitions

## 1. Coding Agent

Responsibilities:

* C
* C++
* Java
* Python
* SQL
* debugging
* DSA
* algorithms
* code generation
* code explanation
* test generation
* compiler-related tasks

Preferred tools:

* terminal
* compiler
* test runner
* filesystem
* Git where enabled

The Coding Agent should prefer correctness over verbosity.

It should validate generated code when execution tools are available.

---

# 2. Research Agent

Responsibilities:

* research
* technical investigation
* documentation lookup
* comparisons
* fact verification
* current information

Tools:

* web search
* local documents
* RAG
* source retrieval

For current or changing information, prefer external retrieval rather than relying entirely on the LLM's knowledge.

---

# 3. Data Agent

Responsibilities:

* CSV
* JSON
* Excel
* SQL
* data cleaning
* statistics
* exploratory analysis
* visualization
* transformations

Preferred Python tools:

* pandas
* NumPy
* matplotlib

The Data Agent should use Python for computational work rather than asking the LLM to perform large calculations manually.

---

# 4. Writing Agent

Responsibilities:

* reports
* assignments
* emails
* documentation
* rewriting
* summarization
* structured content generation

The Writing Agent should prioritize:

* correctness
* natural language
* requested tone
* requested length
* formatting requirements

---

# 5. General Agent

Fallback agent.

Use when:

* request does not clearly match another agent
* request is simple
* task requires general explanation
* router confidence is low

Do not force every request into a specialized agent.

---

# Router

The Router is responsible for classification and routing.

The Router should NOT solve the user's problem.

It should determine:

```text
agent
model
tools
parallelism
priority
requiresRag
requiresWeb
requiresPython
```

Example output:

```json
{
  "agent": "coding",
  "model": "coding-model",
  "tools": ["terminal"],
  "parallel": false,
  "requiresRag": false,
  "requiresWeb": false,
  "requiresPython": false
}
```

The output must be structured and machine-readable.

Prefer strict JSON.

---

# Router Rules

Use the smallest sufficient model for routing.

Do not use the strongest reasoning model for simple classification.

Routing should be:

```text
fast
cheap
deterministic
structured
```

The Router may use confidence scoring.

Example:

```json
{
  "agent": "research",
  "confidence": 0.91
}
```

If confidence is low:

```text
use General Agent
OR
perform a second lightweight classification
```

Do not repeatedly call large models to decide routing.

---

# Planner

The Planner determines whether a task needs:

```text
single agent
multiple agents
sequential execution
parallel execution
RAG
web
Python
code execution
```

Example:

```text
Question:
"Research Linux GPU drivers and compare them with my laptop configuration."

Plan:

1. Research Agent
2. Hardware Analysis
3. Final synthesis
```

Another example:

```text
Question:
"Fix this Java program."

Plan:

1. Coding Agent
2. compile
3. test
4. return result
```

---

# Concurrency

Concurrency should be controlled by Java.

Important:

Concurrency does NOT mean launching unlimited LLM requests.

Use:

```text
many lightweight tasks
+
limited expensive inference
```

The system should have configurable limits.

Example configuration:

```text
maxConcurrentLLMRequests = 1 or 2
maxConcurrentPythonTasks = configurable
maxConcurrentWebTasks = configurable
```

Benchmark these values.

Do NOT assume a fixed value is optimal.

---

# Parallel Agent Execution

Independent tasks may run concurrently.

Example:

```text
Research Agent ───────┐
                      |
Data Agent ───────────┼──> Synthesizer
                      |
Coding Agent ─────────┘
```

Use Java concurrency primitives to coordinate them.

The system must support cancellation and timeout.

If one task fails:

```text
do not automatically kill all other valid tasks
```

unless the task dependency requires it.

---

# Sequential Agent Execution

Use sequential execution when one agent depends on another.

Example:

```text
Research
   |
   v
Analysis
   |
   v
Writing
```

Do not parallelize dependent tasks.

---

# Resource-Aware Scheduling

Before starting an expensive model task:

```text
1. Check model
2. Check RAM
3. Check VRAM
4. Check current inference
5. Reserve resources
6. Start task
7. Release resources
```

Conceptually:

```text
if resourcesAvailable:
    reserve()
    run()
    release()
else:
    wait()
    OR
    evictIdleModel()
    OR
    use smaller model()
```

---

# Quality-Based Model Selection

Model choice should not be based only on model size.

Use:

```text
task difficulty
+
required quality
+
available hardware
+
latency
+
memory
```

Example:

```text
Simple classification
    -> small/fast model

Normal coding
    -> coding model

Complex debugging
    -> stronger reasoning/coding model

Large research task
    -> research model + RAG

Simple writing
    -> smaller writing model
```

The system should choose the smallest model capable of producing acceptable quality.

---

# Model Profiles

Maintain a model registry.

Example:

```text
models/
├── router/
├── coding/
├── reasoning/
├── writing/
├── general/
└── embedding/
```

Do not hard-code model filenames throughout the application.

All model selection should go through ModelManager.

---

# RAG Architecture

Python handles RAG.

Pipeline:

```text
Document
  |
  v
Parser
  |
  v
Chunking
  |
  v
Embedding
  |
  v
Vector Database
  |
  v
Retrieval
  |
  v
Relevant Context
  |
  v
LLM
```

Use RAG only when useful.

Do not retrieve documents for every request.

---

# Embedding Model

Embedding models should be lightweight.

Prefer CPU execution unless GPU acceleration provides a measurable advantage.

Do not keep a large embedding model resident unnecessarily.

Embedding models can be cached when practical.

---

# Memory Architecture

Separate:

## Conversation memory

Short-term conversation context.

## Long-term memory

Only store information that is intentionally useful.

## RAG knowledge

Documents and indexed information.

Do not mix these concepts.

---

# Context Management

Context size must be controlled.

Do not continuously append the entire conversation.

Use:

```text
recent messages
+
relevant memory
+
relevant retrieved documents
```

rather than:

```text
entire history
```

This reduces RAM usage and improves model attention.

---

# Prompt Management

Prompts should be externalized.

Structure:

```text
prompts/
├── router.md
├── planner.md
├── coding.md
├── research.md
├── data.md
├── writing.md
└── general.md
```

Do not bury large system prompts directly inside Java classes.

---

# Error Handling

Every agent call must support:

* timeout
* cancellation
* retry
* fallback
* structured error
* logging

Example:

```text
Model unavailable
    |
    +--> try reload
    |
    +--> retry once
    |
    +--> fallback model
    |
    +--> graceful error
```

Do not retry indefinitely.

---

# Logging

Log:

```text
request ID
agent selected
model selected
load time
inference time
token count if available
memory decision
success/failure
tool execution time
```

Do NOT log private user information unnecessarily.

---

# Observability

Provide a lightweight system status endpoint.

Example:

```text
GET /status
```

Return information such as:

```json
{
  "activeAgent": "coding",
  "activeModel": "coding-model",
  "loadedModels": 1,
  "llmRequests": 1,
  "memoryPressure": "normal"
}
```

Do not expose unnecessary sensitive information.

---

# Security

Never allow arbitrary shell execution from the LLM without controlled permissions.

Tools must be sandboxed or explicitly authorized.

The Coding Agent should not automatically execute destructive commands.

Examples of risky operations requiring protection:

```text
rm -rf
disk formatting
system configuration changes
credential access
network attacks
```

Use allowlists or confirmation mechanisms where appropriate.

---

# Project Principles

Always prefer:

```text
simple > unnecessarily complex
local > external
resource-aware > brute force
quality > raw speed
measurable optimization > assumptions
modular > tightly coupled
recoverable > fragile
```

---

# Development Phases

## Phase 1

Build:

```text
Spring Boot
+
Router
+
One Agent
+
llama.cpp
```

Goal:

Successfully route and answer one request.

---

## Phase 2

Add:

```text
Coding
Research
Data
Writing
General
```

agents.

All can initially use the same local model with different prompts.

---

## Phase 3

Add ModelManager.

Implement:

```text
load
unload
reuse
metadata
health checks
```

---

## Phase 4

Add ResourceManager.

Monitor:

```text
RAM
VRAM
CPU
concurrency
```

---

## Phase 5

Add Python AI services.

Implement:

```text
RAG
embeddings
document processing
data analysis
```

---

## Phase 6

Add controlled concurrency.

Implement:

```text
parallel independent tasks
sequential dependent tasks
timeouts
cancellation
resource reservation
```

---

## Phase 7

Benchmark.

Measure:

```text
TTFT
tokens/second
RAM usage
VRAM usage
model load time
model unload time
agent latency
parallel task performance
```

Optimize based on measured results.

Do NOT optimize based purely on theory.

---

# Benchmarking Rule

Every major optimization must be measurable.

Compare:

```text
CPU only
GPU assisted
different quantizations
different context sizes
different concurrency limits
different models
```

Record the results.

Example:

```text
benchmark/
├── hardware.md
├── models.md
├── inference.md
├── concurrency.md
└── results.csv
```

---

# Final Target Behavior

The final system should behave like this:

```text
USER
 |
 v
JAVA API
 |
 v
ROUTER
 |
 v
PLANNER
 |
 v
RESOURCE MANAGER
 |
 v
MODEL MANAGER
 |
 +--> choose best model
 |
 +--> load only if required
 |
 +--> reuse if already loaded
 |
 +--> unload if memory pressure
 |
 v
AGENT
 |
 +--> Python service if required
 |
 +--> tools if required
 |
 v
RESULT
 |
 v
SYNTHESIS
 |
 v
USER
```

---

# Critical Rules for Future Development

1. Do not load every model at startup.

2. Do not run five LLM inference processes simply because there are five agents.

3. Do not assume the GPU must always be used.

4. Do not assume CPU inference is always better.

5. Benchmark AMD GPU backends on the actual machine.

6. Do not exceed available RAM unnecessarily.

7. Keep LLM concurrency limited by ResourceManager.

8. Use Java for orchestration and concurrency.

9. Use Python where AI/ML libraries provide a real advantage.

10. Keep model selection dynamic.

11. Keep agents modular.

12. Keep prompts external.

13. Make failures recoverable.

14. Measure before optimizing.

15. Prefer one strong reusable loaded model over many simultaneously loaded models when the task does not justify multiple models.

---

# Definition of Success

The project is successful when it can:

* receive a user request
* intelligently route it
* select an appropriate agent
* select an appropriate model
* dynamically load that model when needed
* avoid unnecessary model loading
* execute agents concurrently when tasks are independent
* use Python AI services when appropriate
* use llama.cpp for local inference
* efficiently use the Ryzen CPU and RX 6500M
* remain usable within 16 GB RAM
* recover from model/tool failures
* provide measurable performance metrics
* maintain high answer quality

The architecture must remain modular enough that new agents, models, tools, and AI services can be added without rewriting the whole system.
