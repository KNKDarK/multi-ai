package ai.os.controller.agent;

import org.springframework.stereotype.Component;

/**
 * Thread-local metrics collector for a single request lifecycle.
 * {@link ai.os.controller.agent.HttpLlamaInferenceClient} writes timing data here;
 * {@link ai.os.controller.api.TaskController} reads it to populate the response.
 * Reset at the start of each request.
 */
@Component
public class MetricsCollector {

    private final ThreadLocal<RequestMetrics> current = new ThreadLocal<>();

    public void reset() {
        current.remove();
    }

    public void recordModelLoad(long loadMs, boolean swapped) {
        RequestMetrics m = current.get();
        if (m != null) {
            m.modelLoadMs = loadMs;
            m.swapOccurred = swapped;
        }
    }

    public void recordInference(long inferenceMs) {
        RequestMetrics m = current.get();
        if (m != null) {
            m.inferenceMs = inferenceMs;
        }
    }

    public void recordFirstToken(long firstTokenMs) {
        RequestMetrics m = current.get();
        if (m != null) {
            m.firstTokenMs = firstTokenMs;
        }
    }

    public void recordTokens(int tokens) {
        RequestMetrics m = current.get();
        if (m != null) {
            m.tokens = tokens;
        }
    }

    public void begin() {
        current.set(new RequestMetrics());
    }

    public RequestMetrics snapshot() {
        RequestMetrics m = current.get();
        return m == null ? new RequestMetrics() : m;
    }

    public static class RequestMetrics {
        private long modelLoadMs;
        private long inferenceMs;
        private long firstTokenMs;
        private int tokens;
        private boolean swapOccurred;

        public long getModelLoadMs() { return modelLoadMs; }
        public long getInferenceMs() { return inferenceMs; }
        public long getFirstTokenMs() { return firstTokenMs; }
        public int getTokens() { return tokens; }
        public boolean isSwapOccurred() { return swapOccurred; }
        public double getTokensPerSecond() {
            return inferenceMs == 0 ? 0.0 : tokens * 1000.0 / inferenceMs;
        }

        @Override
        public String toString() {
            return "load=%dms ttft=%dms infer=%dms tokens=%d tok/s=%.1f swap=%s"
                    .formatted(modelLoadMs, firstTokenMs, inferenceMs, tokens, getTokensPerSecond(), swapOccurred);
        }
    }
}
