package org.krmdemo.plansinstr.exec;

import org.krmdemo.plansinstr.core.model.PlanExecution;
import org.krmdemo.plansinstr.core.model.enums.AuditStatus;
import org.krmdemo.plansinstr.core.model.events.UnknownAuditEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * {@link PlanExecutionStore} on the Postgres table {@code plan_execution}
 * (see {@code plans/exec/db/plan_execution.sql}).
 *
 * <p>The whole execution is the JSONB {@code payload}; status, derived amounts and
 * {@code finished} are copied into columns on every write. {@code version} is a column only:
 * the column value is put into the record when a row is read.
 *
 * <p>Claiming is one statement: {@code SELECT … FOR UPDATE SKIP LOCKED} picks due rows that no
 * one else is locking, and the {@code UPDATE} moves their {@code visible_at} to the end of the
 * lease and increments the version. No lock is held after it returns.
 */
public final class JdbcPlanExecutionStore implements PlanExecutionStore {

    private static final String CLAIM = """
            WITH due AS (
                SELECT execution_id
                FROM plan_execution
                WHERE NOT finished AND visible_at <= :now
                ORDER BY visible_at, execution_id
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
            )
            UPDATE plan_execution pe
            SET visible_at = :leaseEnd,
                version = pe.version + 1
            FROM due
            WHERE pe.execution_id = due.execution_id
            RETURNING pe.payload, pe.version, pe.visible_at
            """;

    private static final String INSERT = """
            INSERT INTO plan_execution (execution_id, scheduled_at, visible_at, finished, audit_status,
                                        derived_prop_one, derived_prop_two, payload, version)
            VALUES (:id, :scheduledAt, :scheduledAt, :finished, :status, :one, :two, CAST(:payload AS jsonb), 0)
            """;

    private static final String UPDATE = """
            UPDATE plan_execution
            SET visible_at = :visibleAt,
                finished = :finished,
                audit_status = :status,
                derived_prop_one = :one,
                derived_prop_two = :two,
                payload = CAST(:payload AS jsonb),
                version = version + 1
            WHERE execution_id = :id AND version = :version
            """;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final Clock clock;

    /**
     * @param json mapper that knows the event types in use ({@code registerSubtypes}); events of
     *             other types are kept as {@code UnknownAuditEvent}
     */
    public JdbcPlanExecutionStore(DataSource dataSource, JsonMapper json, Clock clock) {
        this.jdbc = JdbcClient.create(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.json = json;
        this.clock = clock;
    }

    @Override
    public PlanExecution insert(PlanExecution execution) {
        jdbc.sql(INSERT)
                .param("id", execution.executionId())
                .param("scheduledAt", utc(execution.scheduledAt()))
                .param("finished", isFinished(execution))
                .param("status", execution.auditStatus().name())
                .param("one", execution.derivedPropOne())
                .param("two", execution.derivedPropTwo())
                .param("payload", json.writeValueAsString(execution.withVersion(null)))
                .update();
        return execution.withVersion(0L);
    }

    @Override
    public List<PlanExecution> claimDue(int limit, Duration lease) {
        Instant now = clock.instant();
        return tx.execute(status -> jdbc.sql(CLAIM)
                .param("now", utc(now))
                .param("leaseEnd", utc(now.plus(lease)))
                .param("limit", limit)
                .query(this::mapRow)
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
        int updated = jdbc.sql(UPDATE)
                .param("id", execution.executionId())
                .param("version", execution.version())
                .param("visibleAt", utc(visibleAt))
                .param("finished", finished)
                .param("status", execution.auditStatus().name())
                .param("one", execution.derivedPropOne())
                .param("two", execution.derivedPropTwo())
                .param("payload", json.writeValueAsString(execution.withVersion(null)))
                .update();
        if (updated != 1) {
            throw new LeaseLostException("execution " + execution.executionId()
                    + " is no longer at version " + execution.version());
        }
        return execution.withVersion(execution.version() + 1);
    }

    private PlanExecution mapRow(ResultSet rs, int rowNum) throws SQLException {
        return json.readValue(rs.getString("payload"), PlanExecution.class).withVersion(rs.getLong("version"));
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
