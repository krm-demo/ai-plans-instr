package com.example.plans.persistence;

import com.example.plans.model.DdaAccInfo;
import com.example.plans.model.InvAccInfo;
import com.example.plans.model.PlanInstructions;
import com.example.plans.model.PlanSearchCriteria;
import com.example.plans.model.PlanStatus;
import com.example.plans.model.PlanType;
import com.example.plans.model.RestInfo;
import com.example.plans.model.RuntimeInfo;
import com.example.plans.model.ThresholdsInfo;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs PlanJdbcClient against a real Postgres started in-process by Zonky's embedded-postgres
 * (no Docker needed) using the production Flyway migration. The Postgres version is chosen by
 * the property postgres.binaries.version in the parent pom.
 */
class PlanJdbcClientTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    private JdbcClient jdbc;
    private PlanJdbcClient plans;
    private MutableClock clock;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.start();
        dataSource = postgres.getPostgresDatabase();
        Flyway.configure().dataSource(dataSource).locations("classpath:plans/db/migration").load().migrate();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE plan_instructions").update();
        clock = new MutableClock(Instant.parse("2026-11-15T10:00:00Z"));
        plans = new PlanJdbcClient(jdbc, JsonMapper.builder().build(), clock);
    }

    private static PlanInstructions newPlan(String userId, PlanType type) {
        return new PlanInstructions(null, type, PlanStatus.ACTIVE, userId,
                new DdaAccInfo("DDA-" + userId, "BANK-1"),
                new InvAccInfo("INV-" + userId, "OCCD-1"),
                Currency.getInstance("USD"),
                new ThresholdsInfo(new BigDecimal("10.50"), new BigDecimal("500"), new BigDecimal("5"), new BigDecimal("100")),
                new RestInfo("test-agent/1.0", Instant.parse("2026-11-15T10:00:00Z"),
                        new RuntimeInfo("host.example", Instant.parse("2026-11-15T09:00:00Z"), "42-abc1234")),
                null, null, null);
    }

    private static PlanInstructions withStatus(PlanInstructions p, PlanStatus status) {
        return new PlanInstructions(p.planId(), p.planType(), status, p.userId(), p.ddaAccInfo(),
                p.invAccInfo(), p.currency(), p.thresholdInfo(), p.restInfo(),
                p.createdAt(), p.updatedAt(), p.version());
    }

    @Test
    void createAssignsIdTimestampsAndVersionZero() {
        PlanInstructions saved = plans.save(newPlan("u1", PlanType.RECURRING_INVESTMENT));

        assertThat(saved.planId()).isNotNull();
        assertThat(saved.version()).isZero();
        assertThat(saved.createdAt()).isEqualTo(LocalDateTime.of(2026, 11, 15, 10, 0));
        assertThat(saved.updatedAt()).isEqualTo(saved.createdAt());
        // plan_id embeds the same millisecond as created_at
        assertThat(UuidV7Generator.timestampMillis(saved.planId()))
                .isEqualTo(saved.createdAt().toInstant(ZoneOffset.UTC).toEpochMilli());
        assertThat(plans.load(saved.planId())).contains(saved);
    }

    @Test
    void roundTripKeepsNestedRecordsAndRestInfo() {
        PlanInstructions saved = plans.save(newPlan("u1", PlanType.ONE_TIME_INVESTMENT));
        PlanInstructions loaded = plans.load(saved.planId()).orElseThrow();

        assertThat(loaded.ddaAccInfo()).isEqualTo(new DdaAccInfo("DDA-u1", "BANK-1"));
        assertThat(loaded.invAccInfo()).isEqualTo(new InvAccInfo("INV-u1", "OCCD-1"));
        assertThat(loaded.currency()).isEqualTo(Currency.getInstance("USD"));
        assertThat(loaded.thresholdInfo().thresholdMin()).isEqualByComparingTo("10.50");
        assertThat(loaded.restInfo().userAgent()).isEqualTo("test-agent/1.0");
        assertThat(loaded.restInfo().runtimeInfo().buildNumber()).isEqualTo("42-abc1234");
        assertThat(loaded.restInfo().runtimeInfo().startedAt()).isEqualTo(Instant.parse("2026-11-15T09:00:00Z"));
    }

    @Test
    void createWithPlanIdIsRejected() {
        PlanInstructions withId = new PlanInstructions(UUID.randomUUID(), PlanType.REBALANCING,
                PlanStatus.ACTIVE, "u1", new DdaAccInfo("a", "b"), new InvAccInfo("c", "d"),
                Currency.getInstance("USD"), null, null, null, null, null);
        assertThatThrownBy(() -> plans.save(withId)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateBumpsVersionAndUpdatedAtButKeepsCreatedAt() {
        PlanInstructions created = plans.save(newPlan("u1", PlanType.RECURRING_INVESTMENT));
        clock.set(Instant.parse("2026-11-15T11:30:00Z"));

        PlanInstructions updated = plans.save(withStatus(created, PlanStatus.PAUSED));

        assertThat(updated.planId()).isEqualTo(created.planId());
        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.planStatus()).isEqualTo(PlanStatus.PAUSED);
        assertThat(updated.createdAt()).isEqualTo(created.createdAt());
        assertThat(updated.updatedAt()).isEqualTo(LocalDateTime.of(2026, 11, 15, 11, 30));
        // system fields inside the JSONB payload stay in step with the columns
        assertThat(jdbc.sql("SELECT payload ->> 'version' FROM plan_instructions WHERE plan_id = :id")
                .param("id", created.planId()).query(String.class).single()).isEqualTo("1");
        assertThat(jdbc.sql("SELECT payload ->> 'planStatus' FROM plan_instructions WHERE plan_id = :id")
                .param("id", created.planId()).query(String.class).single()).isEqualTo("PAUSED");
    }

    @Test
    void staleVersionFailsWithOptimisticLockingError() {
        PlanInstructions created = plans.save(newPlan("u1", PlanType.RECURRING_INVESTMENT));
        plans.save(withStatus(created, PlanStatus.PAUSED));          // version 0 -> 1

        assertThatThrownBy(() -> plans.save(withStatus(created, PlanStatus.CANCELLED))) // still version 0
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(plans.load(created.planId()).orElseThrow().planStatus()).isEqualTo(PlanStatus.PAUSED);
    }

    @Test
    void updatingUnknownPlanFailsWithNotFound() {
        PlanInstructions ghost = new PlanInstructions(UUID.randomUUID(), PlanType.REBALANCING,
                PlanStatus.ACTIVE, "u1", new DdaAccInfo("a", "b"), new InvAccInfo("c", "d"),
                Currency.getInstance("USD"), null, null, null, null, 0L);
        assertThatThrownBy(() -> plans.save(ghost)).isInstanceOf(PlanNotFoundException.class);
    }

    @Test
    void loadOfUnknownIdIsEmpty() {
        assertThat(plans.load(UUID.randomUUID())).isEmpty();
    }

    @Test
    void sortingByPlanIdFollowsCreationOrder() {
        List<UUID> inCreationOrder = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            if (i % 5 == 0) {
                clock.advance(Duration.ofMillis(3));   // some in the same ms, some later
            }
            inCreationOrder.add(plans.save(newPlan("u" + i, PlanType.RECURRING_INVESTMENT)).planId());
        }
        List<UUID> sortedByPlanId = jdbc.sql("SELECT plan_id FROM plan_instructions ORDER BY plan_id")
                .query(UUID.class).list();
        assertThat(sortedByPlanId).containsExactlyElementsOf(inCreationOrder);
    }

    @Test
    void lookupReturnsLatestNonCancelledPlanOfUserAndType() {
        PlanInstructions first = plans.save(newPlan("u1", PlanType.RECURRING_INVESTMENT));
        clock.advance(Duration.ofMinutes(1));
        PlanInstructions second = plans.save(newPlan("u1", PlanType.RECURRING_INVESTMENT));
        plans.save(newPlan("u1", PlanType.REBALANCING));
        plans.save(newPlan("u2", PlanType.RECURRING_INVESTMENT));

        assertThat(plans.lookup("u1", PlanType.RECURRING_INVESTMENT).orElseThrow().planId())
                .isEqualTo(second.planId());

        clock.advance(Duration.ofMinutes(1));
        plans.save(withStatus(second, PlanStatus.CANCELLED));
        assertThat(plans.lookup("u1", PlanType.RECURRING_INVESTMENT).orElseThrow().planId())
                .isEqualTo(first.planId());

        clock.advance(Duration.ofMinutes(1));
        plans.save(withStatus(first, PlanStatus.CANCELLED));
        assertThat(plans.lookup("u1", PlanType.RECURRING_INVESTMENT)).isEmpty();
    }

    @Test
    void searchCombinesFiltersAndOrdersNewestFirst() {
        PlanInstructions a = plans.save(newPlan("u1", PlanType.RECURRING_INVESTMENT));
        clock.advance(Duration.ofMinutes(1));
        PlanInstructions b = plans.save(newPlan("u1", PlanType.ONE_TIME_INVESTMENT));
        clock.advance(Duration.ofMinutes(1));
        PlanInstructions c = plans.save(newPlan("u2", PlanType.RECURRING_INVESTMENT));
        clock.advance(Duration.ofMinutes(1));
        plans.save(withStatus(c, PlanStatus.PAUSED));

        assertThat(ids(search(criteria(null, null, null, null, null, null))))
                .containsExactly(c.planId(), b.planId(), a.planId());                 // newest first
        assertThat(ids(search(criteria("u1", null, null, null, null, null))))
                .containsExactly(b.planId(), a.planId());
        assertThat(ids(search(criteria(null, Set.of(PlanType.RECURRING_INVESTMENT), null, null, null, null))))
                .containsExactly(c.planId(), a.planId());
        assertThat(ids(search(criteria(null, null, Set.of(PlanStatus.PAUSED), null, null, null))))
                .containsExactly(c.planId());
        assertThat(ids(search(criteria(null, null, null, "DDA-u2", null, null))))
                .containsExactly(c.planId());
        assertThat(ids(search(criteria(null, null, null, null, "INV-u1", null))))
                .containsExactly(b.planId(), a.planId());
        assertThat(ids(search(criteria(null, null, null, null, null, Currency.getInstance("EUR"))))).isEmpty();
    }

    @Test
    void searchPagesAndFiltersByCreatedRange() {
        List<UUID> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            created.add(plans.save(newPlan("u" + i, PlanType.REBALANCING)).planId());
            clock.advance(Duration.ofHours(1));
        }
        // newest first: created[4], [3], [2], [1], [0]
        assertThat(ids(plans.search(new PlanSearchCriteria(null, null, null, null, null, null, null, null, 2, 0))))
                .containsExactly(created.get(4), created.get(3));
        assertThat(ids(plans.search(new PlanSearchCriteria(null, null, null, null, null, null, null, null, 2, 2))))
                .containsExactly(created.get(2), created.get(1));

        // created at 10:00, 11:00, 12:00, 13:00, 14:00 UTC; range is [11:00, 13:00)
        List<PlanInstructions> inRange = plans.search(new PlanSearchCriteria(null, null, null, null, null, null,
                LocalDateTime.of(2026, 11, 15, 11, 0), LocalDateTime.of(2026, 11, 15, 13, 0), null, null));
        assertThat(ids(inRange)).containsExactly(created.get(2), created.get(1));
    }

    private static PlanSearchCriteria criteria(String userId, Set<PlanType> types, Set<PlanStatus> statuses,
                                               String ddaAccNum, String invAccNum, Currency currency) {
        return new PlanSearchCriteria(userId, types, statuses, currency, ddaAccNum, invAccNum, null, null, null, null);
    }

    private List<PlanInstructions> search(PlanSearchCriteria c) {
        return plans.search(c);
    }

    private static List<UUID> ids(List<PlanInstructions> list) {
        return list.stream().map(PlanInstructions::planId).toList();
    }

    /** A clock the test can move forward. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        void advance(Duration d) {
            this.now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
