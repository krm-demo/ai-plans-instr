package plansinstr.exec;

import plansinstr.execution.AuditStatus;
import plansinstr.execution.PlanExecution;
import plansinstr.execution.UnknownAuditEvent;
import plansinstr.persistence.UuidV7Generator;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static plansinstr.exec.TestData.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs against a real Postgres started in-process by Zonky (no Docker). */
class JdbcPlanExecutionStoreTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    private final JsonMapper json = JsonMapper.builder()
            .registerSubtypes(PropOneEvent.class, PropTwoEvent.class)
            .build();
    private final UuidV7Generator ids = new UuidV7Generator();
    private TestData.MutableClock clock;
    private JdbcClient jdbc;
    private JdbcPlanExecutionStore store;

    @BeforeAll
    static void startPostgres() throws IOException {
        postgres = EmbeddedPostgres.start();
        dataSource = postgres.getPostgresDatabase();
        JdbcClient jdbc = JdbcClient.create(dataSource);
        for (String statement : schemaStatements()) {
            jdbc.sql(statement).update();
        }
    }

    @AfterAll
    static void stopPostgres() throws IOException {
        if (postgres != null) {
            postgres.close();
        }
    }

    /** The schema file without comments, split into statements. */
    private static List<String> schemaStatements() throws IOException {
        try (InputStream in = JdbcPlanExecutionStoreTest.class.getResourceAsStream("/plans/exec/db/plan_execution.sql")) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(line -> line.replaceAll("--.*$", ""))
                    .collect(Collectors.joining("\n"));
            return Arrays.stream(sql.split(";")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        }
    }

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE plan_execution").update();
        clock = new TestData.MutableClock(T0);
        store = new JdbcPlanExecutionStore(dataSource, json, clock);
    }

    private PlanExecution scheduled(Duration fromNow) {
        return store.insert(PlanExecution.schedule(ids.next(clock.millis()), TestData.planInstr("500.00"),
                clock.instant().plus(fromNow)));
    }

    @Test
    @DisplayName("only due executions are claimed, and a claim hides them for the lease")
    void claimDueAndLease() {
        PlanExecution due = scheduled(Duration.ZERO);
        scheduled(Duration.ofHours(1));

        List<PlanExecution> first = store.claimDue(10, Duration.ofMinutes(5));
        assertEquals(List.of(due.executionId()), first.stream().map(PlanExecution::executionId).toList());
        assertEquals(1L, first.getFirst().version());

        assertTrue(store.claimDue(10, Duration.ofMinutes(5)).isEmpty(), "leased");

        clock.advance(Duration.ofMinutes(6));   // lease over: claimable again, with a new version
        List<PlanExecution> again = store.claimDue(10, Duration.ofMinutes(5));
        assertEquals(1, again.size());
        assertEquals(2L, again.getFirst().version());
    }

    @Test
    @DisplayName("a worker whose lease was taken over cannot write any more")
    void lostLease() {
        scheduled(Duration.ZERO);
        PlanExecution mine = store.claimDue(1, Duration.ofMinutes(5)).getFirst();
        clock.advance(Duration.ofMinutes(6));
        PlanExecution theirs = store.claimDue(1, Duration.ofMinutes(5)).getFirst();

        PlanExecution progressed = PropOneEvent.retrieved(T0, "730.25").applyTo(mine);
        assertThrows(PlanExecutionStore.LeaseLostException.class,
                () -> store.checkpoint(progressed, Duration.ofMinutes(5)));

        PlanExecution stored = store.checkpoint(PropOneEvent.retrieved(T0, "730.25").applyTo(theirs), Duration.ofMinutes(5));
        assertEquals(theirs.version() + 1, stored.version());
    }

    @Test
    @DisplayName("checkpoint stores payload and column copies; events round-trip through JSONB")
    void checkpointRoundTrip() {
        scheduled(Duration.ZERO);
        PlanExecution claimed = store.claimDue(1, Duration.ofMinutes(5)).getFirst();
        PlanExecution progressed = claimed.applyAll(List.of(
                PropOneEvent.retrieved(T0, "730.25"),
                new UnknownAuditEvent(Map.of("__eventType__", "FROM_NEWER_BUILD", "x", 1))));

        store.checkpoint(progressed, Duration.ZERO);   // lease 0: claimable again right away
        PlanExecution reread = store.claimDue(1, Duration.ofMinutes(5)).getFirst();

        assertEquals(progressed.withVersion(reread.version()), reread);
        assertInstanceOf(PropOneEvent.class, reread.auditEvents().get(0));
        assertInstanceOf(UnknownAuditEvent.class, reread.auditEvents().get(1));
        Map<String, Object> columns = jdbc.sql("SELECT audit_status, derived_prop_one FROM plan_execution").query().singleRow();
        assertEquals("PROP_ONE_RETRIEVED", columns.get("audit_status"));
        assertEquals(new BigDecimal("230.25"), columns.get("derived_prop_one"));
    }

    @Test
    @DisplayName("complete finishes the execution and inserts the next one in the same transaction")
    void completeAndNext() {
        scheduled(Duration.ZERO);
        PlanExecution claimed = store.claimDue(1, Duration.ofMinutes(5)).getFirst();
        PlanExecution done = PropTwoEvent.completed(T0, "1").applyTo(claimed);
        PlanExecution next = PlanExecution.schedule(ids.next(clock.millis()), TestData.planInstr("600.00"), T0);

        store.complete(done, Optional.of(next));
        clock.advance(Duration.ofHours(1));

        List<PlanExecution> claimable = store.claimDue(10, Duration.ofMinutes(5));
        assertEquals(List.of(next.executionId()), claimable.stream().map(PlanExecution::executionId).toList());
        assertEquals(AuditStatus.SCHEDULED, claimable.getFirst().auditStatus());
    }

    @Test
    @DisplayName("a failed complete inserts nothing")
    void completeIsAtomic() {
        scheduled(Duration.ZERO);
        PlanExecution claimed = store.claimDue(1, Duration.ofMinutes(5)).getFirst();
        PlanExecution stale = claimed.withVersion(claimed.version() - 1);
        PlanExecution next = PlanExecution.schedule(ids.next(clock.millis()), TestData.planInstr("600.00"), T0);

        assertThrows(PlanExecutionStore.LeaseLostException.class, () -> store.complete(stale, Optional.of(next)));
        assertEquals(1, jdbc.sql("SELECT count(*) FROM plan_execution").query(Integer.class).single());
    }

    @Test
    @DisplayName("10 concurrent pollers claim 100 executions exactly once (SKIP LOCKED)")
    void concurrentClaims() throws Exception {
        for (int i = 0; i < 100; i++) {
            scheduled(Duration.ZERO);
        }
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<UUID> claimed = Collections.synchronizedList(new ArrayList<>());
        List<Callable<Void>> workers = new ArrayList<>();
        for (int t = 0; t < 10; t++) {
            workers.add(() -> {
                start.await();
                List<PlanExecution> batch;
                while (!(batch = store.claimDue(5, Duration.ofMinutes(5))).isEmpty()) {
                    batch.forEach(pe -> claimed.add(pe.executionId()));
                }
                return null;
            });
        }
        List<Future<Void>> futures = new ArrayList<>();
        for (Callable<Void> worker : workers) {
            futures.add(executor.submit(worker));
        }
        start.countDown();
        for (Future<Void> future : futures) {
            future.get();
        }
        executor.shutdown();

        assertEquals(100, claimed.size());
        assertEquals(100, new HashSet<>(claimed).size());
    }

    @Test
    @DisplayName("the poller emits due executions as an endless Flux")
    void poller() {
        for (int i = 0; i < 7; i++) {
            scheduled(Duration.ZERO);
        }
        Scheduler db = Schedulers.newBoundedElastic(4, 100, "test-db", 10, true);
        try {
            SweepSagaPostgresPoller poller = new SweepSagaPostgresPoller(store,
                    new SweepSagaProperties(3, Duration.ofMillis(20), Duration.ofMinutes(5), 4, Duration.ofMillis(50)), db);

            List<PlanExecution> polled = poller.poll().take(7).collectList().block(Duration.ofSeconds(10));

            assertEquals(7, new HashSet<>(polled.stream().map(PlanExecution::executionId).toList()).size());
        } finally {
            db.dispose();
        }
    }
}
