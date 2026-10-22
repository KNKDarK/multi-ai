package ai.os.controller.agent;

/**
 * Thrown when the llama-server inference call fails due to transport errors,
 * timeouts, non-2xx responses, or the server process crashing mid-request.
 *
 * <p>Callers can inspect {@link #isTransportUnavailable()} to decide whether the failure
 * is a recoverable crash (server may have died) versus a non-recoverable error. This
 * distinction lets the {@link HttpLlamaInferenceClient} perform a single automatic retry
 * against a freshly-restarted llama-server without forcing callers to handle retries
 * themselves.
 */
public class LlamaInferenceException extends RuntimeException {

    private final boolean transportUnavailable;

    public LlamaInferenceException(String message) {
        super(message);
        this.transportUnavailable = false;
    }

    public LlamaInferenceException(String message, Throwable cause) {
        super(message, cause);
        this.transportUnavailable = false;
    }

    private LlamaInferenceException(boolean transportUnavailable, String message, Throwable cause) {
        super(message, cause);
        this.transportUnavailable = transportUnavailable;
    }

    static LlamaInferenceException transport(String message, Throwable cause) {
        return new LlamaInferenceException(true, message, cause);
    }

    static LlamaInferenceException transport(String message) {
        return new LlamaInferenceException(true, message, null);
    }

    public boolean isTransportUnavailable() {
        return transportUnavailable;
    }
}
