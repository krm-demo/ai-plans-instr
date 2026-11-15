package com.example.plans.execution;

import com.example.plans.model.ThresholdsInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Result of the DDA balance retriever. Derives {@code ddaBalance} and {@code diffAmt}
 * ({@code ddaBalance - thresholdMax} of the plan; {@code null} when the plan has no
 * {@code thresholdMax}).
 *
 * @param at         when the balance was retrieved
 * @param status     DDA_BALANCE_RETRIEVED, DDA_BALANCE_RETRY or DDA_BALANCE_FAILED
 * @param ddaBalance the balance; required when the status carries data
 * @param error      why the retrieval failed or will be retried; otherwise {@code null}
 */
@JsonTypeName("DDA_BALANCE")
public record DdaBalanceEvent(
        Instant at,
        AuditStatus status,
        BigDecimal ddaBalance,
        String error) implements AuditEvent {

    public DdaBalanceEvent {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(status, "status");
        if (status.carriesData()) {
            Objects.requireNonNull(ddaBalance, "ddaBalance");
        }
    }

    public static DdaBalanceEvent retrieved(Instant at, BigDecimal ddaBalance) {
        return new DdaBalanceEvent(at, AuditStatus.DDA_BALANCE_RETRIEVED, ddaBalance, null);
    }

    public static DdaBalanceEvent retry(Instant at, String error) {
        return new DdaBalanceEvent(at, AuditStatus.DDA_BALANCE_RETRY, null, error);
    }

    public static DdaBalanceEvent failed(Instant at, String error) {
        return new DdaBalanceEvent(at, AuditStatus.DDA_BALANCE_FAILED, null, error);
    }

    @Override
    public PlanExecution.Derived derive(PlanExecution input) {
        ThresholdsInfo thresholds = input.plan().thresholdInfo();
        BigDecimal thresholdMax = thresholds == null ? null : thresholds.thresholdMax();
        BigDecimal diffAmt = thresholdMax == null ? null : ddaBalance.subtract(thresholdMax);
        return input.derived().withDdaBalance(ddaBalance, diffAmt);
    }
}
