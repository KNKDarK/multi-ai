package ai.os.controller.api;

import ai.os.controller.agent.Agent;
import ai.os.controller.agent.AgentKind;
import ai.os.controller.agent.MetricsCollector;
import ai.os.controller.planner.Step;
import ai.os.controller.planner.TaskPlanner;
import ai.os.controller.router.RequestRouter;
import ai.os.controller.router.TaskType;
import ai.os.controller.scheduler.AgentScheduler;
import ai.os.controller.scheduler.SubmissionResult;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/task")
public class TaskController {

    private static final Logger log = LoggerFactory.getLogger(TaskController.class);

    private final RequestRouter router;
    private final TaskPlanner planner;
    private final AgentScheduler scheduler;
    private final MetricsCollector metricsCollector;
    private final java.util.Map<AgentKind, Agent> agentRegistry;

    public TaskController(RequestRouter router, TaskPlanner planner, AgentScheduler scheduler,
                          MetricsCollector metricsCollector,
                          java.util.Map<AgentKind, Agent> agentRegistry) {
        this.router = router;
        this.planner = planner;
        this.scheduler = scheduler;
        this.metricsCollector = metricsCollector;
        this.agentRegistry = agentRegistry;
    }

    @PostMapping(consumes = "application/json", produces = "application/json")
    public ResponseEntity<?> submit(@RequestBody TaskRequest request) {
        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(HttpStatus.BAD_REQUEST.value(), "Bad Request", "prompt must not be blank"));
        }

        metricsCollector.reset();
        metricsCollector.begin();

        TaskType type = router.classify(request.prompt());
        List<Step> steps = planner.plan(type, request.prompt());
        Step lastStep = steps.get(steps.size() - 1);

        AgentKind kind = mapTypeToKind(type);
        Agent agent = agentRegistry.get(kind);
        if (agent == null) {
            throw new IllegalStateException("No agent registered for kind " + kind);
        }

        log.info("TaskController: type={} -> agent={} ({} steps)", type, kind, steps.size());
        SubmissionResult submission = scheduler.submit(lastStep, request.prompt(), agent);

        MetricsCollector.RequestMetrics metrics = metricsCollector.snapshot();
        long totalMs = metrics.getModelLoadMs() + metrics.getInferenceMs();

        String message = switch (submission.status()) {
            case DISPATCHED -> submission.result().message();
            case QUEUED -> "Queued: agent slot busy, %d step(s) pending. %s".formatted(
                    scheduler.pendingCount(), submission.result().message());
        };

        log.info("TaskController: type={} total={}ms {}",
                type, totalMs, metrics);

        return ResponseEntity.ok(new TaskResponse(
                UUID.randomUUID().toString(), type, submission.status(), steps.size(), message,
                metrics.getModelLoadMs(), metrics.getInferenceMs(), metrics.isSwapOccurred()));
    }

    private record ErrorResponse(int status, String error, String message) {
    }

    private static AgentKind mapTypeToKind(TaskType type) {
        return switch (type) {
            case CODING -> AgentKind.CODING;
            case RESEARCH -> AgentKind.RESEARCH;
            case DATA -> AgentKind.DATA;
            case WRITING -> AgentKind.WRITING;
        };
    }
}
