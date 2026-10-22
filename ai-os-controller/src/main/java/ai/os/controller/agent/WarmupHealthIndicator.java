package ai.os.controller.agent;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Actuator readiness gate: reports {@code UP} only after the startup warmup inference has
 * completed successfully (or warmup is disabled). While the warmup model is still loading,
 * elastic load balancers and health-polling watchers will not mark the instance ready —
 * meaning a client that retries until "UP" can expect the first real request to already have
 * a warm GPU pipeline.
 */
@Component
public class WarmupHealthIndicator implements HealthIndicator {

    private final StartupWarmup startupWarmup;

    public WarmupHealthIndicator(StartupWarmup startupWarmup) {
        this.startupWarmup = startupWarmup;
    }

    @Override
    public Health health() {
        return switch (startupWarmup.getPhase()) {
            case SUCCESS, DISABLED -> Health.up()
                    .withDetail("warmup", startupWarmup.getPhase().name())
                    .build();
            case RUNNING -> Health.down()
                    .withDetail("warmup", "RUNNING")
                    .build();
            case FAILED -> Health.down()
                    .withDetail("warmup", "FAILED")
                    .withDetail("error", startupWarmup.getError())
                    .build();
            case PENDING -> Health.down()
                    .withDetail("warmup", "PENDING")
                    .build();
        };
    }
}