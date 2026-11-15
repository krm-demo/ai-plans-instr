package com.example.plans.exec;

import java.time.Duration;
import java.util.Objects;

/**
 * Settings of the poller and pipeline (stage settings are on {@link SweepSagaStage}).
 *
 * @param batchSize     rows claimed per poll round
 * @param pollInterval  wait after a round that found nothing
 * @param lease         how long a claimed execution stays invisible to other pollers; every
 *                      checkpoint extends it. Must be longer than the slowest stage.
 * @param maxExecutions executions run at the same time by one pipeline
 * @param errorBackoff  wait after a failed claim before the next round
 */
public record SweepSagaProperties(
        int batchSize,
        Duration pollInterval,
        Duration lease,
        int maxExecutions,
        Duration errorBackoff) {

    public SweepSagaProperties {
        if (batchSize < 1 || maxExecutions < 1) {
            throw new IllegalArgumentException("batchSize and maxExecutions must be at least 1");
        }
        Objects.requireNonNull(pollInterval, "pollInterval");
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(errorBackoff, "errorBackoff");
    }

    public static SweepSagaProperties defaults() {
        return new SweepSagaProperties(50, Duration.ofMillis(500), Duration.ofMinutes(5), 16, Duration.ofSeconds(5));
    }
}
