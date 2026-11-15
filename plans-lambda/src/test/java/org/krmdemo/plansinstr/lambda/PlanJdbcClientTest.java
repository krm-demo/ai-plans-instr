package org.krmdemo.plansinstr.lambda;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.krmdemo.plansinstr.core.model.DdaAccInfo;
import org.krmdemo.plansinstr.core.model.InvAccInfo;
import org.krmdemo.plansinstr.core.model.PlanInstructions;
import org.krmdemo.plansinstr.core.model.PlanSearchCriteria;
import org.krmdemo.plansinstr.core.model.RestInfo;
import org.krmdemo.plansinstr.core.model.RuntimeInfo;
import org.krmdemo.plansinstr.core.model.ThresholdsInfo;
import org.krmdemo.plansinstr.core.model.enums.PlanStatus;
import org.krmdemo.plansinstr.core.model.enums.PlanType;
import org.krmdemo.plansinstr.core.persistence.PlanNotFoundException;
import org.krmdemo.plansinstr.core.persistence.UuidV7Generator;
import org.krmdemo.plansinstr.core.persistence.jdbc.PlanJdbcClient;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link PlanJdbcClient} against a real Postgres started in-process by Zonky (no Docker), with
 * the Flyway migrations of this module. A plan stored in the layout of V1 is there before V2
 * runs, so the migration of existing rows is tested too.
 *
 * <p>The tests share the table; each one uses a user id of its own.
 */
class PlanJdbcClientTest {

    private static final Instant T0 = Instant.parse("2026-11-15T10:00:00Z");
    private static final LocalDateTime T0_UTC = LocalDateTime.of(2026, 11, 15, 10, 0);

    private static final UUID LEGACY_ID =
            UuidV7Generator.firstOf(Instant.parse("2026-11-01T10:00:00Z").toEpochMilli());

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    private JdbcClient jdbc;
    private MutableClock clock;
    private PlanJdbcClient plans;

    @BeforeAll
    static void startPostgresAndMigrate() throws IOException {
        postgres = EmbeddedPostgres.start();
        dataSource = postgres.getPostgresDatabase();

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("1").load().migrate();
        insertPlanInLayoutOfV1();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    /** As the code before V2 wrote it: the system fields in columns and again inside the payload. */
    private static void insertPlanInLayoutOfV1() {
        LocalDateTime created = LocalDateTime.of(2026, 11, 1, 10, 0);
        LocalDateTime updated = LocalDateTime.of(2026, 11, 2, 11, 0);
        PlanInstructions legacy = withSystemFields(newPlan("legacy", PlanType.REBALANCING), LEGACY_ID, created, updated, 3L);
        JdbcClient.create(dataSource).sql("""
                        INSERT INTO plan_instructions (plan_id, payload, created_at, updated_at, version)
                        VALUES (:id, CAST(:payload AS jsonb), :created, :updated, 3)""")
                .param("id", LEGACY_ID)
                .param("payload", JsonMapper.builder().build().writeValueAsString(legacy))
                .param("created", created)
                .param("updated", updated)
                .update();
    }

    @AfterAll
    static void stopPostgres() throws IOException {
        if (postgres != null) {
            postgres.close();
        }
    }

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        clock = new MutableClock(T0);
        plans = new PlanJdbcClient(jdbc, JsonMapper.builder().build(), clock);
    }

    @Test
    @DisplayName("V2 keeps a plan stored by V1: same id and times, version + 1, no copies in the payload")
    void planFromBeforeTheMigration() {
        PlanInstructions legacy = plans.load(LEGACY_ID).orElseThrow();

        assertEquals("legacy", legacy.userId());
        assertEquals(PlanType.REBALANCING, legacy.planType());
        assertEquals(LEGACY_ID, legacy.planId());
        assertEquals(LocalDateTime.of(2026, 11, 1, 10, 0), legacy.createdAt());
        assertEquals(LocalDateTime.of(2026, 11, 2, 11, 0), legacy.updatedAt());
        assertEquals(4L, legacy.version());
        assertEquals(false, jdbc.sql("""
                        SELECT jsonb_exists_any(payload, ARRAY['planId', 'createdAt', 'updatedAt', 'version'])
                        FROM plan_instructions WHERE id = :id""")
                .param("id", LEGACY_ID).query(Boolean.class).single());

        PlanInstructions updated = plans.save(withStatus(legacy, PlanStatus.PAUSED), "paused after the migration");
        assertEquals(5L, updated.version());
        assertEquals(legacy.createdAt(), updated.createdAt());
        assertEquals(T0_UTC, updated.updatedAt());
    }

    @Test
    @DisplayName("create: generated id, version 1, both times from the clock, the reason in its column")
    void create() {
        PlanInstructions saved = plans.save(newPlan("u-create", PlanType.RECURRING_INVESTMENT), "created by a test");

        assertEquals(7, saved.planId().version());
        assertEquals(1L, saved.version());
        assertEquals(T0_UTC, saved.createdAt());
        assertEquals(T0_UTC, saved.updatedAt());
        assertEquals(Optional.of(saved), plans.load(saved.planId()));
        assertEquals("created by a test", reasonOf(saved.planId()));
    }

