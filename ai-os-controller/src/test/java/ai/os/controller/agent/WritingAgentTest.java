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

class WritingAgentTest {

    private LlamaInferenceClient inferenceClient;
    private WritingAgent agent;

    @BeforeEach
    void setUp() {
        inferenceClient = org.mockito.Mockito.mock(LlamaInferenceClient.class);
        agent = new WritingAgent(inferenceClient, "writing-small");
    }

    @Test
    void kindIsWriting() {
        assertThat(agent.kind()).isEqualTo(AgentKind.WRITING);
    }

    @Test
    void executeCallsInferenceWithSystemPrompt() {
        when(inferenceClient.complete(contains("professional writing assistant"), eq("writing-small")))
                .thenReturn("Dear team, I am writing to inform you...");

        Step step = new Step(1, TaskType.WRITING, "Draft email");
        StepResult result = agent.execute(new AgentTask(step, "Write an email to the team about the deadline"));

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("Dear team");
        verify(inferenceClient).complete(contains("professional writing assistant"), eq("writing-small"));
    }

    @Test
    void executeReturnsFailureOnInferenceError() {
        when(inferenceClient.complete(contains("professional writing assistant"), eq("writing-small")))
                .thenThrow(new LlamaInferenceException("process crashed"));

        Step step = new Step(1, TaskType.WRITING, "Draft");
        StepResult result = agent.execute(new AgentTask(step, "query"));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Inference failed");
    }
}
