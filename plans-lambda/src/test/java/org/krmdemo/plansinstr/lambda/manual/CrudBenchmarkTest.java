package org.krmdemo.plansinstr.lambda.manual;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.krmdemo.plansinstr.core.model.DdaAccInfo;
import org.krmdemo.plansinstr.core.model.InvAccInfo;
import org.krmdemo.plansinstr.core.model.PlanInstructions;
import org.krmdemo.plansinstr.core.model.RestInfo;
import org.krmdemo.plansinstr.core.model.RuntimeInfo;
import org.krmdemo.plansinstr.core.model.ThresholdsInfo;
import org.krmdemo.plansinstr.core.model.enums.PlanStatus;
import org.krmdemo.plansinstr.core.model.enums.PlanType;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The CRUD benchmark: the same scenario on every database that this run can reach.
 *
 * <ol>
 *   <li>create {@code manual.plans} plans (10 000 by default), ten per user;</li>
 *   <li>load each of them, update each of them (optimistic locking);</li>
 *   <li>per user: lookup, the user's plans, the user's active plans, the plans of the DDA account;</li>
 *   <li>Postgres only: the newest plan of every user, with {@code DISTINCT ON};</li>
 *   <li>delete each of them.</li>
 * </ol>
 * One operation at a time over one connection: these are latencies, not throughput under load.
 *
 * <p>Tagged {@code manual}: the normal build skips it; the {@code manual-tests} workflow runs it.
 * The local targets (H2, the embedded Postgres, the embedded DynamoDB) always take part. Aurora takes part when {@code PLANS_PG_URL},
 * {@code PLANS_PG_USER} and {@code PLANS_PG_PASSWORD} are set, DynamoDB when
 * {@code PLANS_DDB_TABLE} is set (and AWS credentials and region are in the environment).
 *
 * <p>The test explains its own numbers: {@link BenchReport} writes
 * {@code target/manual-tests/summary.md} (what was measured, how to read p50/p95/p99, the local
 * and the cloud databases apart); the workflow only shows that file on the summary page of the run.
 */
