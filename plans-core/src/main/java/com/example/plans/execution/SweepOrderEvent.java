package com.example.plans.execution;

import com.fasterxml.jackson.annotation.JsonTypeName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Result of the sweep order retriever. Derives {@code sweepAmt}.
 *
 * @param at       when the order was placed (or the attempt ended)
 * @param status   SWEEP_ORDER_PLACED, COMPLETED, NOTHING_TO_SWEEP, SWEEP_ORDER_RETRY or
 *                 SWEEP_ORDER_FAILED
 * @param orderId  id of the placed order; {@code null} when nothing was placed
 * @param sweepAmt amount swept; required when the status carries data
 * @param error    why the order failed or will be retried; otherwise {@code null}
 */
@JsonTypeName("SWEEP_ORDER")
public record SweepOrderEvent(
        Instant at,
        AuditStatus status,
        String orderId,
        BigDecimal sweepAmt,
        String error) implements AuditEvent {

    public SweepOrderEvent {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(status, "status");
        if (status.carriesData()) {
            Objects.requireNonNull(sweepAmt, "sweepAmt");
        }
    }

    public static SweepOrderEvent completed(Instant at, String orderId, BigDecimal sweepAmt) {
        return new SweepOrderEvent(at, AuditStatus.COMPLETED, orderId, sweepAmt, null);
    }

    public static SweepOrderEvent failed(Instant at, String error) {
        return new SweepOrderEvent(at, AuditStatus.SWEEP_ORDER_FAILED, null, null, error);
    }

    @Override
    public PlanExecution.Derived derive(PlanExecution input) {
        return input.derived().withSweepAmt(sweepAmt);
    }
}
