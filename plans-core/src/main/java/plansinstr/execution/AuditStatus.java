package plansinstr.execution;

/**
 * Every status a {@link PlanExecution} can be in: stage, transient (retry) and completion
 * statuses in one enum. Each value carries a {@link Kind}, so the merge rule asks
 * {@code isFailure()} instead of listing constants.
 *
 * <p>Statuses of a new retriever are added here, so a new retriever means a change of this
 * library as well.
 */
public enum AuditStatus {

    SCHEDULED(Kind.STAGE),
    STARTED(Kind.STAGE),

    PROP_ONE_RETRIEVED(Kind.STAGE),
    PROP_TWO_RETRIEVED(Kind.STAGE),

    PROP_ONE_RETRY(Kind.TRANSIENT),
    PROP_TWO_RETRY(Kind.TRANSIENT),

    COMPLETED(Kind.SUCCESS),

    PROP_ONE_FAILED(Kind.FAILURE),
    PROP_TWO_FAILED(Kind.FAILURE);

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
