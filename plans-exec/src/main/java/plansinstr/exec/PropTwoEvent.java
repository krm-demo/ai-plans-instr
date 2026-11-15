package plansinstr.exec;

import com.fasterxml.jackson.annotation.JsonTypeName;
import plansinstr.execution.AuditEvent;
import plansinstr.execution.AuditStatus;
import plansinstr.execution.PlanExecution;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Example event. Derives {@code derivedPropTwo}; {@code COMPLETED} marks the end of the saga.
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

    public static PropTwoEvent retrieved(Instant at, String value) {
        return new PropTwoEvent(at, AuditStatus.PROP_TWO_RETRIEVED, new BigDecimal(value), null);
    }

    public static PropTwoEvent completed(Instant at, String value) {
        return new PropTwoEvent(at, AuditStatus.COMPLETED, new BigDecimal(value), null);
    }

    public static PropTwoEvent failed(Instant at, String error) {
        return new PropTwoEvent(at, AuditStatus.PROP_TWO_FAILED, null, error);
    }

    public static PropTwoEvent failed(Instant at, Throwable cause) {
        return failed(at, String.valueOf(cause));
    }

    @Override
    public PlanExecution derive(PlanExecution input) {
        return input.withDerivedPropTwo(value);
    }
}
