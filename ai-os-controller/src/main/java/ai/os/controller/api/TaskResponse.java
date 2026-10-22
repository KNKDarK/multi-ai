package ai.os.controller.api;

import ai.os.controller.router.TaskType;
import ai.os.controller.scheduler.SubmissionStatus;

public record TaskResponse(
        String taskId,
        TaskType taskType,
        SubmissionStatus dispatchStatus,
        int plannedSteps,
        String message,
        long modelLoadMs,
        long inferenceMs,
        boolean modelSwap) {
}
