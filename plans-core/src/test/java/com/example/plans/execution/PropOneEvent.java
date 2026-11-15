package com.example.plans.execution;

import com.example.plans.model.ThresholdsInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Example event (moves to plan-exec with its retriever). Derives {@code derivedPropOne} as
 * {@code value - thresholdMax} of the plan, like a DDA balance minus the threshold.
 */
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

    static PropOneEvent retry(Instant at, String error) {
        return new PropOneEvent(at, AuditStatus.PROP_ONE_RETRY, null, error);
    }

    static PropOneEvent failed(Instant at, String error) {
        return new PropOneEvent(at, AuditStatus.PROP_ONE_FAILED, null, error);
    }

    @Override
    public PlanExecution derive(PlanExecution input) {
        ThresholdsInfo thresholds = input.planInstr().thresholdInfo();
        BigDecimal thresholdMax = thresholds == null ? null : thresholds.thresholdMax();
        return input.withDerivedPropOne(thresholdMax == null ? null : value.subtract(thresholdMax));
    }
}
