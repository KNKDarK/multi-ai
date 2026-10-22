package ai.os.controller.agent;

import ai.os.controller.config.LlamaProperties;
import ai.os.controller.modelmanager.ModelManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Real {@link LlamaInferenceClient} that asks the {@link ModelManager} to ensure the
 * requested model is loaded and then POSTs a streaming OpenAI-compatible chat-completion
 * request to the running llama-server.
 *
 * <p>Streaming lets the client measure per-phase timing on first-boot requests:
 * model-load time, time-to-first-token, total generation time and tokens/sec. The total
 * generation is bounded by {@code ai-os.llama.request-timeout} via a deadline timer that
 * force-closes the response stream, so a stalled or over-long generation can never hang
 * the request thread indefinitely.
 */
@Component
public class HttpLlamaInferenceClient implements LlamaInferenceClient {

    private static final Logger log = LoggerFactory.getLogger(HttpLlamaInferenceClient.class);
    private static final String SSE_DONE = "[DONE]";

    private final ModelManager modelManager;
    private final LlamaProperties llamaProperties;
    private final MetricsCollector metricsCollector;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService deadlineTimer;

    @Autowired
    public HttpLlamaInferenceClient(ModelManager modelManager, LlamaProperties llamaProperties,
                                    MetricsCollector metricsCollector) {
        this(modelManager, llamaProperties, metricsCollector,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                new ObjectMapper());
    }

