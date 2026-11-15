# Plan execution poller (PGMQ-style, Postgres `SKIP LOCKED`)

Collected from the design notes of Nov 12–13, 2026:
- *SweepJdbcClient & SweepSagaStage Architecture (PGMQ Long Poller + Reactor Flux SAGA)*, Nov 12
- *Zonky Embedded Postgres Multi-Threaded JUnit 5 Test (SKIP LOCKED + UUIDv7)*, Nov 13

The code below is as designed then, with the older `PlanExecution`/`AuditEvent` shape (a flat
`AuditEvent(stage, status, details, timestamp)` record). See
[Fitting the current model](#fitting-the-current-model) for what changes with the
`PlanExecution` / `AuditEvent` types now in `plans-core`.

## Flow

1. **Schedule**: insert a `plan_execution` row with status `SCHEDULED` and a `scheduled_at`.
2. **Claim**: pollers run one statement that locks due rows with `FOR UPDATE SKIP LOCKED`,
   flips them to `STARTED` and returns them. Concurrent pollers never get the same row and
   never wait on each other.
3. **Long poll**: if nothing is due, the poller retries every `pollInterval` until
   `maxPollTimeout`, without sleeping a thread (a `ScheduledExecutorService` timer).
4. **Emit**: claimed rows go into a `Flux<PlanExecution>`. The saga stage subscribes, and
   `.repeat()` starts the next poll when one completes.

## Schema (from the Zonky test)

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE OR REPLACE FUNCTION uuidv7() RETURNS uuid AS $$
DECLARE
    unix_ts_ms bytea;
    uuid_bytes bytea;
BEGIN
    unix_ts_ms = substring(int8send(floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint) FROM 3);
    uuid_bytes = unix_ts_ms || gen_random_bytes(10);
    uuid_bytes = set_byte(uuid_bytes, 6, (get_byte(uuid_bytes, 6) & 15) | 112);
    uuid_bytes = set_byte(uuid_bytes, 8, (get_byte(uuid_bytes, 8) & 63) | 128);
    RETURN encode(uuid_bytes, 'hex')::uuid;
END
$$ LANGUAGE plpgsql VOLATILE;

CREATE TABLE plan_execution (
    plan_id         UUID PRIMARY KEY DEFAULT uuidv7(),
    security_symbol VARCHAR(32) NOT NULL,
    account_no      VARCHAR(64) NOT NULL,
    audit_status    VARCHAR(32) NOT NULL,
    scheduled_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    audit_events    JSONB NOT NULL DEFAULT '[]'::jsonb
);
```

## Claim statement

The Nov 13 version, which adds the due-time check and a tie-break on the UUIDv7 key:

```sql
WITH next_batch AS (
    SELECT plan_id
    FROM plan_execution
    WHERE audit_status = 'SCHEDULED'
      AND scheduled_at <= NOW()
    ORDER BY scheduled_at ASC, plan_id ASC
    LIMIT :limit
    FOR UPDATE SKIP LOCKED
)
UPDATE plan_execution pe
SET audit_status = 'IN_PROGRESS',
    audit_events = pe.audit_events || '[{"stage":"CLAIMED"}]'::jsonb
FROM next_batch
WHERE pe.plan_id = next_batch.plan_id
RETURNING pe.plan_id
```

Lock and status change happen in one round trip; no lock is held while retrievers call
external services.

## SweepJdbcClient (Nov 12)

```java
@Component
public class SweepJdbcClient implements JdbcClient {

    private static final TypeReference<List<AuditEvent>> AUDIT_EVENT_LIST_TYPE = new TypeReference<>() {};

    private final JdbcClient delegate;
    private final ScheduledExecutorService pollScheduler;

    public SweepJdbcClient(DataSource dataSource) {
        this.delegate = JdbcClient.create(dataSource);
        this.pollScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sweep-pgmq-poller");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public StatementSpec sql(String sql) {
        return delegate.sql(sql);
    }

    /** Enqueues a new PlanExecution in SCHEDULED state for the long-poller to claim. */
    @Transactional
    public PlanExecution insertScheduled(PlanExecution execution) {
        String query = """
            INSERT INTO plan_execution (
                plan_id, security_symbol, account_no, audit_status, audit_events, created_at, updated_at
            ) VALUES (
                :planId, :securitySymbol, :accountNo, :auditStatus, CAST(:auditEvents AS jsonb), NOW(), NOW()
            )
            RETURNING plan_id, security_symbol, account_no, audit_status, audit_events
            """;

        UUID id = execution.planId() != null ? execution.planId() : UUID.randomUUID();
        List<AuditEvent> initialEvents = execution.auditEvents() != null && !execution.auditEvents().isEmpty()
            ? execution.auditEvents()
            : List.of(AuditEvent.builder()
                .stage("SCHEDULE")
                .status("SCHEDULED")
                .details("Plan scheduled for sweep execution")
                .timestamp(Instant.now())
                .build());

        return sql(query)
            .param("planId", id)
            .param("securitySymbol", execution.securitySymbol())
            .param("accountNo", execution.accountNo())
            .param("auditStatus", AuditStatus.SCHEDULED.name())
            .param("auditEvents", JsonUtils.toJson(initialEvents))
            .query(this::mapPlanExecution)
            .single();
    }

    /** PGMQ-style reactive long-polling read returning a Flux<PlanExecution>. */
    public Flux<PlanExecution> readWithPoll(int batchSize, Duration maxPollTimeout, Duration pollInterval) {
        return Flux.create(sink -> {
            PollingTask task = PollingTask.builder()
                .batchFetcher(() -> tryClaimBatch(batchSize))
                .scheduler(pollScheduler)
                .maxPollTimeout(maxPollTimeout)
                .pollInterval(pollInterval)
                .sink(sink)
                .build();

            sink.onDispose(task::cancel);
            task.start();
        });
    }

    /** Atomic claim transaction using SELECT FOR UPDATE SKIP LOCKED. */
    @Transactional
    public List<PlanExecution> tryClaimBatch(int batchSize) {
        String query = """
            WITH claimed AS (
                SELECT plan_id
                FROM plan_execution
                WHERE audit_status = 'SCHEDULED'
                ORDER BY created_at ASC
                LIMIT :batchSize
                FOR UPDATE SKIP LOCKED
            )
            UPDATE plan_execution p
            SET audit_status = 'STARTED',
                audit_events = p.audit_events || CAST(:eventJson AS jsonb),
                updated_at = NOW()
            FROM claimed
            WHERE p.plan_id = claimed.plan_id
            RETURNING p.plan_id, p.security_symbol, p.account_no, p.audit_status, p.audit_events
            """;

        AuditEvent claimEvent = AuditEvent.builder()
            .stage("POLLER_CLAIM")
            .status("STARTED")
            .details("Claimed via SKIP LOCKED long-poll")
            .timestamp(Instant.now())
            .build();

        return sql(query)
            .param("batchSize", batchSize)
            .param("eventJson", JsonUtils.toJson(List.of(claimEvent)))
            .query(this::mapPlanExecution)
            .list();
    }

    /** Persists updated auditStatus and auditEvents after each SAGA stage. */
    @Transactional
    public PlanExecution updatePlanExecution(PlanExecution execution) {
        String query = """
            UPDATE plan_execution
            SET audit_status = :auditStatus,
                audit_events = CAST(:auditEvents AS jsonb),
                updated_at = NOW()
            WHERE plan_id = :planId
            RETURNING plan_id, security_symbol, account_no, audit_status, audit_events
            """;

        return sql(query)
            .param("planId", execution.planId())
            .param("auditStatus", execution.auditStatus().name())
            .param("auditEvents", JsonUtils.toJson(execution.auditEvents()))
            .query(this::mapPlanExecution)
            .single();
    }

    @PreDestroy
    public void shutdown() {
        pollScheduler.shutdownNow();
    }

    private PlanExecution mapPlanExecution(ResultSet rs, int rowNum) throws SQLException {
        return PlanExecution.builder()
            .planId(rs.getObject("plan_id", UUID.class))
            .securitySymbol(rs.getString("security_symbol"))
            .accountNo(rs.getString("account_no"))
            .auditStatus(AuditStatus.valueOf(rs.getString("audit_status")))
            .auditEvents(JsonUtils.fromJsonList(rs.getString("audit_events"), AUDIT_EVENT_LIST_TYPE))
            .build();
    }

    @Builder(builderClassName = "InternalBuilder", access = AccessLevel.PRIVATE)
    private record PollingTask(
        Supplier<List<PlanExecution>> batchFetcher,
        ScheduledExecutorService scheduler,
        Instant deadline,
        Duration pollInterval,
        FluxSink<PlanExecution> sink,
        AtomicReference<ScheduledFuture<?>> pendingTimer
    ) {
        static PollingTaskBuilder builder() {
            return new PollingTaskBuilder();
        }

        static final class PollingTaskBuilder {
            private Supplier<List<PlanExecution>> batchFetcher;
            private ScheduledExecutorService scheduler;
            private Duration maxPollTimeout = Duration.ofSeconds(5);
            private Duration pollInterval = Duration.ofMillis(100);
            private FluxSink<PlanExecution> sink;

            PollingTaskBuilder batchFetcher(Supplier<List<PlanExecution>> batchFetcher) { this.batchFetcher = batchFetcher; return this; }
            PollingTaskBuilder scheduler(ScheduledExecutorService scheduler) { this.scheduler = scheduler; return this; }
            PollingTaskBuilder maxPollTimeout(Duration maxPollTimeout) { this.maxPollTimeout = maxPollTimeout; return this; }
            PollingTaskBuilder pollInterval(Duration pollInterval) { this.pollInterval = pollInterval; return this; }
            PollingTaskBuilder sink(FluxSink<PlanExecution> sink) { this.sink = sink; return this; }

            PollingTask build() {
                return new InternalBuilder()
                    .batchFetcher(Objects.requireNonNull(batchFetcher, "batchFetcher"))
                    .scheduler(Objects.requireNonNull(scheduler, "scheduler"))
                    .deadline(Instant.now().plus(Objects.requireNonNull(maxPollTimeout, "maxPollTimeout")))
                    .pollInterval(Objects.requireNonNull(pollInterval, "pollInterval"))
                    .sink(Objects.requireNonNull(sink, "sink"))
                    .pendingTimer(new AtomicReference<>())
                    .build();
            }
        }

        void start() {
            scheduleNext(0L);
        }

        void cancel() {
            ScheduledFuture<?> future = pendingTimer.getAndSet(null);
            if (future != null) {
                future.cancel(false);
            }
        }

        private void executeAttempt() {
            if (sink.isCancelled()) {
                return;
            }
            try {
                List<PlanExecution> batch = batchFetcher.get();
                if (!batch.isEmpty()) {
                    batch.forEach(sink::next);
                    sink.complete();
                    return;
                }
                if (!Instant.now().isBefore(deadline)) {
                    sink.complete();
                    return;
                }
                scheduleNext(computeNextDelayMs());
            } catch (Exception ex) {
                sink.error(ex);
            }
        }

        private void scheduleNext(long delayMs) {
            if (!sink.isCancelled()) {
                pendingTimer.set(scheduler.schedule(this::executeAttempt, delayMs, TimeUnit.MILLISECONDS));
            }
        }

        private long computeNextDelayMs() {
            long remainingMs = Duration.between(Instant.now(), deadline).toMillis();
            return Math.min(pollInterval.toMillis(), Math.max(1L, remainingMs));
        }
    }
}
```

`JsonUtils` from the same note:

```java
public final class JsonUtils {

    private static final JsonMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    private JsonUtils() {}

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize object to JSON", e);
        }
    }

    public static <T> List<T> fromJsonList(String json, TypeReference<List<T>> typeRef) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, typeRef);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to deserialize JSON list", e);
        }
    }
}
```

## Concurrency test (Zonky embedded Postgres, Nov 13)

H2 cannot run `FOR UPDATE SKIP LOCKED`, JSONB or PL/pgSQL, so the test uses a real Postgres
binary started in-process (no Docker).

```xml
<dependency>
    <groupId>io.zonky.test</groupId>
    <artifactId>embedded-database-spring-test</artifactId>
    <version>2.6.0</version>
    <scope>test</scope>
