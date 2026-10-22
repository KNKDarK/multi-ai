package ai.os.controller.planner;

import ai.os.controller.router.TaskType;

public record Step(int order, TaskType type, String description) {
}