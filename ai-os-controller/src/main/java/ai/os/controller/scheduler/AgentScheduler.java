package ai.os.controller.scheduler;

import ai.os.controller.agent.Agent;
import ai.os.controller.planner.Step;

public interface AgentScheduler {

    SubmissionResult submit(Step step, String prompt, Agent agent);

    int pendingCount();
}