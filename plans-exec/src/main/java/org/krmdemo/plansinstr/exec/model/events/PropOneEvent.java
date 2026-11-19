package org.krmdemo.plansinstr.exec.model.events;

import org.krmdemo.plansinstr.core.model.PlanExecution;
import org.krmdemo.plansinstr.core.model.ThresholdsInfo;
import org.krmdemo.plansinstr.core.model.enums.AuditStatus;
import org.krmdemo.plansinstr.core.model.events.AuditEvent;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Check one: the initial verification, which also retrieves the balance. Derives
 * {@code derivedPropOne} as {@code value - thresholdMax} of the plan (balance − T), which
 * {@link BalanceEvent} splits on; {@code null} when the plan has no threshold.
 *
 * <p>Statuses: {@code CHECK_ONE_PASSED}, {@code CHECK_ONE_FAILED}, {@code CHECK_ONE_TIMEOUT}.
 *
 * <p>Register it with the mapper: {@code JsonMapper.builder().registerSubtypes(PropOneEvent.class)}.
 */
@JsonTypeName("PROP_ONE")
public record PropOneEvent(Instant at, AuditStatus status, BigDecimal value, String error) implements AuditEvent {

    public PropOneEvent {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(status, "status");
        if (status.carriesData()) {
            Objects.requireNonNull(value, "value");
        }
    }

    public static PropOneEvent passed(Instant at, String value) {
        return new PropOneEvent(at, AuditStatus.CHECK_ONE_PASSED, new BigDecimal(value), null);
    }

    public static PropOneEvent failed(Instant at, String error) {
        return new PropOneEvent(at, AuditStatus.CHECK_ONE_FAILED, null, error);
    }

    public static PropOneEvent timeout(Instant at, String error) {
        return new PropOneEvent(at, AuditStatus.CHECK_ONE_TIMEOUT, null, error);
    }

    /** {@code CHECK_ONE_TIMEOUT} for a timeout, {@code CHECK_ONE_FAILED} for anything else. */
    public static PropOneEvent failed(Instant at, Throwable cause) {
        return Failures.isTimeout(cause) ? timeout(at, String.valueOf(cause)) : failed(at, String.valueOf(cause));
    }

    @Override
    public PlanExecution derive(PlanExecution input) {
        ThresholdsInfo thresholds = input.planInstr().thresholdInfo();
        BigDecimal thresholdMax = thresholds == null ? null : thresholds.thresholdMax();
        return input.withDerivedPropOne(thresholdMax == null ? null : value.subtract(thresholdMax));
    }
}
