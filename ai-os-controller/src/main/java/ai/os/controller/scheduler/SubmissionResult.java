package ai.os.controller.scheduler;

import ai.os.controller.agent.StepResult;

public record SubmissionResult(SubmissionStatus status, StepResult result) {
}