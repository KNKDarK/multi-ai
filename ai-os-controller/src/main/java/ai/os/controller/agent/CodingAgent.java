package ai.os.controller.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CodingAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(CodingAgent.class);

    private final LlamaInferenceClient inferenceClient;
    private final String defaultModelId;

    public CodingAgent(LlamaInferenceClient inferenceClient,
                       @Value("${ai-os.agents.coding.default-model:coding-small}") String defaultModelId) {
        this.inferenceClient = inferenceClient;
        this.defaultModelId = defaultModelId;
    }

    @Override
    public AgentKind kind() {
        return AgentKind.CODING;
    }

    @Override
    public StepResult execute(AgentTask task) {
        String modelId = resolveModelId(task);
        log.info("CodingAgent: invoking inference model='{}' step='{}'", modelId, task.step().description());
        try {
            String completion = inferenceClient.complete(task.prompt(), modelId);
            return new StepResult(true, completion);
        } catch (LlamaInferenceException e) {
            log.error("CodingAgent: inference failed for model '{}' — {}", modelId, e.getMessage());
            return new StepResult(false, "Inference failed: " + e.getMessage());
        } catch (Exception e) {
            log.error("CodingAgent: unexpected error for model '{}' — {}", modelId, e.getMessage());
            return new StepResult(false, "Unexpected error: " + e.getMessage());
        }
    }

    private String resolveModelId(AgentTask task) {
        // Future: inspect task.step().type() to choose different models per task kind.
        // For now, always use the configured default.
        return defaultModelId;
    }
}
