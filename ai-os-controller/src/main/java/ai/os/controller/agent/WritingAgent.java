package ai.os.controller.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WritingAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(WritingAgent.class);

    private final LlamaInferenceClient inferenceClient;
    private final String defaultModelId;

    public WritingAgent(LlamaInferenceClient inferenceClient,
                        @Value("${ai-os.agents.writing.default-model:writing-small}") String defaultModelId) {
        this.inferenceClient = inferenceClient;
        this.defaultModelId = defaultModelId;
    }

    @Override
    public AgentKind kind() {
        return AgentKind.WRITING;
    }

    @Override
    public StepResult execute(AgentTask task) {
        String modelId = resolveModelId(task);
        log.info("WritingAgent: invoking inference model='{}' step='{}'", modelId, task.step().description());

        String writingPrompt = "You are a professional writing assistant. "
                + "Produce clear, well-structured content that matches the requested tone and format.\n\n"
                + task.prompt();

        try {
            String completion = inferenceClient.complete(writingPrompt, modelId);
            return new StepResult(true, completion);
        } catch (LlamaInferenceException e) {
            log.error("WritingAgent: inference failed for model '{}' — {}", modelId, e.getMessage());
            return new StepResult(false, "Inference failed: " + e.getMessage());
        } catch (Exception e) {
            log.error("WritingAgent: unexpected error for model '{}' — {}", modelId, e.getMessage());
            return new StepResult(false, "Unexpected error: " + e.getMessage());
        }
    }

    private String resolveModelId(AgentTask task) {
        return defaultModelId;
    }
}
