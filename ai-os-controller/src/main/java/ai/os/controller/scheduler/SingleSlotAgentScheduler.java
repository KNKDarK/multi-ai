package ai.os.controller.scheduler;

import ai.os.controller.agent.Agent;
import ai.os.controller.agent.AgentTask;
import ai.os.controller.agent.StepResult;
import ai.os.controller.planner.Step;
import ai.os.controller.config.SchedulerProperties;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SingleSlotAgentScheduler implements AgentScheduler {

    private static final Logger log = LoggerFactory.getLogger(SingleSlotAgentScheduler.class);

    private record ScheduledTask(Step step, String prompt, Agent agent) {
    }

    private final ConcurrentLinkedDeque<ScheduledTask> queue = new ConcurrentLinkedDeque<>();
    private final Semaphore slot = new Semaphore(1, true);

    private final SchedulerProperties properties;

    /** Guards virtual-thread drainer creation when auto-drain is enabled. */
    private volatile boolean drainerActive;

    public SingleSlotAgentScheduler(SchedulerProperties properties) {
        this.properties = properties;
    }

    @Override
    public SubmissionResult submit(Step step, String prompt, Agent agent) {
        queue.addLast(new ScheduledTask(step, prompt, agent));
        if (!slot.tryAcquire()) {
            if (properties.isAutoDrain()) {
                ensureDrainer();
            }
            return new SubmissionResult(SubmissionStatus.QUEUED,
                    new StepResult(false, "Queued behind an active dispatch; "
                            + (properties.isAutoDrain()
                                    ? "it will run automatically when the agent slot frees up"
                                    : "it will run when the next request acquires the agent slot")));
        }
        try {
            return drainAll("submit");
        } finally {
            slot.release();
        }
    }

    /**
     * Auto-drain mode only: spawns a short-lived virtual thread that waits for the slot,
     * drains the queue, and exits once the queue is empty. Every slot-holder (submitter
     * or drainer) drains until the queue is empty before releasing, so a queued task is
     * always picked up: either by the current slot-holder's drain, or by this drainer.
     * The drainer never busy-cycles the slot — it exits as soon as it observes an empty
     * queue, leaving the slot free for the next submit() to acquire synchronously.
     */
    private void ensureDrainer() {
        if (drainerActive) {
            return;
        }
        synchronized (this) {
            if (drainerActive) {
                return;
            }
            drainerActive = true;
        }
        Thread.startVirtualThread(() -> {
            log.info("Scheduler auto-drain: drainer started for {} queued task(s)", queue.size());
            try {
                while (!queue.isEmpty()) {
                    slot.acquire(); // blocking wait for the current dispatch to finish
                    try {
                        if (!queue.isEmpty()) {
                            drainAll("auto-drain");
                        }
                    } finally {
                        slot.release();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("Scheduler auto-drain drainer interrupted; exiting");
            } finally {
                drainerActive = false;
                // A task may have raced in between this drainer's final empty-queue check
                // and the flag reset; respawn so it is guaranteed to run without new traffic.
                if (!queue.isEmpty() && properties.isAutoDrain()) {
                    ensureDrainer();
                }
            }
        });
    }

    /**
     * Drains the queue while holding the dispatch slot. Runs every queued task in order,
     * and returns the result of the LAST completed task. Never throws on agent failure:
     * a failing agent is recorded and draining continues, so one poisoned task cannot
     * wedge the queue (mirrors legacy per-submit behavior).
     */
    private SubmissionResult drainAll(String origin) {
        ScheduledTask task;
        StepResult lastResult = null;
        while ((task = queue.pollFirst()) != null) {
            log.info("Dispatching step {} (type {}) to agent {} ({})", task.step().order(),
                    task.step().type(), task.agent().kind(), origin);
            try {
                lastResult = task.agent().execute(new AgentTask(task.step(), task.prompt()));
            } catch (RuntimeException e) {
                log.error("Agent {} failed during dispatch; continuing queue drain", task.agent().kind(), e);
                lastResult = new StepResult(false, "Agent dispatch failed: " + e.getMessage());
            }
        }
        return new SubmissionResult(SubmissionStatus.DISPATCHED, lastResult);
    }

    @Override
    public int pendingCount() {
        return queue.size();
    }
}
