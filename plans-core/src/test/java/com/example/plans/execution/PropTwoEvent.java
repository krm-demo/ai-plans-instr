package com.example.plans.execution;

import com.fasterxml.jackson.annotation.JsonTypeName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** Example event (moves to plan-exec with its retriever). Derives {@code derivedPropTwo}. */
@JsonTypeName("PROP_TWO")
record PropTwoEvent(Instant at, AuditStatus status, BigDecimal value, String error) implements AuditEvent {

    PropTwoEvent {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(status, "status");
        if (status.carriesData()) {
            Objects.requireNonNull(value, "value");
        }
    }

    static PropTwoEvent retrieved(Instant at, String value) {
        return new PropTwoEvent(at, AuditStatus.PROP_TWO_RETRIEVED, new BigDecimal(value), null);
    }

    static PropTwoEvent completed(Instant at, String value) {
        return new PropTwoEvent(at, AuditStatus.COMPLETED, new BigDecimal(value), null);
    }

    static PropTwoEvent failed(Instant at, String error) {
        return new PropTwoEvent(at, AuditStatus.PROP_TWO_FAILED, null, error);
    }

    @Override
    public PlanExecution derive(PlanExecution input) {
        return input.withDerivedPropTwo(value);
    }
}
