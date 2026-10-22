package ai.os.controller.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DataAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(DataAgent.class);

    private final LlamaInferenceClient inferenceClient;
    private final String defaultModelId;

    public DataAgent(LlamaInferenceClient inferenceClient,
                     @Value("${ai-os.agents.data.default-model:data-small}") String defaultModelId) {
        this.inferenceClient = inferenceClient;
        this.defaultModelId = defaultModelId;
    }

    @Override
    public AgentKind kind() {
        return AgentKind.DATA;
    }

    @Override
    public StepResult execute(AgentTask task) {
        String modelId = resolveModelId(task);
        log.info("DataAgent: invoking inference model='{}' step='{}'", modelId, task.step().description());

        String dataPrompt = "You are a data analysis assistant. "
                + "Answer clearly and provide concrete, actionable output.\n\n"
                + task.prompt();

        try {
            String completion = inferenceClient.complete(dataPrompt, modelId);
            return new StepResult(true, completion);
        } catch (LlamaInferenceException e) {
            log.error("DataAgent: inference failed for model '{}' — {}", modelId, e.getMessage());
            return new StepResult(false, "Inference failed: " + e.getMessage());
        } catch (Exception e) {
            log.error("DataAgent: unexpected error for model '{}' — {}", modelId, e.getMessage());
            return new StepResult(false, "Unexpected error: " + e.getMessage());
        }
    }

    private String resolveModelId(AgentTask task) {
        return defaultModelId;
    }
}
