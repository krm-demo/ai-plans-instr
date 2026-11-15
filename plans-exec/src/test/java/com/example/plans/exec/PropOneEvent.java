package com.example.plans.exec;

import com.example.plans.execution.AuditEvent;
import com.example.plans.execution.AuditStatus;
import com.example.plans.execution.PlanExecution;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** Example event: derives {@code derivedPropOne} as {@code value - thresholdMax} of the plan. */
@JsonTypeName("PROP_ONE")
record PropOneEvent(Instant at, AuditStatus status, BigDecimal value, String error) implements AuditEvent {

    PropOneEvent {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(status, "status");
        if (status.carriesData()) {
            Objects.requireNonNull(value, "value");
        }
    }

    static PropOneEvent retrieved(Instant at, String value) {
        return new PropOneEvent(at, AuditStatus.PROP_ONE_RETRIEVED, new BigDecimal(value), null);
    }

    static PropOneEvent failed(Instant at, Throwable cause) {
        return new PropOneEvent(at, AuditStatus.PROP_ONE_FAILED, null, String.valueOf(cause));
    }

    @Override
    public PlanExecution derive(PlanExecution input) {
        var thresholds = input.planInstr().thresholdInfo();
        BigDecimal max = thresholds == null ? null : thresholds.thresholdMax();
        return input.withDerivedPropOne(max == null ? value : value.subtract(max));
    }
}
