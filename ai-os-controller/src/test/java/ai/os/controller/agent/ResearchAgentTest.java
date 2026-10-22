package ai.os.controller.agent;

import ai.os.controller.planner.Step;
import ai.os.controller.router.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearchAgentTest {

    private LlamaInferenceClient inferenceClient;
    private ExecutionBackend executionBackend;
    private ResearchAgent agent;

    @BeforeEach
    void setUp() {
        inferenceClient = org.mockito.Mockito.mock(LlamaInferenceClient.class);
        executionBackend = org.mockito.Mockito.mock(ExecutionBackend.class);
        agent = new ResearchAgent(inferenceClient, executionBackend, "research-small");
    }

    @Test
    void kindIsResearch() {
        assertThat(agent.kind()).isEqualTo(AgentKind.RESEARCH);
    }

    @Test
    void executeChainsRetrievalThenInference() {
        when(executionBackend.invoke(eq("embed"), anyString()))
                .thenReturn(new BackendResult(true, "retrieved: Linux GPU drivers"));
        when(inferenceClient.complete(anyString(), eq("research-small")))
                .thenReturn("AMD ROCm is the recommended driver stack");

        Step step = new Step(1, TaskType.RESEARCH, "Research GPU drivers");
        StepResult result = agent.execute(new AgentTask(step, "Compare GPU drivers for RX 6500M"));

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("AMD ROCm");
        verify(executionBackend).invoke("embed", "Compare GPU drivers for RX 6500M");
        // The augmented prompt (with "Retrieved context") is sent to the LLM,
        // not included in the result message
        verify(inferenceClient).complete(
                org.mockito.ArgumentMatchers.contains("Retrieved context"), eq("research-small"));
    }

    @Test
    void executeProceedsWithoutRetrievalWhenBackendFails() {
        when(executionBackend.invoke(eq("embed"), anyString()))
                .thenThrow(new RuntimeException("embed-service down"));
        when(inferenceClient.complete(anyString(), eq("research-small")))
                .thenReturn("General answer without retrieval");

        Step step = new Step(1, TaskType.RESEARCH, "Research topic");
        StepResult result = agent.execute(new AgentTask(step, "What is quantum computing?"));

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("General answer without retrieval");
        // Prompt passed directly without context prefix
        verify(inferenceClient).complete("What is quantum computing?", "research-small");
    }

    @Test
    void executeReturnsFailureOnInferenceError() {
        when(executionBackend.invoke(eq("embed"), anyString()))
                .thenReturn(new BackendResult(true, "context"));
        when(inferenceClient.complete(anyString(), eq("research-small")))
                .thenThrow(new LlamaInferenceException("connection refused"));

        Step step = new Step(1, TaskType.RESEARCH, "Research");
        StepResult result = agent.execute(new AgentTask(step, "query"));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Inference failed");
    }
}
