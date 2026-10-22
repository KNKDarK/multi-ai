package ai.os.controller.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import ai.os.controller.agent.Agent;
import ai.os.controller.agent.AgentKind;
import ai.os.controller.agent.AgentTask;
import ai.os.controller.agent.StepResult;
import ai.os.controller.config.SchedulerProperties;
import ai.os.controller.planner.Step;
import ai.os.controller.router.TaskType;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * In-process benchmark for the scheduler drain decision (PERFORMANCE.md item 5).
 *
 * <p>Measures three concrete, deterministic deltas between legacy (auto-drain=false)
 * and auto-drain (auto-drain=true) behavior. "Inference" is simulated with a sleep;
 * this measures pure scheduler behavior, not model latency — GPU numbers are measured
 * separately on real hardware.
 *
 * <p>Run with:
 * <pre>
 *   SCHED_BENCH=1 ./mvnw test -Dtest=SchedulerDrainBenchmark
 * </pre>
 *
 * <p>Knobs (env vars): SCHED_BENCH_TASK_MS (simulated per-task work, default 100),
 * SCHED_BENCH_N (queued follow-ups, default 10), SCHED_BENCH_RACE_TRIALS (release-race
 * probes, default 200).
 */
@Tag("benchmark")
@EnabledIfEnvironmentVariable(named = "SCHED_BENCH", matches = "1")
class SchedulerDrainBenchmark {

    private static int envInt(String name, int dflt) {
        String v = System.getenv(name);
        return v == null ? dflt : Integer.parseInt(v);
    }

    private Agent agentOf(AgentKind kind, Runnable body) {
        return new Agent() {
            @Override
            public AgentKind kind() {
                return kind;
            }

            @Override
            public StepResult execute(AgentTask task) {
                body.run();
                return new StepResult(true, "done");
            }
        };
    }

    @Test
    void drainBehaviorAutoDrainVsLegacy() throws Exception {
        int taskMs = envInt("SCHED_BENCH_TASK_MS", 100);
        int n = envInt("SCHED_BENCH_N", 10);
        int raceTrials = envInt("SCHED_BENCH_RACE_TRIALS", 200);

        long autoQueuedDuringDispatch = measureDrainTiming(true, taskMs, n);
        long legacyQueuedDuringDispatch = measureDrainTiming(false, taskMs, n);
        int autoStranded = measureRaceStranding(true, raceTrials);
        int legacyStranded = measureRaceStranding(false, raceTrials);

        System.out.println("\n=== Scheduler drain benchmark (simulated work) ===\n");

        System.out.println("-- A. Tasks queued DURING an active dispatch (the common case) --");
        System.out.printf("   auto-drain: follow-up drain completed %d ms after slot release%n",
                autoQueuedDuringDispatch);
        System.out.printf("   legacy    : follow-up drain completed %d ms after slot release%n",
                legacyQueuedDuringDispatch);
        System.out.println("   (parity expected: the active dispatch's own drain loop picks\n"
                + "    these up in both modes — auto-drain adds no benefit here)\n");

        System.out.println("-- B. Poisoned task (agent throws) while follow-ups are queued --");
        System.out.println("   measured in autoDrainSurvivesPoisonedTaskVsLegacy (deterministic):\n"
                + "   exception containment is provided by the shared per-task catch in drainAll,\n"
                + "   so BOTH modes now contain it. Versus the ORIGINAL code, where an agent\n"
                + "   exception propagated out of submit() and stranded everything queued\n"
                + "   behind it, this is a resilience fix independent of the drain flag.\n");

        System.out.printf("-- C. Release-race stranding over %d trials (task enqueued in the%n"
                + "      microsecond window between the holder's final queue poll and%n"
                + "      slot release; stranded = never runs unless new traffic arrives) --%n", raceTrials);
        System.out.printf("   auto-drain: %d/%d stranded%n", autoStranded, raceTrials);
        System.out.printf("   legacy    : %d/%d stranded%n", legacyStranded, raceTrials);
        System.out.println("   auto-drain closes this window BY CONSTRUCTION: every QUEUED\n"
                + "   submission spawns a drainer that acquires the slot when it frees,\n"
                + "   regardless of interleaving. A legacy count of 0 here just means the\n"
                + "   µs-scale window was not hit in this run; the exposure still exists.\n");

        // Sanity assertions (the printed table is the real deliverable).
        assertThat(autoQueuedDuringDispatch).isGreaterThanOrEqualTo(0);
        assertThat(legacyQueuedDuringDispatch).isGreaterThanOrEqualTo(0);
    }

