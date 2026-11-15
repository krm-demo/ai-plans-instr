package plansinstr.persistence;

import plansinstr.model.PlanInstructions;
import plansinstr.model.PlanSearchCriteria;
import plansinstr.model.PlanType;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * All persistence of {@link PlanInstructions}, using Spring's {@link JdbcClient} (no JPA).
 *
 * <p>Table {@code plan_instructions}: {@code plan_id} (UUIDv7, primary key), {@code payload}
 * (the whole record as JSONB), {@code created_at}, {@code updated_at} and {@code version}.
 */
@Repository
public class PlanJdbcClient {

    private static final String COLUMNS = "plan_id, payload, created_at, updated_at, version";
    private static final String SELECT = "SELECT " + COLUMNS + " FROM plan_instructions";

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;
    private final UuidV7Generator ids = new UuidV7Generator();

    public PlanJdbcClient(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    /** Loads a plan by primary key. */
    public Optional<PlanInstructions> load(UUID planId) {
        return jdbc.sql(SELECT + " WHERE plan_id = :planId")
                .param("planId", planId)
                .query(this::mapRow)
                .optional();
    }

    /**
     * Creates the plan when {@code version} is null, otherwise updates it.
     *
     * @return the stored state, including generated id, timestamps and the new version
     * @throws IllegalArgumentException when creating with a planId, or updating without one
     * @throws PlanNotFoundException when updating a plan that does not exist
     * @throws OptimisticLockingFailureException when the stored version differs from the
     *         one supplied
     */
    public PlanInstructions save(PlanInstructions plan) {
        return plan.version() == null ? insert(plan) : update(plan);
    }

    private PlanInstructions insert(PlanInstructions plan) {
        if (plan.planId() != null) {
            throw new IllegalArgumentException("planId is generated; it must be null when creating a plan");
        }
        UUID id = ids.next(clock.millis());
        // created_at comes from the id itself, so sorting by plan_id and by created_at agree.
        LocalDateTime now = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(UuidV7Generator.timestampMillis(id)), ZoneOffset.UTC);
        PlanInstructions stored = new PlanInstructions(
                id, plan.planType(), plan.planStatus(), plan.userId(), plan.ddaAccInfo(),
                plan.invAccInfo(), plan.currency(), plan.thresholdInfo(), plan.restInfo(), now, now, 0L);

        jdbc.sql("""
                        INSERT INTO plan_instructions (plan_id, payload, created_at, updated_at, version)
                        VALUES (:planId, CAST(:payload AS jsonb), :now, :now, 0)""")
                .param("planId", id)
                .param("payload", json.writeValueAsString(stored))
                .param("now", now)
                .update();
        return stored;
    }

    private PlanInstructions update(PlanInstructions plan) {
        if (plan.planId() == null) {
            throw new IllegalArgumentException("planId is required when version is set");
        }
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
        // The merge keeps the system fields inside payload in step with the columns.
        List<PlanInstructions> rows = jdbc.sql("""
                        UPDATE plan_instructions
                           SET payload = CAST(:payload AS jsonb)
                                         || jsonb_build_object('planId', plan_id,
                                                               'createdAt', created_at,
                                                               'updatedAt', CAST(:now AS timestamp),
                                                               'version', version + 1),
                               updated_at = :now,
                               version = version + 1
                         WHERE plan_id = :planId AND version = :version
                        RETURNING %s""".formatted(COLUMNS))
                .param("planId", plan.planId())
                .param("version", plan.version())
                .param("payload", json.writeValueAsString(plan))
                .param("now", now)
                .query(this::mapRow)
                .list();
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        if (load(plan.planId()).isEmpty()) {
            throw new PlanNotFoundException(plan.planId());
        }
        throw new OptimisticLockingFailureException(
                "Plan " + plan.planId() + " was modified concurrently (expected version "
                        + plan.version() + ")");
    }

    /** Finds the most recently updated non-cancelled plan of a user for a plan type. */
    public Optional<PlanInstructions> lookup(String userId, PlanType planType) {
        return jdbc.sql(SELECT + """
                         WHERE payload ->> 'userId' = :userId
                           AND payload ->> 'planType' = :planType
                           AND payload ->> 'planStatus' <> 'CANCELLED'
                         ORDER BY updated_at DESC
                         LIMIT 1""")
                .param("userId", userId)
                .param("planType", planType.name())
                .query(this::mapRow)
                .optional();
    }

    /** Returns one page of plans matching all supplied criteria, newest first. */
    public List<PlanInstructions> search(PlanSearchCriteria c) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        Map<String, Object> p = new HashMap<>();

        if (c.userId() != null) {
            sql.append(" AND payload ->> 'userId' = :userId");
            p.put("userId", c.userId());
        }
        if (!c.planTypes().isEmpty()) {
            sql.append(" AND payload ->> 'planType' IN (:planTypes)");
            p.put("planTypes", c.planTypes().stream().map(Enum::name).toList());
        }
        if (!c.planStatuses().isEmpty()) {
            sql.append(" AND payload ->> 'planStatus' IN (:planStatuses)");
            p.put("planStatuses", c.planStatuses().stream().map(Enum::name).toList());
        }
        if (c.currency() != null) {
            sql.append(" AND payload ->> 'currency' = :currency");
            p.put("currency", c.currency().getCurrencyCode());
        }
        if (c.ddaAccNum() != null) {
            sql.append(" AND payload -> 'ddaAccInfo' ->> 'ddaAccNum' = :ddaAccNum");
            p.put("ddaAccNum", c.ddaAccNum());
        }
        if (c.invAccNum() != null) {
            sql.append(" AND payload -> 'invAccInfo' ->> 'invAccNum' = :invAccNum");
            p.put("invAccNum", c.invAccNum());
        }
        if (c.createdFrom() != null) {
            sql.append(" AND created_at >= :createdFrom");
            p.put("createdFrom", c.createdFrom());
        }
        if (c.createdTo() != null) {
            sql.append(" AND created_at < :createdTo");
            p.put("createdTo", c.createdTo());
        }
        // plan_id is a UUIDv7, so this is also newest-first by creation time.
        sql.append(" ORDER BY plan_id DESC LIMIT :limit OFFSET :offset");
        p.put("limit", c.limit());
        p.put("offset", c.offset());

        return jdbc.sql(sql.toString()).params(p).query(this::mapRow).list();
    }

    /** Deserializes payload, then lets the real columns override the system fields. */
    private PlanInstructions mapRow(ResultSet rs, int rowNum) throws SQLException {
        PlanInstructions p = json.readValue(rs.getString("payload"), PlanInstructions.class);
        return new PlanInstructions(
                rs.getObject("plan_id", UUID.class),
                p.planType(), p.planStatus(), p.userId(), p.ddaAccInfo(), p.invAccInfo(),
                p.currency(), p.thresholdInfo(), p.restInfo(),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getLong("version"));
    }
}
