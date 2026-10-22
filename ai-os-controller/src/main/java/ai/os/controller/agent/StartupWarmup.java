package ai.os.controller.agent;

import ai.os.controller.config.LlamaProperties;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Runs a single small inference against the real GPU/CPU backend shortly after boot so the
 * first user request is not also the first inference on cold hardware. Aside from shader
 * caches, this makes the readiness endpoint honest: the app only reports healthy once a
 * complete inference round-trip has actually succeeded.
 *
 * <p>The warmup runs on a virtual thread so application startup is never blocked. It is a
 * best-effort step — if it fails, the app still starts (the health indicator then reports
 * {@code WARMUP_FAILED} but the service remains usable).
 */
@Component
public class StartupWarmup {

    private static final Logger log = LoggerFactory.getLogger(StartupWarmup.class);

    public enum Phase {
        DISABLED, PENDING, RUNNING, SUCCESS, FAILED
    }

    private final LlamaInferenceClient inferenceClient;
    private final LlamaProperties llamaProperties;
    private volatile Phase phase = Phase.PENDING;
    private volatile long loadMs;
    private volatile long inferenceMs;
    private volatile String error;
    private final CompletableFuture<Void> completion = new CompletableFuture<>();

    public StartupWarmup(LlamaInferenceClient inferenceClient, LlamaProperties llamaProperties) {
        this.inferenceClient = inferenceClient;
        this.llamaProperties = llamaProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!llamaProperties.isWarmupEnabled()) {
            phase = Phase.DISABLED;
            completion.complete(null);
            log.info("StartupWarmup: disabled by configuration");
            return;
        }
        String modelId = llamaProperties.getWarmupModelId();
        log.info("StartupWarmup: starting warmup inference for model '{}'", modelId);
        Thread.ofVirtual().name("warmup-inference").start(() -> run(modelId));
    }

    private void run(String modelId) {
        phase = Phase.RUNNING;
        long started = System.nanoTime();
        try {
            inferenceClient.complete(llamaProperties.getWarmupPrompt(), modelId);
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            phase = Phase.SUCCESS;
            log.info("StartupWarmup: completed for model '{}' in {} ms total", modelId, durationMs);
        } catch (Exception e) {
            phase = Phase.FAILED;
            error = e.getMessage();
            log.warn("StartupWarmup: failed for model '{}' — {} (service remains usable)", modelId, error);
        } finally {
            completion.complete(null);
        }
    }

    public Phase getPhase() {
        return phase;
    }

    public long getLoadMs() {
        return loadMs;
    }

    public long getInferenceMs() {
        return inferenceMs;
    }

    public String getError() {
        return error;
    }

    public CompletableFuture<Void> getCompletion() {
        return completion;
    }
}