package ai.os.controller.scheduler;

import ai.os.controller.agent.Agent;
import ai.os.controller.agent.AgentKind;
import ai.os.controller.agent.AgentTask;
import ai.os.controller.agent.StepResult;
import ai.os.controller.config.SchedulerProperties;
import ai.os.controller.planner.Step;
import ai.os.controller.router.TaskType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SingleSlotAgentSchedulerConcurrencyTest {

    private SingleSlotAgentScheduler scheduler;
    private SchedulerProperties properties;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        properties = new SchedulerProperties();
        scheduler = new SingleSlotAgentScheduler(properties);
        executor = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    /**
     * Verifies that concurrent submit() calls are serialized: only one gets
     * DISPATCHED (holds the semaphore), the rest get QUEUED immediately
     * because tryAcquire() is non-blocking.
     */
    @Test
    void concurrentSubmissionsOnlyOneDispatchedRestQueued() throws Exception {
        int concurrency = 4;
        CountDownLatch holdLatch = new CountDownLatch(1);
        CountDownLatch firstSubmitDone = new CountDownLatch(1);
        AtomicInteger dispatched = new AtomicInteger(0);
        AtomicInteger queued = new AtomicInteger(0);

        Agent blockingAgent = new Agent() {
            @Override
            public AgentKind kind() { return AgentKind.CODING; }

            @Override
            public StepResult execute(AgentTask task) {
                try {
                    holdLatch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new StepResult(true, "done");
            }
        };

        Agent quickAgent = new Agent() {
            @Override
            public AgentKind kind() { return AgentKind.CODING; }

            @Override
            public StepResult execute(AgentTask task) {
                return new StepResult(true, "quick");
            }
        };

        Step step = new Step(1, TaskType.CODING, "task");

        // First submission: holds the semaphore, blocks inside execute()
        executor.submit(() -> {
            SubmissionResult r = scheduler.submit(step, "blocking", blockingAgent);
            if (r.status() == SubmissionStatus.DISPATCHED) dispatched.incrementAndGet();
            else queued.incrementAndGet();
            firstSubmitDone.countDown();
        });

        // Wait until first submit has entered execute() (holdLatch is already blocking)
        // then wait a bit more to ensure the semaphore is held
        Thread.sleep(100);

        // Fire remaining submissions concurrently — all should be QUEUED
        CountDownLatch allSubmitted = new CountDownLatch(concurrency - 1);
        for (int i = 0; i < concurrency - 1; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    SubmissionResult r = scheduler.submit(step, "queued-" + idx, quickAgent);
                    if (r.status() == SubmissionStatus.DISPATCHED) dispatched.incrementAndGet();
                    else queued.incrementAndGet();
                } finally {
                    allSubmitted.countDown();
                }
            });
        }

        assertThat(allSubmitted.await(5, TimeUnit.SECONDS))
                .as("All submissions should complete quickly")
                .isTrue();

        assertThat(queued.get())
                .as("Remaining %d submissions should be QUEUED".formatted(concurrency - 1))
                .isEqualTo(concurrency - 1);

        // Release the blocking agent
        holdLatch.countDown();

        // Wait for the first submit to finish and record its result
        assertThat(firstSubmitDone.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(dispatched.get())
                .as("Exactly 1 submission should be DISPATCHED")
                .isEqualTo(1);
    }

    /**
     * Verifies that a queued task eventually runs when a new submit()
     * acquires the semaphore and drains the queue.
     */
    @Test
    void queuedTaskRunsOnNextSubmitDrain() throws Exception {
        CountDownLatch holdLatch = new CountDownLatch(1);
        List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());

        Agent blockingAgent = new Agent() {
            @Override
            public AgentKind kind() { return AgentKind.CODING; }

            @Override
            public StepResult execute(AgentTask task) {
                executionOrder.add("first-start");
                try {
                    holdLatch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                executionOrder.add("first-end");
                return new StepResult(true, "first-done");
            }
        };

        Agent queuedAgent = new Agent() {
            @Override
            public AgentKind kind() { return AgentKind.CODING; }

            @Override
            public StepResult execute(AgentTask task) {
                executionOrder.add("queued-ran");
                return new StepResult(true, "queued-done");
            }
        };

        Agent drainTrigger = new Agent() {
            @Override
            public AgentKind kind() { return AgentKind.CODING; }

            @Override
            public StepResult execute(AgentTask task) {
                executionOrder.add("drain-trigger");
                return new StepResult(true, "trigger-done");
            }
        };

        Step step = new Step(1, TaskType.CODING, "task");

        // First submission holds the semaphore
        executor.submit(() -> scheduler.submit(step, "blocking", blockingAgent));
        Thread.sleep(100); // let it enter execute()

        // This submission gets QUEUED (task added to queue, but semaphore held)
        SubmissionResult queuedResult = scheduler.submit(step, "queued-task", queuedAgent);
        assertThat(queuedResult.status()).isEqualTo(SubmissionStatus.QUEUED);

        // Release the first — the queued task is NOT auto-drained,
        // but a subsequent submit() will drain it
        holdLatch.countDown();
        Thread.sleep(200); // let first finish and release semaphore

        // Third submission: acquires semaphore, drains queue (picks up the queued task)
        SubmissionResult thirdResult = scheduler.submit(step, "trigger-drain", drainTrigger);
        assertThat(thirdResult.status()).isEqualTo(SubmissionStatus.DISPATCHED);

        // Verify execution order: first ran, then queued task ran during drain, then trigger
        assertThat(executionOrder).containsExactly("first-start", "first-end", "queued-ran", "drain-trigger");
    }

    /**
     * Verifies that only one agent runs at a time by tracking concurrent
     * execution count across serialized dispatches.
     */
    @Test
    void serializedDispatchNeverOverlaps() throws Exception {
        int tasks = 6;
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        AtomicInteger currentConcurrent = new AtomicInteger(0);
        CountDownLatch allDone = new CountDownLatch(tasks);

        for (int i = 0; i < tasks; i++) {
            final int idx = i;
            executor.submit(() -> {
                Agent agent = new Agent() {
                    @Override
                    public AgentKind kind() { return AgentKind.CODING; }

                    @Override
                    public StepResult execute(AgentTask task) {
                        int c = currentConcurrent.incrementAndGet();
                        maxConcurrent.updateAndGet(prev -> Math.max(prev, c));
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        currentConcurrent.decrementAndGet();
                        return new StepResult(true, "task-" + idx);
                    }
                };
                Step step = new Step(1, TaskType.CODING, "task-" + idx);
                scheduler.submit(step, "prompt-" + idx, agent);
                allDone.countDown();
            });
        }

        assertThat(allDone.await(10, TimeUnit.SECONDS))
                .as("All tasks should complete")
                .isTrue();

        // Only one agent should have been executing at any point
        assertThat(maxConcurrent.get())
                .as("Max concurrent dispatches should be 1")
                .isEqualTo(1);
    }

    /**
     * Auto-drain mode: a task queued behind an active dispatch must execute as soon as
     * the slot frees — WITHOUT any new submit() call triggering the drain.
     */
    @Test
    void autoDrainRunsQueuedTaskWithoutNewSubmit() throws Exception {
        properties.setAutoDrain(true);
        CountDownLatch holdLatch = new CountDownLatch(1);
        CountDownLatch queuedRan = new CountDownLatch(1);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        AtomicInteger currentConcurrent = new AtomicInteger(0);

        Agent blockingAgent = new Agent() {
            @Override
            public AgentKind kind() { return AgentKind.CODING; }

            @Override
            public StepResult execute(AgentTask task) {
                try {
                    holdLatch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new StepResult(true, "first-done");
            }
        };

        Agent queuedAgent = new Agent() {
            @Override
            public AgentKind kind() { return AgentKind.CODING; }

            @Override
            public StepResult execute(AgentTask task) {
                int c = currentConcurrent.incrementAndGet();
                maxConcurrent.updateAndGet(prev -> Math.max(prev, c));
                currentConcurrent.decrementAndGet();
                queuedRan.countDown();
                return new StepResult(true, "queued-done");
            }
        };

        Step step = new Step(1, TaskType.CODING, "task");

        executor.submit(() -> scheduler.submit(step, "blocking", blockingAgent));
        Thread.sleep(100); // let the first submit enter execute() holding the slot

        SubmissionResult queuedResult = scheduler.submit(step, "queued-task", queuedAgent);
        assertThat(queuedResult.status()).isEqualTo(SubmissionStatus.QUEUED);

        // Release the first task. Auto-drain must pick up the queued task on its own.
        holdLatch.countDown();

        assertThat(queuedRan.await(5, TimeUnit.SECONDS))
                .as("Queued task should run via auto-drain without a new submit()")
                .isTrue();
        assertThat(maxConcurrent.get())
                .as("Auto-drain must never overlap dispatches")
                .isEqualTo(1);
    }

    /**
     * Legacy mode (auto-drain=false) preserves the pre-existing behavior: a task queued
     * DURING an active dispatch is drained by that dispatch's own submit() loop at its
     * end — no new submit() needed. (The only legacy gap is the narrow release race
     * window, which is measured by SchedulerDrainBenchmark, not asserted here.)
     */
    @Test
    void legacyModeDrainsQueuedTaskAtEndOfCurrentDispatch() throws Exception {
        properties.setAutoDrain(false);
        CountDownLatch holdLatch = new CountDownLatch(1);
        CountDownLatch queuedRan = new CountDownLatch(1);
        AtomicInteger queuedRuns = new AtomicInteger(0);

        Agent blockingAgent = new Agent() {
            @Override
            public AgentKind kind() { return AgentKind.CODING; }

            @Override
            public StepResult execute(AgentTask task) {
                try {
                    holdLatch.await(5, TimeUnit.SECONDS);
                }                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new StepResult(true, "first-done");
            }
        };

        Agent queuedAgent = new Agent() {
            @Override
            public AgentKind kind() { return AgentKind.CODING; }

            @Override
            public StepResult execute(AgentTask task) {
                queuedRuns.incrementAndGet();
                queuedRan.countDown();
                return new StepResult(true, "queued-done");
            }
        };

        Step step = new Step(1, TaskType.CODING, "task");

        executor.submit(() -> scheduler.submit(step, "blocking", blockingAgent));
        Thread.sleep(100);

        assertThat(scheduler.submit(step, "queued-task", queuedAgent).status())
                .isEqualTo(SubmissionStatus.QUEUED);

        holdLatch.countDown();

        // Legacy semantics: the first submit's drain loop picks the task up at dispatch end,
        // without any new submit() call.
        assertThat(queuedRan.await(5, TimeUnit.SECONDS))
                .as("Legacy mode drains tasks queued during the current dispatch at its end")
                .isTrue();
        assertThat(queuedRuns.get()).isEqualTo(1);
        assertThat(scheduler.pendingCount()).isZero();
    }

    /**
     * A queued task that throws must not wedge the drainer: later queued tasks still run.
     */
    @Test
    void autoDrainSurvivesAgentException() throws Exception {
        properties.setAutoDrain(true);
        CountDownLatch holdLatch = new CountDownLatch(1);
        CountDownLatch poisonEntered = new CountDownLatch(1);
        CountDownLatch healthyRan = new CountDownLatch(1);

        Agent blockingAgent = new Agent() {
            @Override
            public AgentKind kind() { return AgentKind.CODING; }

            @Override
            public StepResult execute(AgentTask task) {
                try {
                    holdLatch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new StepResult(true, "first-done");
            }
        };

        Agent poisonAgent = new Agent() {
            @Override
            public AgentKind kind() { return AgentKind.CODING; }

            @Override
            public StepResult execute(AgentTask task) {
                poisonEntered.countDown();
                throw new IllegalStateException("boom");
            }
        };

        Agent healthyAgent = new Agent() {
            @Override
            public AgentKind kind() { return AgentKind.CODING; }

            @Override
            public StepResult execute(AgentTask task) {
                healthyRan.countDown();
                return new StepResult(true, "healthy-done");
            }
        };

        Step step = new Step(1, TaskType.CODING, "task");

        executor.submit(() -> scheduler.submit(step, "blocking", blockingAgent));
        Thread.sleep(100);

        scheduler.submit(step, "poison", poisonAgent);
        scheduler.submit(step, "healthy", healthyAgent);

        holdLatch.countDown();

        assertThat(poisonEntered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(healthyRan.await(5, TimeUnit.SECONDS))
                .as("Healthy queued task must run even though an earlier queued task threw")
                .isTrue();
    }

    /**
     * Auto-drain throughput: N same-model follow-ups submitted while one request holds
     * the slot all complete without any further submit() calls.
     */
    @Test
    void autoDrainCompletesAllQueuedTasksAfterSlotFrees() throws Exception {
        properties.setAutoDrain(true);
        int queuedCount = 5;
        CountDownLatch holdLatch = new CountDownLatch(1);
        CountDownLatch allDone = new CountDownLatch(queuedCount);
        AtomicInteger completed = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        AtomicInteger currentConcurrent = new AtomicInteger(0);

        Agent blockingAgent = new Agent() {
            @Override
            public AgentKind kind() { return AgentKind.CODING; }

            @Override
            public StepResult execute(AgentTask task) {
                try {
                    holdLatch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new StepResult(true, "first-done");
            }
        };

        Step step = new Step(1, TaskType.CODING, "task");

        executor.submit(() -> scheduler.submit(step, "blocking", blockingAgent));
        Thread.sleep(100);

        for (int i = 0; i < queuedCount; i++) {
            final int idx = i;
            Agent quickAgent = new Agent() {
                @Override
                public AgentKind kind() { return AgentKind.CODING; }

                @Override
                public StepResult execute(AgentTask task) {
                    int c = currentConcurrent.incrementAndGet();
                    maxConcurrent.updateAndGet(prev -> Math.max(prev, c));
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    currentConcurrent.decrementAndGet();
                    completed.incrementAndGet();
                    allDone.countDown();
                    return new StepResult(true, "done-" + idx);
                }
            };
            assertThat(scheduler.submit(step, "queued-" + idx, quickAgent).status())
                    .isEqualTo(SubmissionStatus.QUEUED);
        }

        holdLatch.countDown();

        assertThat(allDone.await(10, TimeUnit.SECONDS))
                .as("All queued tasks should complete via auto-drain")
                .isTrue();
        assertThat(completed.get()).isEqualTo(queuedCount);
        assertThat(maxConcurrent.get())
                .as("Max concurrent dispatches must remain 1 under auto-drain")
                .isEqualTo(1);
    }
}
