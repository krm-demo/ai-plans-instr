package org.krmdemo.plansinstr.exec.model.events;

import org.krmdemo.plansinstr.core.model.PlanExecution;
import org.krmdemo.plansinstr.core.model.enums.AuditStatus;
import org.krmdemo.plansinstr.core.model.events.AuditEvent;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.time.Instant;
import java.util.Objects;

/**
 * The sweep that ends the saga: {@code CHECK_LOW_PASSED → SWEPT_IN},
 * {@code CHECK_HIGH_PASSED → SWEPT_OUT}. A failed sweep grounds out in its branch's failure
 * pair ({@code CHECK_LOW_*} or {@code CHECK_HIGH_*}).
 *
 * <p>Register it with the mapper: {@code JsonMapper.builder().registerSubtypes(SweepEvent.class)}.
 */
@JsonTypeName("SWEEP")
public record SweepEvent(Instant at, AuditStatus status, String error) implements AuditEvent {

    public SweepEvent {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(status, "status");
    }

    /**
     * @throws IllegalStateException when {@code from} is not {@code CHECK_LOW_PASSED} or {@code CHECK_HIGH_PASSED}
     */
    public static SweepEvent swept(Instant at, AuditStatus from) {
        return new SweepEvent(at, from.swept(), null);
    }

    public static SweepEvent failed(Instant at, AuditStatus from, Throwable cause) {
        return new SweepEvent(at, from.failure(Failures.isTimeout(cause)), String.valueOf(cause));
    }

    @Override
    public PlanExecution derive(PlanExecution input) {
        return input;
    }
}