    HttpLlamaInferenceClient(ModelManager modelManager, LlamaProperties llamaProperties,
                             MetricsCollector metricsCollector,
                             HttpClient httpClient, ObjectMapper objectMapper) {
        this.modelManager = modelManager;
        this.llamaProperties = llamaProperties;
        this.metricsCollector = metricsCollector;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.deadlineTimer = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("llama-deadline").factory());
    }

    @Override
    public String complete(String prompt, String modelId) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }

        log.info("Loading model '{}' ({})", modelId,
                modelManager.currentModel().isPresent() ? "another model resident" : "no model resident");
        long loadStart = System.nanoTime();
        try {
            modelManager.load(modelId);
        } catch (Exception e) {
            throw new LlamaInferenceException("Failed to load model '" + modelId + "'", e);
        }
        long loadElapsed = (System.nanoTime() - loadStart) / 1_000_000;
        boolean swapped = modelManager.lastLoadSwapped();
        metricsCollector.recordModelLoad(loadElapsed, swapped);
        log.info("Model '{}' loaded in {} ms (swap={})", modelId, loadElapsed, swapped);

        return executeWithCrashRetry(prompt, modelId);
    }

    /**
     * Runs one chat-completion attempt. If the llama-server appears to be down or dies
     * mid-request (transport-level failure), reloads the model — which restarts a dead
     * llama-server — and retries exactly once. Non-transport failures (HTTP 4xx/5xx,
     * deadline, empty completion) are not retried.
     */
    private String executeWithCrashRetry(String prompt, String modelId) {
        Instant attemptStart = Instant.now();
        Duration deadline = llamaProperties.getRequestTimeout();
        LlamaInferenceException lastFailure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpResponse<InputStream> response = sendChatCompletion(prompt);
                return readStreamedCompletion(response, modelId);
            } catch (LlamaInferenceException e) {
                if (!e.isTransportUnavailable() || attempt > 0) {
                    throw e;
                }
                lastFailure = e;
                // If the stream broke only because the whole request budget elapsed
                // (e.g. a huge prompt whose prompt-eval alone burned the 90s deadline),
                // this is an over-budget failure, not a crash — retrying would only
                // reproduce the same slow path. Only retry genuine crashes that leave
                // budget remaining.
                Instant now = Instant.now();
                if (!now.isBefore(attemptStart.plus(deadline))) {
                    log.warn("llama-server stream failure for model '{}' after the full {} request budget; "
                            + "not retrying (this is an over-budget failure, not a crash)", modelId, deadline);
                    throw e;
                }
            }
            log.warn("llama-server unreachable for model '{}' ({}); reloading model and retrying once",
                    modelId, lastFailure.getMessage());
            try {
                modelManager.load(modelId);
            } catch (Exception loadError) {
                throw new LlamaInferenceException(
                        "Failed to reload model '" + modelId + "' after server failure: " + loadError.getMessage(),
                        loadError);
            }
        }
        // unreachable: attempt 1 either threw or returned
        throw lastFailure;
    }

    private HttpResponse<InputStream> sendChatCompletion(String prompt) {
        String url = "http://127.0.0.1:" + llamaProperties.getPort() + "/v1/chat/completions";

        Map<String, Object> body = Map.of(
                "model", "local",
                "messages", List.of(
                        Map.of("role", "system", "content",
                                "You are a helpful coding assistant. Answer clearly and concisely."),
                        Map.of("role", "user", "content", prompt)
                ),
                "temperature", 0.7,
                "max_tokens", llamaProperties.getMaxTokens(),
                "stream", true
        );

        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new LlamaInferenceException("Failed to serialise request body", e);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(llamaProperties.getRequestTimeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw LlamaInferenceException.transport(
                    "llama-server request failed (server may have crashed or timed out): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlamaInferenceException("Interrupted while waiting for llama-server response", e);
        }

        if (response.statusCode() != 200) {
            throw new LlamaInferenceException(
                    "llama-server returned HTTP " + response.statusCode() + ": " + readFirstLine(response.body()));
        }
        return response;
    }

    private String readStreamedCompletion(HttpResponse<InputStream> response, String modelId) {
        Duration deadline = llamaProperties.getRequestTimeout();
        InputStream body = response.body();
        AtomicBoolean deadlineHit = new AtomicBoolean(false);
        ScheduledFuture<?> timeout = deadlineTimer.schedule(() -> {
            deadlineHit.set(true);
            try {
                body.close();
            } catch (IOException ignored) {
                // closing the stream is best-effort: unblocks the blocking read below
            }
        }, deadline.toMillis(), TimeUnit.MILLISECONDS);

        long inferenceStart = System.nanoTime();
        StringBuilder content = new StringBuilder();
        int tokens = 0;
        long firstTokenMs = -1;
        boolean sawDone = false;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || !trimmed.startsWith("data:")) {
                    continue;
                }
                String payload = trimmed.substring("data:".length()).trim();
                if (payload.equals(SSE_DONE)) {
                    sawDone = true;
                    break;
                }
                String delta = parseContentDelta(payload);
                if (delta == null || delta.isEmpty()) {
                    continue;
                }
                if (firstTokenMs == -1) {
                    firstTokenMs = (System.nanoTime() - inferenceStart) / 1_000_000;
                    metricsCollector.recordFirstToken(firstTokenMs);
                    log.info("First token for model '{}' after {} ms", modelId, firstTokenMs);
                }
                content.append(delta);
                tokens++;
            }
        } catch (IOException e) {
            if (deadlineHit.get()) {
                throw new LlamaInferenceException("llama-server generation did not finish within " + deadline
                        + (tokens == 0 ? "" : " after " + tokens + " tokens"));
            }
            throw LlamaInferenceException.transport(
                    "llama-server stream failed mid-request (server may have crashed): " + e.getMessage(), e);
        } finally {
            timeout.cancel(false);
        }

        if (!sawDone) {
            if (deadlineHit.get()) {
                throw new LlamaInferenceException("llama-server generation did not finish within " + deadline
                        + (tokens == 0 ? "" : " after " + tokens + " tokens"));
            }
            throw new LlamaInferenceException("llama-server stream ended without [DONE]"
                    + (tokens == 0 ? " (no tokens received)" : " after " + tokens + " tokens"));
        }
        if (tokens == 0) {
            throw new LlamaInferenceException("llama-server returned empty streaming completion");
        }

        long inferenceElapsed = (System.nanoTime() - inferenceStart) / 1_000_000;
        metricsCollector.recordInference(inferenceElapsed);
        metricsCollector.recordTokens(tokens);
        double tokPerSec = inferenceElapsed == 0 ? 0.0 : tokens * 1000.0 / inferenceElapsed;
        log.info("Inference complete for model '{}': ttft={}ms total={}ms tokens={} tok/s={} chars={}",
                modelId, firstTokenMs, inferenceElapsed, tokens,
                String.format(java.util.Locale.ROOT, "%.1f", tokPerSec), content.length());

        return content.toString();
    }

    private String parseContentDelta(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            JsonNode delta = node.path("choices").path(0).path("delta").path("content");
            if (delta.isMissingNode() || delta.isNull()) {
                return null;
            }
            return delta.asText();
        } catch (Exception e) {
            log.warn("Skipping unparseable SSE chunk: {}", e.getMessage());
            return null;
        }
    }

    private static String readFirstLine(InputStream body) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            return line == null ? "" : line.substring(0, Math.min(line.length(), 200));
        } catch (IOException e) {
            return "unreadable body";
        }
    }
}