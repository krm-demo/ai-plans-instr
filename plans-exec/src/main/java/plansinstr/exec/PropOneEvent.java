package plansinstr.exec;

import com.fasterxml.jackson.annotation.JsonTypeName;
import plansinstr.execution.AuditEvent;
import plansinstr.execution.AuditStatus;
import plansinstr.execution.PlanExecution;
import plansinstr.model.ThresholdsInfo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Example event. Derives {@code derivedPropOne} as {@code value - thresholdMax} of the plan,
 * like a DDA balance minus the threshold; {@code null} when the plan has no threshold.
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

    public static PropOneEvent retrieved(Instant at, String value) {
        return new PropOneEvent(at, AuditStatus.PROP_ONE_RETRIEVED, new BigDecimal(value), null);
    }

    public static PropOneEvent retry(Instant at, String error) {
        return new PropOneEvent(at, AuditStatus.PROP_ONE_RETRY, null, error);
    }

    public static PropOneEvent failed(Instant at, String error) {
        return new PropOneEvent(at, AuditStatus.PROP_ONE_FAILED, null, error);
    }

    public static PropOneEvent failed(Instant at, Throwable cause) {
        return failed(at, String.valueOf(cause));
    }

    @Override
    public PlanExecution derive(PlanExecution input) {
        ThresholdsInfo thresholds = input.planInstr().thresholdInfo();
        BigDecimal thresholdMax = thresholds == null ? null : thresholds.thresholdMax();
        return input.withDerivedPropOne(thresholdMax == null ? null : value.subtract(thresholdMax));
    }
}
