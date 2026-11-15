package com.example.plans.execution;

/**
 * Every status a {@link PlanExecution} can be in: stage, transient (retry) and completion
 * statuses in one enum. Each value carries a {@link Kind}, so the merge rule asks
 * {@code isFailure()} instead of listing constants.
 */
public enum AuditStatus {

    SCHEDULED(Kind.STAGE),
    STARTED(Kind.STAGE),

    DDA_BALANCE_RETRIEVED(Kind.STAGE),
    INV_POSITIONS_RETRIEVED(Kind.STAGE),
    SWEEP_ORDER_PLACED(Kind.STAGE),

    DDA_BALANCE_RETRY(Kind.TRANSIENT),
    INV_POSITIONS_RETRY(Kind.TRANSIENT),
    SWEEP_ORDER_RETRY(Kind.TRANSIENT),

    COMPLETED(Kind.SUCCESS),
    NOTHING_TO_SWEEP(Kind.SUCCESS),

    DDA_BALANCE_FAILED(Kind.FAILURE),
    INV_POSITIONS_FAILED(Kind.FAILURE),
    SWEEP_ORDER_FAILED(Kind.FAILURE);

    public enum Kind {
        /** Progress inside the saga; the event carries data. */
        STAGE,
        /** A retriever will be retried; the event carries no data and is not sticky. */
        TRANSIENT,
        /** Execution finished successfully. */
        SUCCESS,
        /** Execution failed for good; the first one sticks. */
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

    /** Whether an event with this status carries data that derived properties are computed from. */
    public boolean carriesData() {
        return kind == Kind.STAGE || kind == Kind.SUCCESS;
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
}
