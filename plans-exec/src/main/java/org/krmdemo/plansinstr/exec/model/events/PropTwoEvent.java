package org.krmdemo.plansinstr.exec.model.events;

import org.krmdemo.plansinstr.core.model.PlanExecution;
import org.krmdemo.plansinstr.core.model.enums.AuditStatus;
import org.krmdemo.plansinstr.core.model.events.AuditEvent;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * The branch check: check low in {@code LOW_BALANCE}, check high in {@code HIGH_BALANCE}.
 * Derives {@code derivedPropTwo}. The factories take {@code from}, the execution's status
 * when the check ran, and pick the branch's status from it:
 * {@code CHECK_LOW_PASSED} / {@code CHECK_LOW_FAILED} / {@code CHECK_LOW_TIMEOUT} or the
 * {@code CHECK_HIGH_*} ones.
 *
 * <p>Register it with the mapper: {@code JsonMapper.builder().registerSubtypes(PropTwoEvent.class)}.
 */
@JsonTypeName("PROP_TWO")
public record PropTwoEvent(Instant at, AuditStatus status, BigDecimal value, String error) implements AuditEvent {

    public PropTwoEvent {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(status, "status");
        if (status.carriesData()) {
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * @throws IllegalStateException when {@code from} is not {@code LOW_BALANCE} or {@code HIGH_BALANCE}
     */
    public static PropTwoEvent passed(Instant at, AuditStatus from, String value) {
        return new PropTwoEvent(at, from.checkPassed(), new BigDecimal(value), null);
    }

    public static PropTwoEvent failed(Instant at, AuditStatus from, String error) {
        return new PropTwoEvent(at, from.failure(false), null, error);
    }

    public static PropTwoEvent timeout(Instant at, AuditStatus from, String error) {
        return new PropTwoEvent(at, from.failure(true), null, error);
    }

    public static PropTwoEvent failed(Instant at, AuditStatus from, Throwable cause) {
        return new PropTwoEvent(at, from.failure(Failures.isTimeout(cause)), null, String.valueOf(cause));
    }

    @Override
    public PlanExecution derive(PlanExecution input) {
        return input.withDerivedPropTwo(value);
    }
}
