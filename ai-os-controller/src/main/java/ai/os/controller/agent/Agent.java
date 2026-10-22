package ai.os.controller.agent;

public interface Agent {

    AgentKind kind();

    StepResult execute(AgentTask task);
}