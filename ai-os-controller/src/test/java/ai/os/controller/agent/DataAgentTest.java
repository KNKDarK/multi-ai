package ai.os.controller.agent;

import ai.os.controller.planner.Step;
import ai.os.controller.router.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataAgentTest {

    private LlamaInferenceClient inferenceClient;
    private DataAgent agent;

    @BeforeEach
    void setUp() {
        inferenceClient = org.mockito.Mockito.mock(LlamaInferenceClient.class);
        agent = new DataAgent(inferenceClient, "data-small");
    }

    @Test
    void kindIsData() {
        assertThat(agent.kind()).isEqualTo(AgentKind.DATA);
    }

    @Test
    void executeCallsInferenceWithSystemPrompt() {
        when(inferenceClient.complete(contains("data analysis assistant"), eq("data-small")))
                .thenReturn("SELECT AVG(price) FROM products;");

        Step step = new Step(1, TaskType.DATA, "Compute average price");
        StepResult result = agent.execute(new AgentTask(step, "What is the average product price?"));

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("SELECT AVG");
        verify(inferenceClient).complete(contains("data analysis assistant"), eq("data-small"));
    }

    @Test
    void executeReturnsFailureOnInferenceError() {
        when(inferenceClient.complete(contains("data analysis assistant"), eq("data-small")))
                .thenThrow(new LlamaInferenceException("timeout"));

        Step step = new Step(1, TaskType.DATA, "Analyze");
        StepResult result = agent.execute(new AgentTask(step, "query"));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Inference failed");
    }
}
