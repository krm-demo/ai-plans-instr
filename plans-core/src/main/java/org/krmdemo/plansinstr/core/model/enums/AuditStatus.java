package org.krmdemo.plansinstr.core.model.enums;

import org.krmdemo.plansinstr.core.model.PlanExecution;

import java.math.BigDecimal;

/**
 * Every status a {@link PlanExecution} can be in. Each value carries a {@link Kind}, so the
 * merge rule asks {@code isFailure()} instead of listing constants.
 *
 * <p>The state machine (see {@code plans-core/docs/audit-status/}):
 * <pre>
 * SCHEDULED ─► CHECK_ONE_PASSED ─┬─ balance &lt; T ─► LOW_BALANCE  ─► CHECK_LOW_PASSED  ─► SWEPT_IN
 *     │                          └─ balance ≥ T ─► HIGH_BALANCE ─► CHECK_HIGH_PASSED ─► SWEPT_OUT
 *     ⏚ CHECK_ONE_FAILED / CHECK_ONE_TIMEOUT        ⏚ CHECK_LOW_* / CHECK_HIGH_* FAILED / TIMEOUT
 * </pre>
 * The transitions are methods of the enum ({@link #balance}, {@link #checkPassed},
 * {@link #swept}, {@link #failure}), so events do not repeat the diagram.
 *
 * <p>Statuses are stored by name: never rename a constant that may already be stored.
 */
public enum AuditStatus {

    SCHEDULED(Kind.STAGE),
    CHECK_ONE_PASSED(Kind.STAGE),
    CHECK_ONE_FAILED(Kind.FAILURE),
    CHECK_ONE_TIMEOUT(Kind.FAILURE),

    LOW_BALANCE(Kind.STAGE),
    CHECK_LOW_PASSED(Kind.STAGE),
    CHECK_LOW_FAILED(Kind.FAILURE),
    CHECK_LOW_TIMEOUT(Kind.FAILURE),
    SWEPT_IN(Kind.SUCCESS),

    HIGH_BALANCE(Kind.STAGE),
    CHECK_HIGH_PASSED(Kind.STAGE),
    CHECK_HIGH_FAILED(Kind.FAILURE),
    CHECK_HIGH_TIMEOUT(Kind.FAILURE),
    SWEPT_OUT(Kind.SUCCESS);

    public enum Kind {
        /** Progress inside the saga ("transient" in the diagram); the event carries data. */
        STAGE,
        /** Execution finished successfully. */
        SUCCESS,
        /** Execution failed for good ("grounded"); the first one sticks. */
        FAILURE
    }

    private final Kind kind;

    AuditStatus(Kind kind) {
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public boolean isFailure() {
        return kind == Kind.FAILURE;
    }

    /** A success or a failure: the execution will not be claimed again. */
    public boolean isFinal() {
        return kind == Kind.SUCCESS || kind == Kind.FAILURE;
    }

    /** Whether an event with this status carries data that derived properties are computed from. */
    public boolean carriesData() {
        return kind == Kind.STAGE || kind == Kind.SUCCESS;
    }

    /**
     * The balance split after {@link #CHECK_ONE_PASSED}: {@link #LOW_BALANCE} when the balance is
     * below the threshold T, {@link #HIGH_BALANCE} otherwise (balance ≥ T).
     *
     * @param balanceMinusThreshold balance − T, i.e. {@code derivedPropOne}
     * @throws IllegalStateException when it is {@code null} (the plan has no threshold)
     */
    public static AuditStatus balance(BigDecimal balanceMinusThreshold) {
        if (balanceMinusThreshold == null) {
            throw new IllegalStateException("no threshold: cannot tell a low balance from a high one");
        }
        return balanceMinusThreshold.signum() < 0 ? LOW_BALANCE : HIGH_BALANCE;
    }

    /** The branch check passed: {@code LOW_BALANCE → CHECK_LOW_PASSED}, {@code HIGH_BALANCE → CHECK_HIGH_PASSED}. */
    public AuditStatus checkPassed() {
        return switch (this) {
            case LOW_BALANCE -> CHECK_LOW_PASSED;
            case HIGH_BALANCE -> CHECK_HIGH_PASSED;
            default -> throw noTransition("checkPassed");
        };
    }

    /** The sweep: {@code CHECK_LOW_PASSED → SWEPT_IN}, {@code CHECK_HIGH_PASSED → SWEPT_OUT}. */
    public AuditStatus swept() {
        return switch (this) {
            case CHECK_LOW_PASSED -> SWEPT_IN;
            case CHECK_HIGH_PASSED -> SWEPT_OUT;
            default -> throw noTransition("swept");
        };
    }

    /**
     * Where a failure in this status grounds out: the failure pair of the checkpoint this status
     * belongs to ({@code *_TIMEOUT} when {@code timeout}). A failure status stays itself (the first
     * failure sticks).
     */
    public AuditStatus failure(boolean timeout) {
        return switch (this) {
            case SCHEDULED, CHECK_ONE_PASSED -> timeout ? CHECK_ONE_TIMEOUT : CHECK_ONE_FAILED;
            case LOW_BALANCE, CHECK_LOW_PASSED -> timeout ? CHECK_LOW_TIMEOUT : CHECK_LOW_FAILED;
            case HIGH_BALANCE, CHECK_HIGH_PASSED -> timeout ? CHECK_HIGH_TIMEOUT : CHECK_HIGH_FAILED;
            case CHECK_ONE_FAILED, CHECK_ONE_TIMEOUT, CHECK_LOW_FAILED, CHECK_LOW_TIMEOUT,
                 CHECK_HIGH_FAILED, CHECK_HIGH_TIMEOUT -> this;
            case SWEPT_IN, SWEPT_OUT -> throw noTransition("failure");
        };
    }

    /**
     * The derived status after an event: the incoming status overwrites the current one,
     * except that the first failure sticks. A {@code null} incoming status (an event this
     * build does not know) leaves the current status unchanged.
     */
    public static AuditStatus merge(AuditStatus current, AuditStatus incoming) {
        if (incoming == null) {
            return current;
        }
        if (current != null && current.isFailure()) {
            return current;
        }
        return incoming;
    }

    private IllegalStateException noTransition(String transition) {
        return new IllegalStateException("no '" + transition + "' transition from " + this);
    }
}
