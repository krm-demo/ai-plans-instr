package org.krmdemo.plansinstr.core.model.enums;

import org.krmdemo.plansinstr.core.model.PlanExecution;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Every status a {@link PlanExecution} can be in. Each status is in exactly one of
 * {@link #TRANSIENT_STATUSES}, {@link #SUCCESS_STATUSES} and {@link #FAILED_STATUSES} (checked when
 * the class loads), so the merge rule asks {@code isFailed()} instead of listing constants.
 *
 * <p>The state machine (diagrams below; write-up in {@code plans-core/docs/audit-status/}):
 * <pre>
 * SCHEDULED ─► CHECK_ONE_PASSED ─┬─ balance &lt; T ─► LOW_BALANCE  ─► CHECK_LOW_PASSED  ─► SWEPT_IN
 *     │                          └─ balance ≥ T ─► HIGH_BALANCE ─► CHECK_HIGH_PASSED ─► SWEPT_OUT
 *     ⏚ CHECK_ONE_FAILED / CHECK_ONE_TIMEOUT        ⏚ CHECK_LOW_* / CHECK_HIGH_* FAILED / TIMEOUT
 * </pre>
 * The transitions are methods of the enum ({@link #balance}, {@link #checkPassed},
 * {@link #swept}, {@link #failure}), so events do not repeat the diagram.
 *
 * <p>Statuses are stored by name: never rename a constant that may already be stored.
 *
 * <p><img src="doc-files/audit-status-horizontal.svg" width="640" alt="AuditStatus state chart diagram">
 *
 * <p>In the diagrams every status links back to its constant here. After changing this enum run
 * {@code python3 plans-core/docs/audit-status/generate.py}; {@code AuditStatusDiagramTest}
 * fails until the diagrams match.
 *
 * @see <a href="doc-files/audit-status-horizontal.svg">State chart diagram (horizontal)</a>
 * @see <a href="doc-files/audit-status-vertical.svg">State chart diagram (vertical)</a>
 */
public enum AuditStatus {

    /** Initial: the execution is waiting to be claimed and run. */
    SCHEDULED,
    /** Initial verification passed; the balance is known. */
    CHECK_ONE_PASSED,
    /** Initial verification failed (final). */
    CHECK_ONE_FAILED,
    /** Initial verification timed out (final). */
    CHECK_ONE_TIMEOUT,

    /** The balance is below the threshold T: the Swept-In branch. */
    LOW_BALANCE,
    /** Low-balance verification passed; ready to sweep in. */
    CHECK_LOW_PASSED,
    /** Low-balance verification failed (final). */
    CHECK_LOW_FAILED,
    /** Low-balance verification timed out (final). */
    CHECK_LOW_TIMEOUT,
    /** Success: the funds were swept in (final). */
    SWEPT_IN,

    /** The balance is at or above the threshold T: the Swept-Out branch. */
    HIGH_BALANCE,
    /** High-balance verification passed; ready to sweep out. */
    CHECK_HIGH_PASSED,
    /** High-balance verification failed (final). */
    CHECK_HIGH_FAILED,
    /** High-balance verification timed out (final). */
    CHECK_HIGH_TIMEOUT,
    /** Success: the funds were swept out (final). */
    SWEPT_OUT;

    /** Progress inside the saga: the execution goes on. Events with these statuses carry data. */
    public static final Set<AuditStatus> TRANSIENT_STATUSES = Collections.unmodifiableSet(EnumSet.of(
            SCHEDULED, CHECK_ONE_PASSED,
            LOW_BALANCE, CHECK_LOW_PASSED,
            HIGH_BALANCE, CHECK_HIGH_PASSED));

    /** The execution finished successfully. */
    public static final Set<AuditStatus> SUCCESS_STATUSES = Collections.unmodifiableSet(EnumSet.of(
            SWEPT_IN, SWEPT_OUT));

    /** The execution failed for good ("grounded" in the diagram); the first failure sticks. */
    public static final Set<AuditStatus> FAILED_STATUSES = Collections.unmodifiableSet(EnumSet.of(
            CHECK_ONE_FAILED, CHECK_ONE_TIMEOUT,
            CHECK_LOW_FAILED, CHECK_LOW_TIMEOUT,
            CHECK_HIGH_FAILED, CHECK_HIGH_TIMEOUT));

    /** Successes and failures: the execution will not be claimed again. */
    public static final Set<AuditStatus> FINAL_STATUSES = Collections.unmodifiableSet(
            union(SUCCESS_STATUSES, FAILED_STATUSES));

    static {
        // The three sets must split the values exactly: a new constant missing from every set
        // (or put in two) fails here, when the class loads, not later in a merge.
        EnumSet<AuditStatus> seen = EnumSet.noneOf(AuditStatus.class);
        for (Set<AuditStatus> group : List.of(TRANSIENT_STATUSES, SUCCESS_STATUSES, FAILED_STATUSES)) {
            for (AuditStatus status : group) {
                if (!seen.add(status)) {
                    throw new IllegalStateException(status + " is in more than one status set");
                }
            }
        }
        if (!seen.equals(EnumSet.allOf(AuditStatus.class))) {
            throw new IllegalStateException("in no status set: " + EnumSet.complementOf(seen));
        }
    }

    private static EnumSet<AuditStatus> union(Set<AuditStatus> a, Set<AuditStatus> b) {
        EnumSet<AuditStatus> union = EnumSet.copyOf(a);
        union.addAll(b);
        return union;
    }

    public boolean isTransient() {
        return TRANSIENT_STATUSES.contains(this);
    }

    public boolean isSuccess() {
        return SUCCESS_STATUSES.contains(this);
    }

    public boolean isFailed() {
        return FAILED_STATUSES.contains(this);
    }

    /** A success or a failure: the execution will not be claimed again. */
    public boolean isFinal() {
        return FINAL_STATUSES.contains(this);
    }

    /** Whether an event with this status carries data that derived properties are computed from. */
    public boolean carriesData() {
        return isTransient() || isSuccess();
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
        if (current != null && current.isFailed()) {
            return current;
        }
        return incoming;
    }

    private IllegalStateException noTransition(String transition) {
        return new IllegalStateException("no '" + transition + "' transition from " + this);
    }
}
