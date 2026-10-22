package ai.os.controller.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Scheduler tuning knobs.
 *
 * <p>{@code auto-drain}: when true, a virtual-thread drainer continuously drains the
 * queue while the dispatch slot is held, so tasks that arrive while another request
 * owns the slot are executed as soon as the slot frees — without waiting for a new
 * {@code submit()} call to trigger the legacy one-shot drain. With auto-drain on,
 * a QUEUED submission's {@code message} reflects its own eventual result; with it off,
 * the queue only drains when a later {@code submit()} acquires the slot
 * (the pre-existing behavior, preserved for A/B measurement).
 */
@ConfigurationProperties(prefix = "ai-os.scheduler")
public class SchedulerProperties {

    private boolean autoDrain = true;

    public boolean isAutoDrain() {
        return autoDrain;
    }

    public void setAutoDrain(boolean autoDrain) {
        this.autoDrain = autoDrain;
    }
}
