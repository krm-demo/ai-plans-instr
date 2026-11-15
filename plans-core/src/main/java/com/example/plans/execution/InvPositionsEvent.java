package com.example.plans.execution;

import com.fasterxml.jackson.annotation.JsonTypeName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Result of the investment positions retriever. Derives {@code positionsValue}.
 *
 * @param at             when the positions were retrieved
 * @param status         INV_POSITIONS_RETRIEVED, INV_POSITIONS_RETRY or INV_POSITIONS_FAILED
 * @param positionsValue market value of the positions; required when the status carries data
 * @param error          why the retrieval failed or will be retried; otherwise {@code null}
 */
@JsonTypeName("INV_POSITIONS")
public record InvPositionsEvent(
        Instant at,
        AuditStatus status,
        BigDecimal positionsValue,
        String error) implements AuditEvent {

    public InvPositionsEvent {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(status, "status");
        if (status.carriesData()) {
            Objects.requireNonNull(positionsValue, "positionsValue");
        }
    }

    public static InvPositionsEvent retrieved(Instant at, BigDecimal positionsValue) {
        return new InvPositionsEvent(at, AuditStatus.INV_POSITIONS_RETRIEVED, positionsValue, null);
    }

    public static InvPositionsEvent failed(Instant at, String error) {
        return new InvPositionsEvent(at, AuditStatus.INV_POSITIONS_FAILED, null, error);
    }

    @Override
    public PlanExecution.Derived derive(PlanExecution input) {
        return input.derived().withPositionsValue(positionsValue);
    }
}
