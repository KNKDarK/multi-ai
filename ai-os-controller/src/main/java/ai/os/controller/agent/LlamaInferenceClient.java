package ai.os.controller.agent;

/**
 * Client that performs real LLM inference via the running llama-server.
 * Implementations must ensure the requested model is loaded before inference.
 */
public interface LlamaInferenceClient {

    /**
     * Load the model (if not already resident) and send a chat-completion request.
     *
     * @param prompt  the user message to complete
     * @param modelId logical model id recognised by the model catalog (e.g. "coding-small")
     * @return the assistant content from the chat-completion response
     * @throws LlamaInferenceException on transport, timeout, or server errors
     */
    String complete(String prompt, String modelId);
}
