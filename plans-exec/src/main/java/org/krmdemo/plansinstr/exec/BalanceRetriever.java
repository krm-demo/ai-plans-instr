package org.krmdemo.plansinstr.exec;

import org.krmdemo.plansinstr.core.model.PlanExecution;
import org.krmdemo.plansinstr.exec.model.events.BalanceEvent;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.Objects;

/**
 * The balance split as a saga stage of its own, right after check one: reads
 * {@code derivedPropOne} (balance − T) and answers {@code LOW_BALANCE} or {@code HIGH_BALANCE}.
 * Calls nothing.
 */
public final class BalanceRetriever implements Retriever<BalanceEvent> {

    private final Clock clock;

    public BalanceRetriever(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Mono<BalanceEvent> retrieve(PlanExecution input) {
        return Mono.fromCallable(() -> BalanceEvent.of(clock.instant(), input));
    }

    @Override
    public BalanceEvent failed(PlanExecution input, Throwable cause) {
        return BalanceEvent.failed(clock.instant(), input.auditStatus(), cause);
    }
}
