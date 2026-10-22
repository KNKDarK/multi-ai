package ai.os.controller.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ResearchAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(ResearchAgent.class);

    private final LlamaInferenceClient inferenceClient;
    private final ExecutionBackend executionBackend;
    private final String defaultModelId;

    public ResearchAgent(LlamaInferenceClient inferenceClient,
                         ExecutionBackend executionBackend,
                         @Value("${ai-os.agents.research.default-model:research-small}") String defaultModelId) {
        this.inferenceClient = inferenceClient;
        this.executionBackend = executionBackend;
        this.defaultModelId = defaultModelId;
    }

    @Override
    public AgentKind kind() {
        return AgentKind.RESEARCH;
    }

    @Override
    public StepResult execute(AgentTask task) {
        String modelId = resolveModelId(task);
        log.info("ResearchAgent: retrieval+inference model='{}' step='{}'", modelId, task.step().description());

        // Step 1 — retrieval via Python embed-service
        BackendResult retrieval;
        try {
            retrieval = executionBackend.invoke("embed", task.prompt());
        } catch (Exception e) {
            log.warn("ResearchAgent: retrieval failed ({}), proceeding with prompt only", e.getMessage());
            retrieval = new BackendResult(false, "");
        }

        // Step 2 — compose answer via LLM with retrieved context
        String augmented = task.prompt();
        if (retrieval.success() && !retrieval.output().isBlank()) {
            augmented = "Retrieved context:\n" + retrieval.output() + "\n\nUser question:\n" + task.prompt();
        }

        try {
            String completion = inferenceClient.complete(augmented, modelId);
            return new StepResult(true, completion);
        } catch (LlamaInferenceException e) {
            log.error("ResearchAgent: inference failed for model '{}' — {}", modelId, e.getMessage());
            return new StepResult(false, "Inference failed: " + e.getMessage());
        } catch (Exception e) {
            log.error("ResearchAgent: unexpected error for model '{}' — {}", modelId, e.getMessage());
            return new StepResult(false, "Unexpected error: " + e.getMessage());
        }
    }

    private String resolveModelId(AgentTask task) {
        return defaultModelId;
    }
}
