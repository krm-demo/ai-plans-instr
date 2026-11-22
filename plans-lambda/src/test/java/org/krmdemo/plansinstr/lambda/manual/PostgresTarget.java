package org.krmdemo.plansinstr.lambda.manual;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.krmdemo.plansinstr.core.model.PlanInstructions;
import org.krmdemo.plansinstr.core.model.PlanSearchCriteria;
import org.krmdemo.plansinstr.core.model.enums.PlanStatus;
import org.krmdemo.plansinstr.core.model.enums.PlanType;
import org.krmdemo.plansinstr.core.persistence.jdbc.PlanJdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * Postgres through {@link PlanJdbcClient}, the code the Lambdas run. Both kinds use one
 * connection for all operations, so the numbers are the database and the network, not the
 * handshakes of new connections.
 */
final class PostgresTarget implements BenchTarget {

    private final String name;
    private final String about;
    private final EmbeddedPostgres embedded;
    private final SingleConnectionDataSource single;
    private final JdbcClient jdbc;
    private final PlanJdbcClient plans;
    /** How long the first connection took (an Aurora that paused itself wakes up for it). */
    final Duration firstConnect;

    private PostgresTarget(String name, String about, EmbeddedPostgres embedded, DataSource migrations,
                           Connection connection, Duration firstConnect) {
        this.name = name;
        this.embedded = embedded;
        this.firstConnect = firstConnect;
        Flyway.configure().dataSource(migrations).locations("classpath:db/migration").load().migrate();
        this.single = new SingleConnectionDataSource(connection, true);
        this.jdbc = JdbcClient.create(single);
        this.plans = new PlanJdbcClient(jdbc, JsonMapper.builder().build(), Clock.systemUTC());
        this.about = about + ", " + jdbc.sql("SELECT version()").query(String.class).single();
    }

    /** Zonky: a real Postgres started in-process, on this machine. */
    static PostgresTarget embedded() {
        try {
            long t0 = System.nanoTime();
            EmbeddedPostgres pg = EmbeddedPostgres.start();
            DataSource ds = pg.getPostgresDatabase();
            Connection c = ds.getConnection();
            return new PostgresTarget("embedded-postgres", "Zonky EmbeddedPostgres on the runner", pg, ds, c,
                    Duration.ofNanos(System.nanoTime() - t0));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A Postgres over the network, for example Aurora Serverless v2. A paused Aurora resumes on
     * the first connection, which takes about 15 seconds, so this retries for up to {@code wait}.
     */
    static PostgresTarget remote(String name, String url, String user, String password, Duration wait) {
        DriverManagerDataSource ds = new DriverManagerDataSource(url, user, password);
        long t0 = System.nanoTime();
        long deadline = t0 + wait.toNanos();
        SQLException last = null;
        while (System.nanoTime() < deadline) {
            try {
                Connection c = ds.getConnection();
                Duration took = Duration.ofNanos(System.nanoTime() - t0);
                return new PostgresTarget(name, "remote " + url.replaceAll("\\?.*", ""), null, ds, c, took);
            } catch (SQLException e) {
                last = e;
                System.out.println("[" + name + "] not reachable yet: " + e.getMessage());
                sleep(Duration.ofSeconds(5));
            }
        }
        throw new IllegalStateException(name + ": no connection within " + wait, last);
    }

    @Override public String name() { return name; }
    @Override public Kind kind() { return embedded != null ? Kind.LOCAL : Kind.CLOUD; }
    @Override public String about() { return about; }

    @Override
    public PlanInstructions create(PlanInstructions plan) {
        return plans.save(plan, "benchmark: create");
    }

    @Override
    public Optional<PlanInstructions> load(UUID planId) {
        return plans.load(planId);
    }

    @Override
    public PlanInstructions update(PlanInstructions plan) {
        return plans.save(plan, "benchmark: update");
    }

    @Override
    public boolean delete(UUID planId) {
        return jdbc.sql("DELETE FROM plan_instructions WHERE id = :id").param("id", planId).update() > 0;
    }

    @Override
    public Optional<PlanInstructions> lookup(String userId, PlanType planType) {
        return plans.lookup(userId, planType);
    }

    @Override
    public List<PlanInstructions> byUser(String userId, PlanStatus status) {
        return plans.search(new PlanSearchCriteria(userId, null, status == null ? null : Set.of(status),
                null, null, null, null, null, null, null));
    }

    @Override
    public List<PlanInstructions> byDdaAccount(String ddaAccNum) {
        return plans.search(new PlanSearchCriteria(null, null, null, null, ddaAccNum, null, null, null, null, null));
    }

    /** {@code DISTINCT ON}: one row per user, the first in the ORDER BY, in one pass. */
    @Override
    public OptionalInt latestPlanPerUser(String userIdPrefix) {
        List<UUID> ids = jdbc.sql("""
                        SELECT DISTINCT ON (payload ->> 'userId') id
                          FROM plan_instructions
                         WHERE payload ->> 'userId' LIKE :prefix
                         ORDER BY payload ->> 'userId', last_updated_at DESC""")
                .param("prefix", userIdPrefix + "%")
                .query(UUID.class)
                .list();
        return OptionalInt.of(ids.size());
    }

    /** Removes what an earlier run left behind, for example after a failure. */
    int deleteByUserPrefix(String userIdPrefix) {
        return jdbc.sql("DELETE FROM plan_instructions WHERE payload ->> 'userId' LIKE :prefix")
                .param("prefix", userIdPrefix + "%").update();
    }

    @Override
    public void close() {
        single.destroy();
        if (embedded != null) {
            try {
                embedded.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
