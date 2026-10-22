package ai.os.controller.agent;

import ai.os.controller.planner.Step;

public record AgentTask(Step step, String prompt) {
}