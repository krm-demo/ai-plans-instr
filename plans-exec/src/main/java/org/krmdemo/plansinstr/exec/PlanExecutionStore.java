package org.krmdemo.plansinstr.exec;

import org.krmdemo.plansinstr.core.model.PlanExecution;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Storage of {@link PlanExecution} used as a queue (blocking calls; the poller and pipeline run
 * them on their own scheduler).
 *
 * <p>Claiming takes a lease: the row is invisible to other pollers until the lease ends. Every
 * claim and every write increments {@code version}; a write with an older version fails with
 * {@link LeaseLostException}, because someone else claimed the row after this worker's lease
 * ended.
 */
public interface PlanExecutionStore {

    /** Stores a new execution; it becomes due at its {@code scheduledAt}. Returns it with version 1. */
    PlanExecution insert(PlanExecution execution);

    /**
     * Claims up to {@code limit} due, unfinished executions that no one else holds, and leases
     * them for {@code lease}. Concurrent callers never get the same row.
     */
    List<PlanExecution> claimDue(int limit, Duration lease);

    /** Saves progress and extends the lease. Returns the stored state with its new version. */
    PlanExecution checkpoint(PlanExecution execution, Duration lease) throws LeaseLostException;

    /**
     * In one transaction: saves the finished execution (it will not be claimed again) and inserts
     * the next one, if any. Returns the stored finished execution.
     */
    PlanExecution complete(PlanExecution finished, Optional<PlanExecution> next) throws LeaseLostException;

    /** Thrown when the row's version is not the one this worker holds. */
    final class LeaseLostException extends RuntimeException {
        public LeaseLostException(String message) {
            super(message);
        }
    }
}
