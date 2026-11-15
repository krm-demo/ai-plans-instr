package org.krmdemo.plansinstr.exec;

import org.krmdemo.plansinstr.core.model.PlanExecution;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Same contract as the Postgres store, kept in memory, with leases on the given clock. */
final class InMemoryPlanExecutionStore implements PlanExecutionStore {

    final Map<UUID, PlanExecution> rows = new LinkedHashMap<>();
    final Map<UUID, Boolean> finished = new LinkedHashMap<>();
    final Map<UUID, Instant> visibleAt = new LinkedHashMap<>();
    final List<String> log = new ArrayList<>();
    private final Clock clock;

    InMemoryPlanExecutionStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized PlanExecution insert(PlanExecution execution) {
        PlanExecution stored = execution.withVersion(1L);
        rows.put(stored.executionId(), stored);
        finished.put(stored.executionId(), false);
        visibleAt.put(stored.executionId(), stored.scheduledAt());
        log.add("insert " + stored.executionId());
        return stored;
    }

    @Override
    public synchronized List<PlanExecution> claimDue(int limit, Duration lease) {
        List<PlanExecution> claimed = new ArrayList<>();
        for (PlanExecution row : rows.values()) {
            Instant now = clock.instant();
            if (claimed.size() < limit && !finished.get(row.executionId())
                    && !visibleAt.get(row.executionId()).isAfter(now)) {
                visibleAt.put(row.executionId(), now.plus(lease));
                PlanExecution next = row.withVersion(row.version() + 1);
                rows.put(next.executionId(), next);
                claimed.add(next);
            }
        }
        return claimed;
    }

    @Override
    public synchronized PlanExecution checkpoint(PlanExecution execution, Duration lease) {
        log.add("checkpoint " + execution.auditStatus());
        return update(execution, JdbcPlanExecutionStore.isFinished(execution));
    }

    @Override
    public synchronized PlanExecution complete(PlanExecution done, Optional<PlanExecution> next) {
        log.add("complete " + done.auditStatus());
        PlanExecution stored = update(done, true);
        next.ifPresent(this::insert);
        return stored;
    }

    private PlanExecution update(PlanExecution execution, boolean done) {
        PlanExecution current = rows.get(execution.executionId());
        if (current == null || !current.version().equals(execution.version())) {
            throw new LeaseLostException("version mismatch for " + execution.executionId());
        }
        PlanExecution stored = execution.withVersion(execution.version() + 1);
        rows.put(stored.executionId(), stored);
        finished.put(stored.executionId(), done);
        return stored;
    }
}
