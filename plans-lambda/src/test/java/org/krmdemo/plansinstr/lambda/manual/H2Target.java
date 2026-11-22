package org.krmdemo.plansinstr.lambda.manual;

import org.krmdemo.plansinstr.core.model.PlanInstructions;
import org.krmdemo.plansinstr.core.model.enums.PlanStatus;
import org.krmdemo.plansinstr.core.model.enums.PlanType;
import org.krmdemo.plansinstr.core.persistence.UuidV7Generator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * H2 in memory, in this JVM. It cannot run our Postgres code (JSONB, {@code ->>}, the Flyway
 * migrations), so it has its own table, built like the DynamoDB item: the fields that the
 * searches need are columns with indexes, the plan is next to them as JSON. That H2 needs its
 * own schema and queries is the reason the tests of the real code use Zonky, a real Postgres.
 */
final class H2Target implements BenchTarget {

    private static final String COLUMNS =
            "id, version, created_at, last_updated_at, CAST(payload AS VARCHAR) AS payload ";

    private final SingleConnectionDataSource ds;
    private final JdbcClient jdbc;
    private final JsonMapper json = JsonMapper.builder().build();
    private final UuidV7Generator ids = new UuidV7Generator();
    final Duration firstConnect;

    H2Target() {
        long t0 = System.nanoTime();
        this.ds = new SingleConnectionDataSource("jdbc:h2:mem:plans;DB_CLOSE_DELAY=-1", "sa", "", true);
        this.jdbc = JdbcClient.create(ds);
        jdbc.sql("""
                CREATE TABLE plan_instructions (
                    id              UUID PRIMARY KEY,
                    version         BIGINT       NOT NULL,
                    created_at      TIMESTAMP(3) NOT NULL,
                    last_updated_at TIMESTAMP(3) NOT NULL,
                    user_id         VARCHAR(100) NOT NULL,
                    plan_type       VARCHAR(40)  NOT NULL,
                    plan_status     VARCHAR(40)  NOT NULL,
                    dda_acc_num     VARCHAR(100) NOT NULL,
                    payload         JSON         NOT NULL
                )""").update();
        jdbc.sql("CREATE INDEX plan_by_user ON plan_instructions (user_id, id)").update();
        jdbc.sql("CREATE INDEX plan_by_dda ON plan_instructions (dda_acc_num, id)").update();
        this.firstConnect = Duration.ofNanos(System.nanoTime() - t0);
    }

    @Override public String name() { return "h2"; }
    @Override public Kind kind() { return Kind.LOCAL; }

    @Override
    public String about() {
        return "H2 " + jdbc.sql("SELECT H2VERSION()").query(String.class).single()
                + " in memory, in the JVM of the test; its own table (columns + JSON), not our Postgres schema";
    }

    @Override
    public void ping() {
        jdbc.sql("SELECT 1").query(Integer.class).single();
    }

    @Override
    public PlanInstructions create(PlanInstructions p) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UUID id = ids.next(now.toInstant(ZoneOffset.UTC).toEpochMilli());
        jdbc.sql("""
                        INSERT INTO plan_instructions
                            (id, version, created_at, last_updated_at, user_id, plan_type, plan_status, dda_acc_num, payload)
                        VALUES (:id, 1, :now, :now, :user, :type, :status, :dda, :payload FORMAT JSON)""")
                .param("id", id).param("now", now)
                .param("user", p.userId()).param("type", p.planType().name()).param("status", p.planStatus().name())
                .param("dda", p.ddaAccInfo().ddaAccNum()).param("payload", payloadOf(p))
                .update();
        return withEnvelope(p, id, now, now, 1);
    }

    @Override
    public Optional<PlanInstructions> load(UUID planId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM plan_instructions WHERE id = :id")
                .param("id", planId).query(rows()).optional();
    }

    @Override
    public PlanInstructions update(PlanInstructions p) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int n = jdbc.sql("""
                        UPDATE plan_instructions
                           SET version = version + 1, last_updated_at = :now,
                               plan_type = :type, plan_status = :status, dda_acc_num = :dda,
                               payload = :payload FORMAT JSON
                         WHERE id = :id AND version = :version""")
                .param("now", now).param("type", p.planType().name()).param("status", p.planStatus().name())
                .param("dda", p.ddaAccInfo().ddaAccNum()).param("payload", payloadOf(p))
                .param("id", p.planId()).param("version", p.version())
                .update();
        if (n != 1) {
            throw new IllegalStateException("version " + p.version() + " of " + p.planId() + " is not the stored one");
        }
        return withEnvelope(p, p.planId(), p.createdAt(), now, p.version() + 1);
    }

    @Override
    public boolean delete(UUID planId) {
        return jdbc.sql("DELETE FROM plan_instructions WHERE id = :id").param("id", planId).update() > 0;
    }

    @Override
    public Optional<PlanInstructions> lookup(String userId, PlanType planType) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM plan_instructions
                         WHERE user_id = :user AND plan_type = :type AND plan_status <> 'CANCELLED'
                         ORDER BY last_updated_at DESC LIMIT 1""")
                .param("user", userId).param("type", planType.name())
                .query(rows()).optional();
    }

    @Override
    public List<PlanInstructions> byUser(String userId, PlanStatus status) {
        String where = status == null ? "" : " AND plan_status = :status";
        var q = jdbc.sql("SELECT " + COLUMNS + " FROM plan_instructions WHERE user_id = :user" + where
                + " ORDER BY id DESC LIMIT 50").param("user", userId);
        if (status != null) {
            q = q.param("status", status.name());
        }
        return q.query(rows()).list();
    }

    @Override
    public List<PlanInstructions> byDdaAccount(String ddaAccNum) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM plan_instructions WHERE dda_acc_num = :dda ORDER BY id DESC LIMIT 50")
                .param("dda", ddaAccNum).query(rows()).list();
    }

    /**
     * H2 has no {@code DISTINCT ON} (Postgres has it, few others do): the portable way is a
     * window function that numbers the plans of each user and keeps the first.
     */
    @Override
    public OptionalInt latestPlanPerUser(String userIdPrefix) {
        return OptionalInt.of(jdbc.sql("""
                        SELECT id FROM (
                            SELECT id, ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY last_updated_at DESC) AS n
                              FROM plan_instructions
                             WHERE user_id LIKE :prefix
                        ) WHERE n = 1""")
                .param("prefix", userIdPrefix + "%")
                .query(UUID.class).list().size());
    }

    @Override
    public void close() {
        jdbc.sql("SHUTDOWN").update();
        ds.destroy();
    }

    // ---------------------------------------------------------------- mapping

    private String payloadOf(PlanInstructions p) {
        return json.writeValueAsString(new PlanInstructions(null, p.planType(), p.planStatus(), p.userId(),
                p.ddaAccInfo(), p.invAccInfo(), p.currency(), p.thresholdInfo(), p.restInfo(), null, null, null));
    }

    private RowMapper<PlanInstructions> rows() {
        return (rs, i) -> withEnvelope(json.readValue(rs.getString("payload"), PlanInstructions.class),
                rs.getObject("id", UUID.class),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("last_updated_at", LocalDateTime.class),
                rs.getLong("version"));
    }

    private static PlanInstructions withEnvelope(PlanInstructions p, UUID id, LocalDateTime created,
                                                 LocalDateTime updated, long version) {
        return new PlanInstructions(id, p.planType(), p.planStatus(), p.userId(), p.ddaAccInfo(), p.invAccInfo(),
                p.currency(), p.thresholdInfo(), p.restInfo(), created, updated, version);
    }
}
