package com.example.plans.exec;

import com.example.plans.execution.AuditEvent;
import com.example.plans.execution.AuditStatus;
import com.example.plans.execution.PlanExecution;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** Example event: derives {@code derivedPropTwo}; COMPLETED marks the end of the saga. */
@JsonTypeName("PROP_TWO")
record PropTwoEvent(Instant at, AuditStatus status, BigDecimal value, String error) implements AuditEvent {

    PropTwoEvent {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(status, "status");
        if (status.carriesData()) {
            Objects.requireNonNull(value, "value");
        }
    }

    static PropTwoEvent completed(Instant at, String value) {
        return new PropTwoEvent(at, AuditStatus.COMPLETED, new BigDecimal(value), null);
    }

    static PropTwoEvent failed(Instant at, Throwable cause) {
        return new PropTwoEvent(at, AuditStatus.PROP_TWO_FAILED, null, String.valueOf(cause));
    }

    @Override
    public PlanExecution derive(PlanExecution input) {
        return input.withDerivedPropTwo(value);
    }
}