</dependency>
```

```java
@JdbcTest
@AutoConfigureEmbeddedDatabase(provider = DatabaseProvider.ZONKY, type = DatabaseType.POSTGRES)
// Disable outer test transaction so worker threads can commit independently
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PlanExecutionConcurrencyTest {

    @Autowired private JdbcClient jdbcClient;
    @Autowired private PlatformTransactionManager transactionManager;
    private TransactionTemplate txTemplate;

    @BeforeEach
    void setUp() {
        txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // DDL from the "Schema" section above
    }

    @Test
    void shouldClaimDuePlansConcurrentlyWithoutDuplicatesUsingSkipLocked() throws Exception {
        int totalPlans = 100;
        int pollerThreads = 10;
        int batchSize = 5;

        // 1. Seed 100 due plan executions
        for (int i = 0; i < totalPlans; i++) {
            jdbcClient.sql("""
                INSERT INTO plan_execution (security_symbol, account_no, audit_status, audit_events)
                VALUES (:symbol, :account, 'SCHEDULED', :events::jsonb)
                """)
                .param("symbol", "AAPL")
                .param("account", "ACC-" + i)
                .param("events", "[{\"stage\":\"INIT\"}]")
                .update();
        }

        // 2. Simulate 10 concurrent poller replicas claiming batches simultaneously
        ExecutorService executor = Executors.newFixedThreadPool(pollerThreads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<UUID> allClaimedIds = Collections.synchronizedList(new ArrayList<>());

        List<Callable<Void>> workers = new ArrayList<>();
        for (int t = 0; t < pollerThreads; t++) {
            workers.add(() -> {
                startGate.await(); // release all pollers at the same instant
                while (true) {
                    List<UUID> batch = claimBatch(batchSize);
                    if (batch.isEmpty()) {
                        break;
                    }
                    allClaimedIds.addAll(batch);
                }
                return null;
            });
        }

        List<Future<Void>> futures = new ArrayList<>();
        for (Callable<Void> worker : workers) {
            futures.add(executor.submit(worker));
        }
        startGate.countDown();
        for (Future<Void> future : futures) {
            future.get(); // propagates any deadlock or SQL exception
        }
        executor.shutdown();

        // 3. Every plan claimed exactly once
        assertThat(allClaimedIds).hasSize(totalPlans);
        assertThat(new HashSet<>(allClaimedIds)).hasSize(totalPlans);

        // 4. UUIDv7 keys are in insertion order
        List<UUID> orderedIds = jdbcClient.sql("SELECT plan_id FROM plan_execution ORDER BY plan_id ASC")
            .query(UUID.class)
            .list();
        assertThat(orderedIds).isSorted();
    }

    private List<UUID> claimBatch(int limit) {
        return txTemplate.execute(status -> jdbcClient.sql(CLAIM_SQL)   // the claim statement above
            .param("limit", limit)
            .query(UUID.class)
            .list());
    }
}
```

Takeaways from the note:
- `@Transactional(propagation = NOT_SUPPORTED)` on the test class, so each worker's
  `REQUIRES_NEW` transaction commits on its own and `SKIP LOCKED` behaves as in production.
- Claim and status change in one `WITH … FOR UPDATE SKIP LOCKED` + `UPDATE … RETURNING`.
- `DEFAULT uuidv7()` keys sort in creation order.
- Within one millisecond the random part decides the order, so "sorted by key" is creation
  order only to the millisecond.

## Fitting the current model

What changes with `PlanExecution` / `AuditEvent` / `AuditStatus` from `plans-core`, and the
gaps to close when this becomes code:

1. **Lease (visibility timeout).** As written, a claimed row stays `STARTED` forever if its
   worker dies. A PGMQ-style queue needs a `visible_at` column: the claim sets
   `visible_at = now() + lease`, the poll selects due rows whose `visible_at <= now()`, and a
   live worker extends the lease. That is also how a worker hands an execution with
   `hasUnknownEvents()` to a newer worker: it just lets the lease expire.
2. **Optimistic locking.** `updatePlanExecution` writes without a check. After a lease expires,
   two workers could both write. Use `PlanExecution.version`: `WHERE execution_id = :id AND
   version = :version`, and treat 0 updated rows as "lost the lease".
3. **Status name.** The Nov 12 note claims to `STARTED`, the Nov 13 test to `IN_PROGRESS`.
   `AuditStatus` has `STARTED`; use that.
4. **Keys.** `UUID.randomUUID()` in `insertScheduled` gives v4 keys; use UUIDv7 (`plans-core`
   has `UuidV7Generator`) so keys sort by creation time, as the test expects.
5. **The claim event.** The claim appends a raw JSON fragment to `audit_events`. With typed
   events, either the claim only changes the status (and the `STARTED` transition is not an
   event), or a registered `ClaimedEvent` type is appended. Otherwise every reader sees an
   `UnknownAuditEvent` for it.
6. **`@Transactional` on `tryClaimBatch`** is called from a lambda inside the same bean, so the
   Spring proxy is bypassed and no transaction is started. It still works because the claim is
   one statement, but do not rely on the annotation there.
7. **Columns, no join.** `security_symbol`/`account_no` belong to the older example. The
   current record embeds the whole `PlanInstructions` by value as `planInstr` inside the JSONB
   payload. There is no `plan_id` foreign key and no join to `plan_instructions`, so editing or
   deleting a plan never affects an execution. Only `audit_status`, the `numeric(18,2)` derived
   amounts and the queue columns are copied out of the payload, for polling and querying.

   ```sql
   CREATE TABLE plan_execution (
       execution_id     UUID PRIMARY KEY,          -- UUIDv7
       scheduled_at     TIMESTAMPTZ NOT NULL,
       visible_at       TIMESTAMPTZ NOT NULL,      -- lease, see item 1
       audit_status     TEXT NOT NULL,
       derived_prop_one NUMERIC(18,2),
       derived_prop_two NUMERIC(18,2),
       payload          JSONB NOT NULL,           -- whole PlanExecution, incl. planInstr and auditEvents
       version          BIGINT NOT NULL
   );
   ```
8. **Next execution.** When an execution finishes, the next one gets a copy of the plan
   instructions current at that moment as its `planInstr`. Insert it in the same transaction as the final update
   of the finished one, so a crash between the two cannot break the chain.
