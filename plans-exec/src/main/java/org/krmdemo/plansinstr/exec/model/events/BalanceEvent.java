package org.krmdemo.plansinstr.exec.model.events;

import org.krmdemo.plansinstr.core.model.PlanExecution;
import org.krmdemo.plansinstr.core.model.enums.AuditStatus;
import org.krmdemo.plansinstr.core.model.events.AuditEvent;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.time.Instant;
import java.util.Objects;

/**
 * The balance split after check one: {@code LOW_BALANCE} when balance &lt; T, {@code HIGH_BALANCE}
 * when balance ≥ T, read from {@code derivedPropOne} (balance − T) of the execution. Calls
 * nothing, derives nothing; produced by {@code BalanceRetriever}.
 *
 * <p>When the split cannot be made (the plan has no threshold) the event carries the check-one
 * failure: {@code CHECK_ONE_FAILED}.
 *
 * <p>Register it with the mapper: {@code JsonMapper.builder().registerSubtypes(BalanceEvent.class)}.
 */
@JsonTypeName("BALANCE")
public record BalanceEvent(Instant at, AuditStatus status, String error) implements AuditEvent {

    public BalanceEvent {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(status, "status");
    }

    /**
     * @throws IllegalStateException when {@code input} has no {@code derivedPropOne}
     */
    public static BalanceEvent of(Instant at, PlanExecution input) {
        return new BalanceEvent(at, AuditStatus.balance(input.derivedPropOne()), null);
    }

    /** The failure of the checkpoint {@code from} belongs to (check one, after {@code CHECK_ONE_PASSED}). */
    public static BalanceEvent failed(Instant at, AuditStatus from, Throwable cause) {
        return new BalanceEvent(at, from.failure(Failures.isTimeout(cause)), String.valueOf(cause));
    }

    @Override
    public PlanExecution derive(PlanExecution input) {
        return input;
    }
}