    /**
     * A: N tasks enqueued while a dispatch holds the slot; measure wall time from slot
     * release until ALL follow-ups finished, per mode.
     */
    private long measureDrainTiming(boolean autoDrain, int taskMs, int n) throws Exception {
        SchedulerProperties props = new SchedulerProperties();
        props.setAutoDrain(autoDrain);
        SingleSlotAgentScheduler scheduler = new SingleSlotAgentScheduler(props);

        CountDownLatch holdLatch = new CountDownLatch(1);
        CountDownLatch firstEntered = new CountDownLatch(1);
        AtomicInteger remaining = new AtomicInteger(n);
        CountDownLatch allDone = new CountDownLatch(n);

        Agent holder = agentOf(AgentKind.CODING, () -> {
            firstEntered.countDown();
            try {
                holdLatch.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Step step = new Step(1, TaskType.CODING, "task");
        Thread holderThread = Thread.ofVirtual().unstarted(() -> scheduler.submit(step, "hold", holder));
        holderThread.start();
        assertThat(firstEntered.await(10, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(50); // settle

        for (int i = 0; i < n; i++) {
            final int idx = i;
            Agent followUp = agentOf(AgentKind.DATA, () -> {
                try {
                    Thread.sleep(taskMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                remaining.decrementAndGet();
                allDone.countDown();
            });
            assertThat(scheduler.submit(step, "q-" + idx, followUp).status())
                    .isEqualTo(SubmissionStatus.QUEUED);
        }

        long releaseAt = System.nanoTime();
        holdLatch.countDown();
        assertThat(allDone.await(60, TimeUnit.SECONDS)).isTrue();
        holderThread.join(5000);
        assertThat(scheduler.pendingCount()).isZero();
        return (System.nanoTime() - releaseAt) / 1_000_000;
    }

    /**
     * C: race probe — enqueue a task as concurrently as possible with slot release;
     * count how often it is stranded (still pending and unexecuted after 300 ms with
     * no further traffic). Statistical: legacy may show 0 even though the window exists.
     */
    private int measureRaceStranding(boolean autoDrain, int trials) throws Exception {
        int stranded = 0;
        for (int t = 0; t < trials; t++) {
            SchedulerProperties props = new SchedulerProperties();
            props.setAutoDrain(autoDrain);
            SingleSlotAgentScheduler scheduler = new SingleSlotAgentScheduler(props);

            CountDownLatch holdLatch = new CountDownLatch(1);
            CountDownLatch firstEntered = new CountDownLatch(1);
            CountDownLatch raced = new CountDownLatch(1);
            CountDownLatch followUpRan = new CountDownLatch(1);

            Agent holder = agentOf(AgentKind.CODING, () -> {
                firstEntered.countDown();
                try {
                    holdLatch.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                // Signal the racer, then yield to widen the chance of hitting the window.
                raced.countDown();
                Thread.yield();
            });

            Step step = new Step(1, TaskType.CODING, "task");
            Thread holderThread = Thread.ofVirtual().unstarted(() -> {
                try {
                    scheduler.submit(step, "hold", holder);
                } catch (RuntimeException ignored) {
                    // legacy: an exception from drain propagation is not what we measure here
                }
            });
            holderThread.start();
            assertThat(firstEntered.await(10, TimeUnit.SECONDS)).isTrue();

            Thread racer = Thread.ofVirtual().start(() -> {
                try {
                    assertThat(raced.await(30, TimeUnit.SECONDS))
                            .as("holder must signal before racing")
                            .isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("racer interrupted", e);
                }
                Agent followUp = agentOf(AgentKind.DATA, followUpRan::countDown);
                scheduler.submit(step, "race", followUp);
            });

            holdLatch.countDown();
            racer.join(10000);
            holderThread.join(10000);

            boolean ran = followUpRan.await(300, TimeUnit.MILLISECONDS);
            boolean pending = scheduler.pendingCount() > 0;
            if (!ran || pending) {
                stranded++;
            }
        }
        return stranded;
    }

    /**
     * B (deterministic, assertion form): a poisoned agent must not strand later follow-ups
     * in EITHER mode. The original code propagated agent exceptions out of submit(), which
     * aborted the drain loop and stranded everything queued behind it; the shared per-task
     * catch in drainAll fixes that for both modes. This test is the regression guard.
     */
    @Test
    void autoDrainSurvivesPoisonedTaskVsLegacy() throws Exception {
        int autoPending = poisonedScenario(true);
        int legacyPending = poisonedScenario(false);
        System.out.println("\n=== Poisoned-task containment (deterministic) ===");
        System.out.printf("   auto-drain: follow-ups stranded after poison = %d (expect 0)%n", autoPending);
        System.out.printf("   legacy    : follow-ups stranded after poison = %d (expect 0)%n", legacyPending);
        assertThat(autoPending).isZero();
        assertThat(legacyPending).isZero();
    }

    private int poisonedScenario(boolean autoDrain) throws Exception {
        SchedulerProperties props = new SchedulerProperties();
        props.setAutoDrain(autoDrain);
        SingleSlotAgentScheduler scheduler = new SingleSlotAgentScheduler(props);

        CountDownLatch holdLatch = new CountDownLatch(1);
        CountDownLatch firstEntered = new CountDownLatch(1);

        Agent holder = agentOf(AgentKind.CODING, () -> {
            firstEntered.countDown();
            try {
                holdLatch.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Agent poison = agentOf(AgentKind.DATA, () -> {
            throw new IllegalStateException("poison");
        });
        AtomicInteger healthyRuns = new AtomicInteger();
        Agent healthy = agentOf(AgentKind.DATA, healthyRuns::incrementAndGet);

        Step step = new Step(1, TaskType.CODING, "task");
        Thread holderThread = Thread.ofVirtual().unstarted(() -> {
            try {
                scheduler.submit(step, "hold", holder);
            } catch (RuntimeException expectedInLegacy) {
                // legacy: the poison propagates out of submit() to the HTTP caller
            }
        });
        holderThread.start();
        assertThat(firstEntered.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(scheduler.submit(step, "poison", poison).status()).isEqualTo(SubmissionStatus.QUEUED);
        assertThat(scheduler.submit(step, "healthy", healthy).status()).isEqualTo(SubmissionStatus.QUEUED);

        holdLatch.countDown();
        holderThread.join(10000);
        Thread.sleep(500); // give any drainer a fair window

        System.out.printf("   (%s) healthy follow-up executed %d time(s)%n",
                autoDrain ? "auto-drain" : "legacy    ", healthyRuns.get());
        return scheduler.pendingCount();
    }
}