@Tag("manual")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CrudBenchmarkTest {

    private static final int PLANS = Integer.getInteger("manual.plans", 10_000);
    private static final int PLANS_PER_USER = 10;
    private static final Path REPORTS = Path.of("target", "manual-tests");
    private static final PlanType[] TYPES = PlanType.values();
    private static final PlanStatus[] UPDATED_STATUSES = {PlanStatus.PAUSED, PlanStatus.ACTIVE, PlanStatus.CANCELLED};
    private static final BenchReport REPORT = new BenchReport(PLANS, PLANS_PER_USER);

    @AfterAll
    static void writeReport() throws IOException {
        REPORT.write(REPORTS);
        System.out.println(REPORT.markdown());
    }

    // ---------------------------------------------------------------- local: in this JVM

    @Test
    @Order(1)
    void h2() throws IOException {
        try (H2Target h2 = new H2Target()) {
            run(h2, h2.firstConnect, prefix -> 0);
        }
    }

    @Test
    @Order(2)
    void embeddedPostgres() throws IOException {
        try (PostgresTarget pg = PostgresTarget.embedded()) {
            run(pg, pg.firstConnect, pg::deleteByUserPrefix);
        }
    }

    @Test
    @Order(3)
    void embeddedDynamoDb() throws IOException {
        try (DynamoDbTarget ddb = DynamoDbTarget.embedded()) {
            run(ddb, null, prefix -> 0);
        }
    }

    // ---------------------------------------------------------------- cloud: AWS

    @Test
    @Order(4)
    void auroraPostgres() throws IOException {
        String url = System.getenv("PLANS_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "PLANS_PG_URL is not set");
        try (PostgresTarget pg = PostgresTarget.remote("aurora-postgres", url,
                System.getenv("PLANS_PG_USER"), System.getenv("PLANS_PG_PASSWORD"), Duration.ofMinutes(3))) {
            run(pg, pg.firstConnect, pg::deleteByUserPrefix);
        }
    }

    @Test
    @Order(5)
    void dynamoDb() throws IOException {
        String table = System.getenv("PLANS_DDB_TABLE");
        assumeTrue(table != null && !table.isBlank(), "PLANS_DDB_TABLE is not set");
        try (DynamoDbTarget ddb = DynamoDbTarget.aws(table)) {
            run(ddb, null, prefix -> 0);
        }
    }

    // ---------------------------------------------------------------- the scenario

    private interface Cleaner {
        int deleteByUserPrefix(String prefix);
    }

    private void run(BenchTarget db, Duration firstConnect, Cleaner leftovers) throws IOException {
        String prefix = "bench-" + db.name() + "-";
        int removed = leftovers.deleteByUserPrefix(prefix);
        String userPrefix = prefix + Long.toString(System.currentTimeMillis(), 36) + "-u";
        int users = Math.max(1, PLANS / PLANS_PER_USER);
        Random random = new Random(42);
        Set<UUID> alive = new LinkedHashSet<>();
        List<OpStats> stats = new ArrayList<>();

        try {
            OpStats ping = add(stats, "ping (round trip, no data)");
            for (int i = 0; i < Math.min(1000, PLANS); i++) {
                long t0 = System.nanoTime();
                db.ping();
                ping.since(t0);
            }

            OpStats create = add(stats, "create");
            List<PlanInstructions> plans = new ArrayList<>(PLANS);
            for (int i = 0; i < PLANS; i++) {
                PlanInstructions draft = newPlan(userPrefix + (i % users), TYPES[random.nextInt(TYPES.length)]);
                long t0 = System.nanoTime();
                PlanInstructions saved = db.create(draft);
                create.since(t0);
                alive.add(saved.planId());
                plans.add(saved);
            }

            OpStats load = add(stats, "load by id");
            for (PlanInstructions p : plans) {
                long t0 = System.nanoTime();
                Optional<PlanInstructions> loaded = db.load(p.planId());
                load.since(t0);
                assertEquals(1L, loaded.orElseThrow().version());
            }

            OpStats update = add(stats, "update (version check)");
            for (int i = 0; i < plans.size(); i++) {
                PlanInstructions p = plans.get(i);
                PlanInstructions changed = new PlanInstructions(p.planId(), p.planType(), UPDATED_STATUSES[i % 3],
                        p.userId(), p.ddaAccInfo(), p.invAccInfo(), p.currency(), p.thresholdInfo(), p.restInfo(),
                        p.createdAt(), p.updatedAt(), p.version());
                long t0 = System.nanoTime();
                PlanInstructions saved = db.update(changed);
                update.since(t0);
                assertEquals(2L, saved.version());
            }

            OpStats lookup = add(stats, "search: lookup(user, type)");
            OpStats byUser = add(stats, "search: plans of a user");
            OpStats byUserActive = add(stats, "search: active plans of a user");
            OpStats byDda = add(stats, "search: plans of a DDA account");
            int found = 0;
            for (int u = 0; u < users; u++) {
                String userId = userPrefix + u;
                long t0 = System.nanoTime();
                found += db.lookup(userId, PlanType.RECURRING_INVESTMENT).isPresent() ? 1 : 0;
                lookup.since(t0);

                t0 = System.nanoTime();
                int all = db.byUser(userId, null).size();
                byUser.since(t0);
                assertEquals(Math.min(PLANS_PER_USER, PLANS), all, "plans of " + userId);

                t0 = System.nanoTime();
                db.byUser(userId, PlanStatus.ACTIVE).forEach(p -> assertEquals(PlanStatus.ACTIVE, p.planStatus()));
                byUserActive.since(t0);

                t0 = System.nanoTime();
                int dda = db.byDdaAccount("DDA-" + userId).size();
                byDda.since(t0);
                assertEquals(all, dda, "plans of DDA-" + userId);
            }
            assertTrue(found > 0, "lookup found nothing");

            if (db.latestPlanPerUser(userPrefix).isPresent()) {
                OpStats distinctOn = add(stats, "search: newest plan per user");
                for (int i = 0; i < 10; i++) {
                    long t0 = System.nanoTime();
                    OptionalInt n = db.latestPlanPerUser(userPrefix);
                    distinctOn.since(t0);
                    assertEquals(users, n.orElseThrow());
                }
            }

            OpStats delete = add(stats, "delete");
            for (PlanInstructions p : plans) {
                long t0 = System.nanoTime();
                boolean deleted = db.delete(p.planId());
                delete.since(t0);
                assertTrue(deleted);
                alive.remove(p.planId());
            }
        } finally {
            for (UUID id : alive) {
                db.delete(id);
            }
            report(db, firstConnect, removed, stats);
        }
    }

    private static OpStats add(List<OpStats> stats, String op) {
        OpStats s = new OpStats(op);
        stats.add(s);
        return s;
    }

    private static void report(BenchTarget db, Duration firstConnect, int removed, List<OpStats> stats) {
        REPORT.add(new BenchReport.Result(db.kind(), db.name(), db.about(), firstConnect, removed, List.copyOf(stats)));
    }

    private static PlanInstructions newPlan(String userId, PlanType type) {
        return new PlanInstructions(null, type, PlanStatus.ACTIVE, userId,
                new DdaAccInfo("DDA-" + userId, "BANK-1"),
                new InvAccInfo("INV-" + userId, "OCCD-1"),
                Currency.getInstance("USD"),
                new ThresholdsInfo(new BigDecimal("10.50"), new BigDecimal("500"), new BigDecimal("5"),
                        new BigDecimal("100")),
                new RestInfo("crud-benchmark/1.0", Instant.now(),
                        new RuntimeInfo("runner", Instant.now(), "manual")),
                null, null, null);
    }
}
