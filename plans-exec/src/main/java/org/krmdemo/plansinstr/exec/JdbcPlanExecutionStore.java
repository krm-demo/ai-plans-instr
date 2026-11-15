package org.krmdemo.plansinstr.exec;

import org.krmdemo.plansinstr.core.model.PlanExecution;
import org.krmdemo.plansinstr.core.model.enums.AuditStatus;
import org.krmdemo.plansinstr.core.persistence.jdbc.VersionedEntity;
import org.krmdemo.plansinstr.core.persistence.jdbc.VersionedEntityJdbcClient;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link PlanExecutionStore} on the Postgres table {@code plan_execution}
 * (see {@code plans/exec/db/plan_execution.sql}).
 *
 * <p>A row is a {@link VersionedEntity} with the whole execution as its JSONB {@code payload};
 * status, derived amounts and {@code finished} are copied into columns on every write. The
 * execution's {@code version} is the envelope's: it is not stored inside the payload and is put
 * into the record when a row is read. {@code last_updated_reason} is the audit status that was
 * written, or {@code CLAIMED}.
 *
 * <p>Claiming is one statement: {@code SELECT … FOR UPDATE SKIP LOCKED} picks due rows that no
 * one else is locking, and the {@code UPDATE} moves their {@code visible_at} to the end of the
 * lease and increments the version. No lock is held after it returns.
 */
public final class JdbcPlanExecutionStore implements PlanExecutionStore {

    private static final String CLAIM = """
            WITH due AS (
                SELECT id
                FROM plan_execution
                WHERE NOT finished AND visible_at <= :now
                ORDER BY visible_at, id
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
            )
            UPDATE plan_execution pe
            SET visible_at = :leaseEnd,
                version = pe.version + 1,
                last_updated_at = :now,
                last_updated_reason = 'CLAIMED'
            FROM due
            WHERE pe.id = due.id
            RETURNING pe.id, pe.version, pe.last_updated_at, pe.last_updated_reason, pe.payload
            """;

    private final JdbcClient jdbc;
    private final VersionedEntityJdbcClient<PlanExecution> entities;
    private final TransactionTemplate tx;
    private final Clock clock;

    /**
     * @param json mapper that knows the event types in use ({@code registerSubtypes}); events of
     *             other types are kept as {@code UnknownAuditEvent}
     */
    public JdbcPlanExecutionStore(DataSource dataSource, JsonMapper json, Clock clock) {
        this.jdbc = JdbcClient.create(dataSource);
        this.entities = new VersionedEntityJdbcClient<>(jdbc, json, clock, "plan_execution", PlanExecution.class);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.clock = clock;
    }

    @Override
    public PlanExecution insert(PlanExecution execution) {
        Map<String, Object> columns = columns(execution, execution.scheduledAt(), isFinished(execution));
        columns.put("scheduled_at", execution.scheduledAt());
        VersionedEntity<PlanExecution> stored = entities.insert(
                execution.executionId(), execution.withVersion(null), reason(execution), columns);
        return execution.withVersion((long) stored.version());
    }

    @Override
    public List<PlanExecution> claimDue(int limit, Duration lease) {
        Instant now = clock.instant();
        return tx.execute(status -> jdbc.sql(CLAIM)
                .param("now", utc(now))
                .param("leaseEnd", utc(now.plus(lease)))
                .param("limit", limit)
                .query((rs, rowNum) -> toExecution(entities.mapRow(rs, rowNum)))
                .list());
    }

    @Override
    public PlanExecution checkpoint(PlanExecution execution, Duration lease) {
        return update(execution, clock.instant().plus(lease), isFinished(execution));
    }

    @Override
    public PlanExecution complete(PlanExecution finished, Optional<PlanExecution> next) {
        return tx.execute(status -> {
            PlanExecution stored = update(finished, clock.instant(), true);
            next.ifPresent(this::insert);
            return stored;
        });
    }

    private PlanExecution update(PlanExecution execution, Instant visibleAt, boolean finished) {
        if (execution.version() == null) {
            throw new IllegalArgumentException("execution " + execution.executionId() + " has no version");
        }
        try {
            VersionedEntity<PlanExecution> stored = entities.update(
                    execution.executionId(), Math.toIntExact(execution.version()), execution.withVersion(null),
                    reason(execution), columns(execution, visibleAt, finished));
            return execution.withVersion((long) stored.version());
        } catch (OptimisticLockingFailureException e) {
            throw new LeaseLostException("execution " + execution.executionId()
                    + " is no longer at version " + execution.version());
        }
    }

    /** The copies of payload fields that the table keeps in columns of its own. */
    private static Map<String, Object> columns(PlanExecution execution, Instant visibleAt, boolean finished) {
        Map<String, Object> columns = new LinkedHashMap<>();   // null values are allowed
        columns.put("visible_at", visibleAt);
        columns.put("finished", finished);
        columns.put("audit_status", execution.auditStatus().name());
        columns.put("derived_prop_one", execution.derivedPropOne());
        columns.put("derived_prop_two", execution.derivedPropTwo());
        return columns;
    }

    /** {@code last_updated_reason} of a write: the status the execution has reached. */
    private static String reason(PlanExecution execution) {
        return execution.auditStatus().name();
    }

    private static PlanExecution toExecution(VersionedEntity<PlanExecution> entity) {
        return entity.payload().withVersion((long) entity.version());
    }

    /** Finished executions are never claimed again: a success, or the (sticky) first failure. */
    static boolean isFinished(PlanExecution execution) {
        AuditStatus.Kind kind = execution.auditStatus().kind();
        return kind == AuditStatus.Kind.SUCCESS || kind == AuditStatus.Kind.FAILURE;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