    @Test
    @DisplayName("update: version + 1, updatedAt moves, createdAt stays; an old version is rejected")
    void update() {
        PlanInstructions v1 = plans.save(newPlan("u-update", PlanType.ONE_TIME_INVESTMENT), "created");
        clock.advance(Duration.ofMinutes(5));

        PlanInstructions v2 = plans.save(withStatus(v1, PlanStatus.PAUSED), "paused");

        assertEquals(2L, v2.version());
        assertEquals(PlanStatus.PAUSED, v2.planStatus());
        assertEquals(T0_UTC, v2.createdAt());
        assertEquals(T0_UTC.plusMinutes(5), v2.updatedAt());
        assertEquals(Optional.of(v2), plans.load(v1.planId()));
        assertEquals("paused", reasonOf(v1.planId()));

        assertThrows(OptimisticLockingFailureException.class,
                () -> plans.save(withStatus(v1, PlanStatus.CANCELLED), "stale"));
        assertEquals(Optional.of(v2), plans.load(v1.planId()));
    }

    @Test
    @DisplayName("save rejects a new plan with an id, and an update of a plan that does not exist")
    void invalidSaves() {
        PlanInstructions draft = newPlan("u-invalid", PlanType.REBALANCING);
        UUID unknownId = UuidV7Generator.firstOf(T0.toEpochMilli());

        assertThrows(IllegalArgumentException.class,
                () -> plans.save(withSystemFields(draft, unknownId, null, null, null), "id without version"));
        assertThrows(IllegalArgumentException.class,
                () -> plans.save(withSystemFields(draft, null, null, null, 1L), "version without id"));
        assertThrows(PlanNotFoundException.class,
                () -> plans.save(withSystemFields(draft, unknownId, null, null, 1L), "missing"));
    }

    @Test
    @DisplayName("lookup: the user's most recently updated plan of the type that is not cancelled")
    void lookup() {
        PlanInstructions older = plans.save(newPlan("u-lookup", PlanType.RECURRING_INVESTMENT), "created");
        clock.advance(Duration.ofMinutes(1));
        PlanInstructions newer = plans.save(newPlan("u-lookup", PlanType.RECURRING_INVESTMENT), "created");
        plans.save(newPlan("u-lookup", PlanType.REBALANCING), "created");

        assertEquals(Optional.of(newer), plans.lookup("u-lookup", PlanType.RECURRING_INVESTMENT));

        clock.advance(Duration.ofMinutes(1));
        plans.save(withStatus(newer, PlanStatus.CANCELLED), "cancelled");
        assertEquals(Optional.of(older), plans.lookup("u-lookup", PlanType.RECURRING_INVESTMENT));

        assertEquals(Optional.empty(), plans.lookup("u-lookup", PlanType.ONE_TIME_INVESTMENT));
    }

    @Test
    @DisplayName("search: newest first, and a range of creation times selects by the time inside the id")
    void searchByCreationTime() {
        PlanInstructions at10 = plans.save(newPlan("u-search", PlanType.REBALANCING), "created");
        clock.advance(Duration.ofHours(1));
        PlanInstructions at11 = plans.save(newPlan("u-search", PlanType.REBALANCING), "created");
        clock.advance(Duration.ofHours(1));
        PlanInstructions at12 = plans.save(newPlan("u-search", PlanType.REBALANCING), "created");

        assertEquals(List.of(at12, at11, at10), plans.search(criteria("u-search", null, null)));
        assertEquals(List.of(at12, at11), plans.search(criteria("u-search", T0_UTC.plusHours(1), null)));
        assertEquals(List.of(at11), plans.search(criteria("u-search", T0_UTC.plusHours(1), T0_UTC.plusHours(2))));
        assertEquals(List.of(at10), plans.search(criteria("u-search", null, T0_UTC.plusHours(1))));
    }

    // ---------------------------------------------------------------- helpers

    private String reasonOf(UUID planId) {
        return jdbc.sql("SELECT last_updated_reason FROM plan_instructions WHERE id = :id")
                .param("id", planId).query(String.class).single();
    }

    private static PlanSearchCriteria criteria(String userId, LocalDateTime createdFrom, LocalDateTime createdTo) {
        return new PlanSearchCriteria(userId, null, null, null, null, null, createdFrom, createdTo, null, null);
    }

    private static PlanInstructions newPlan(String userId, PlanType type) {
        return new PlanInstructions(null, type, PlanStatus.ACTIVE, userId,
                new DdaAccInfo("DDA-" + userId, "BANK-1"),
                new InvAccInfo("INV-" + userId, "OCCD-1"),
                Currency.getInstance("USD"),
                new ThresholdsInfo(new BigDecimal("10.50"), new BigDecimal("500"), new BigDecimal("5"),
                        new BigDecimal("100")),
                new RestInfo("test-agent/1.0", T0,
                        new RuntimeInfo("host.example", Instant.parse("2026-11-15T09:00:00Z"), "42-abc1234")),
                null, null, null);
    }

    private static PlanInstructions withStatus(PlanInstructions p, PlanStatus status) {
        return new PlanInstructions(p.planId(), p.planType(), status, p.userId(), p.ddaAccInfo(),
                p.invAccInfo(), p.currency(), p.thresholdInfo(), p.restInfo(),
                p.createdAt(), p.updatedAt(), p.version());
    }

    private static PlanInstructions withSystemFields(PlanInstructions p, UUID planId, LocalDateTime createdAt,
                                                     LocalDateTime updatedAt, Long version) {
        return new PlanInstructions(planId, p.planType(), p.planStatus(), p.userId(), p.ddaAccInfo(),
                p.invAccInfo(), p.currency(), p.thresholdInfo(), p.restInfo(), createdAt, updatedAt, version);
    }

    /** A clock that the test moves by hand. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
