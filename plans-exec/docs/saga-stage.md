# SweepSagaStage and the reactive pipeline

Collected from the design notes of Nov 12, 2026:
- *SweepJdbcClient & SweepSagaStage Architecture (PGMQ Long Poller + Reactor Flux SAGA)*: the
  orchestrator that subscribes to the poller and runs stages
- *Reactive Pipeline Configuration, Updated Saga Stages & JUnit 5 Tests*: the stage as a
  `UnaryOperator<PlanExecution>` that runs retrievers through a `Flux` and reduces their events
  with `auditEvent.applyTo(...)`, plus configuration and tests

Two layers:
- **Orchestrator** (`SweepSagaStage`): subscribes to the poller's `Flux<PlanExecution>`, runs
  each claimed execution through its stages, checkpoints after each stage, compensates on failure.
- **Stage** (`ExplicitFluxSweepSagaStage` / `DynamicFluxSweepSagaStage`): runs a group of
  retrievers against one input snapshot and folds their events into the output.

## Orchestrator (Nov 12, morning)

```java
@Service
public class SweepSagaStage {

    private final SweepJdbcClient sweepJdbcClient;
    private final AsyncRetriever positionRetriever;
    private final AsyncRetriever marketDataRetriever;
    private Disposable pipelineSubscription;

    public SweepSagaStage(SweepJdbcClient sweepJdbcClient,
                          AsyncRetriever positionRetriever,
                          AsyncRetriever marketDataRetriever) {
        this.sweepJdbcClient = sweepJdbcClient;
        this.positionRetriever = positionRetriever;
        this.marketDataRetriever = marketDataRetriever;
    }

    /** Continuous long-polling pipeline that consumes claimed PlanExecutions. */
    @PostConstruct
    public void startPipeline() {
        this.pipelineSubscription = Flux.defer(() -> sweepJdbcClient.readWithPoll(
                50,                     // batchSize
                Duration.ofSeconds(5),  // maxPollTimeout
                Duration.ofMillis(100)  // pollInterval
            ))
            .repeat()
            .flatMap(this::execute, 16)
            .onErrorContinue((err, item) -> {
                // Log unhandled pipeline error so polling never terminates
            })
            .subscribe();
    }

    @PreDestroy
    public void stopPipeline() {
        if (pipelineSubscription != null && !pipelineSubscription.isDisposed()) {
            pipelineSubscription.dispose();
        }
    }

    /** Schedules a PlanExecution as SCHEDULED and returns the persisted record. */
    public Mono<PlanExecution> schedule(PlanExecution planExecution) {
        return Mono.fromCallable(() -> sweepJdbcClient.insertScheduled(planExecution))
            .subscribeOn(Schedulers.boundedElastic());
    }

    /** Runs all stages for a claimed PlanExecution; returns the final COMPLETED or FAILED one. */
    public Mono<PlanExecution> execute(PlanExecution claimed) {
        AtomicReference<PlanExecution> latestState = new AtomicReference<>(claimed);

        return Mono.just(claimed)
            // Stage 1: asynchronous position retrieval
            .flatMap(exec -> runStage(exec, "POSITION_RETRIEVER", positionRetriever).doOnNext(latestState::set))
            // Stage 2: asynchronous market data retrieval
            .flatMap(exec -> runStage(exec, "MARKET_DATA_RETRIEVER", marketDataRetriever).doOnNext(latestState::set))
            // Finalize as COMPLETED
            .flatMap(exec -> finalizeExecution(exec, AuditStatus.COMPLETED, "All SAGA stages succeeded"))
            // Compensate completed stages on failure and return FAILED
            .onErrorResume(err -> compensateAndFail(latestState.get(), err));
    }

    private Mono<PlanExecution> runStage(PlanExecution current, String stageName, AsyncRetriever retriever) {
        return retriever.retrieve(current.accountNo(), current.securitySymbol())
            .flatMap(result -> {
                PlanExecution updated = current.appendEvent(AuditEvent.builder()
                    .stage(stageName)
                    .status("SUCCEEDED")
                    .details(result.summary())
                    .timestamp(Instant.now())
                    .build());
                return persistAsync(updated);
            });
    }

    private Mono<PlanExecution> finalizeExecution(PlanExecution current, AuditStatus finalStatus, String details) {
        PlanExecution finalized = current.toBuilder()
            .auditStatus(finalStatus)
            .build()
            .appendEvent(AuditEvent.builder()
                .stage("SAGA_COMPLETE")
                .status(finalStatus.name())
                .details(details)
                .timestamp(Instant.now())
                .build());
        return persistAsync(finalized);
    }

    private Mono<PlanExecution> compensateAndFail(PlanExecution lastKnown, Throwable error) {
        boolean positionCompleted = lastKnown.auditEvents().stream()
            .anyMatch(e -> "POSITION_RETRIEVER".equals(e.stage()) && "SUCCEEDED".equals(e.status()));

        Mono<Void> compensation = positionCompleted
            ? positionRetriever.compensate(lastKnown.accountNo(), lastKnown.securitySymbol())
            : Mono.empty();

        return compensation.then(finalizeExecution(lastKnown, AuditStatus.FAILED, "Stage error: " + error.getMessage()));
    }

    private Mono<PlanExecution> persistAsync(PlanExecution execution) {
        return Mono.fromCallable(() -> sweepJdbcClient.updatePlanExecution(execution))
            .subscribeOn(Schedulers.boundedElastic());
    }
}
```

End to end:
- **Schedule**: `schedule(plan)` inserts a `SCHEDULED` row.
- **Claim**: `readWithPoll(...)` locks due rows with `SKIP LOCKED`, flips them to `STARTED` and
  emits them (see [poller.md](poller.md)).
- **Orchestrate**: `.flatMap(this::execute, 16)` runs up to 16 executions at once, checkpoints
  after each stage, compensates on failure and ends with `COMPLETED` or `FAILED`.

## Configuration (Nov 12, evening)

Two levers decide parallel vs. sequential:
- `maxConcurrency` in `.flatMap(mapper, maxConcurrency)`: `1` runs retrievers one at a time,
  `> 1` runs up to that many at once.
- A dedicated `Schedulers.newBoundedElastic(...)` for retriever I/O, so slow external APIs do
  not starve the rest of the application.

```java
@Data
@Configuration
@ConfigurationProperties(prefix = "sweep.saga.reactive")
public class SweepReactiveProperties {

    /** 1 = strict sequential; > 1 = parallel up to maxConcurrency tasks at once. */
    private int maxConcurrency = 8;

    /** Per-retriever execution timeout. */
    private Duration retrieverTimeout = Duration.ofSeconds(3);

    /** Dedicated boundedElastic scheduler settings. */
    private int maxThreads = 16;
    private int maxQueuedTasks = 100;
    private int threadTtlSeconds = 60;

    @Bean(destroyMethod = "dispose")
    public Scheduler sweepRetrieverScheduler() {
        return Schedulers.newBoundedElastic(maxThreads, maxQueuedTasks, "sweep-retriever", threadTtlSeconds, true);
    }
}
```

```yaml
sweep:
  saga:
    reactive:
      max-concurrency: 8      # 1 = sequential, > 1 = parallel
      retriever-timeout: 3s
      max-threads: 16
      max-queued-tasks: 100
      thread-ttl-seconds: 60
```

Optional third `flatMap` argument `prefetch` (default 32): lower it to 1–2 when retrievers are
heavy, to avoid eager pre-buffering.

## Stage: retrievers through a Flux, reduced with `applyTo`

Two variants with the same body: explicit retriever fields (`@Qualifier`) or an injected
`List` of all retriever beans.

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class DynamicFluxSweepSagaStage implements UnaryOperator<PlanExecution> {

    private final List<Function<PlanExecution, ? extends AuditEvent>> retrievers;
    private final SweepReactiveProperties properties;
    private final Scheduler sweepRetrieverScheduler;

    @PostConstruct
    public void logPipelineConfiguration() {
        String mode = properties.getMaxConcurrency() == 1
            ? "SEQUENTIAL (maxConcurrency=1)"
            : "PARALLEL (maxConcurrency=" + properties.getMaxConcurrency() + ")";
        String wiredNames = retrievers.stream()
            .map(r -> r.getClass().getSimpleName())
            .collect(Collectors.joining(", "));
        log.info("Constructed SweepSagaStage [DYNAMIC List Injection] in {} mode with {} active retrievers: [{}]",
            mode, retrievers.size(), wiredNames);
    }

    @Override
    public PlanExecution apply(PlanExecution initialExecution) {
        return Flux.fromIterable(retrievers)
            .flatMap(retriever -> {
                String retrieverName = retriever.getClass().getSimpleName();
                return Mono.fromCallable(() -> (AuditEvent) retriever.apply(initialExecution))
                    .subscribeOn(sweepRetrieverScheduler)
                    .timeout(properties.getRetrieverTimeout())
                    .onErrorResume(ex -> {
                        log.warn("Retriever [{}] failed during Flux execution: {}", retrieverName, ex.getMessage());
                        return Mono.just(new FailedAuditEvent(retrieverName, ex));
                    });
            }, properties.getMaxConcurrency())
            .reduce(initialExecution, (currentExecution, auditEvent) -> auditEvent.applyTo(currentExecution))
            .block();
    }
}
```

`ExplicitFluxSweepSagaStage` is the same, with three `@Qualifier` fields (`balanceRetriever`,
`creditLimitRetriever`, `riskScoreRetriever`) and `Flux.fromIterable(List.of(...))` over them.

### Tests (JUnit 5 + Mockito, no Spring context)

- **Parallel** (`maxConcurrency = 3`): three retrievers sleeping 200 ms each finish in under
  500 ms.
- **Sequential** (`maxConcurrency = 1`): the same three take at least 600 ms.
- **Resilience**: when one retriever throws or times out, a `FailedAuditEvent` is applied and
  the others still enrich the execution.

```java
@Test
void dynamicStage_shouldRecoverWithFailedAuditEvent_whenOneRetrieverFails() {
    properties.setMaxConcurrency(3);
    when(balanceRetriever.apply(any())).thenReturn(new StubSuccessAuditEvent("BALANCE_OK"));
    when(creditLimitRetriever.apply(any())).thenThrow(new RuntimeException("Downstream timeout"));
    when(riskScoreRetriever.apply(any())).thenReturn(new StubSuccessAuditEvent("RISK_OK"));

    DynamicFluxSweepSagaStage stage = new DynamicFluxSweepSagaStage(
        List.of(balanceRetriever, creditLimitRetriever, riskScoreRetriever), properties, testScheduler);

    PlanExecution result = stage.apply(new PlanExecution());

    assertEquals(3, result.getAppliedEvents().size());
    assertTrue(result.getAppliedEvents().stream().anyMatch(e -> e instanceof FailedAuditEvent));
}
```

The timing tests stub each retriever with `Thread.sleep(200)` and assert elapsed time
(`< 500` ms parallel, `>= 600` ms sequential) on a test `Schedulers.newBoundedElastic(8, 50, "test-sweep", 10, true)`.

## Fitting the current model

The stage note already uses the shape `plans-core` now has (`auditEvent.applyTo(execution)`).
Gaps to close when this becomes code:

1. **Reduce in declared order, not arrival order.** `flatMap(..., maxConcurrency)` emits events
   as retrievers finish, so the fold order changes from run to run. That is safe only while
   every derived property has exactly one writing event. Use `flatMapSequential(...)` (same
   concurrency, results in declared order) so `PlanExecution` is the same on every run.
2. **No `.block()` in a stage.** The orchestrator calls stages from reactive threads; blocking
   there can stall the pipeline (Reactor rejects `block()` on non-blocking threads). Make the
   stage `Function<PlanExecution, Mono<PlanExecution>>` and compose it with `flatMap`.
3. **One failure type per retriever.** `FailedAuditEvent(retrieverName, ex)` is a generic event,
   but events are one-to-one with retrievers, and `AuditStatus` has a failure status per
   retriever (`PROP_ONE_FAILED`, …). Let each retriever also create its own failure (and
   retry) event, e.g. `Retriever<E> { Mono<E> retrieve(PlanExecution in); E failed(Instant at, Throwable cause); }`,
   so the stage's `onErrorResume` and `timeout` produce the right type.
4. **Timeouts and retries are transient first.** A timeout should first become the retriever's
   `*_RETRY` status (not sticky), and only a final attempt its `*_FAILED` status (sticky).
5. **`onErrorContinue`** in `startPipeline` is easy to misuse (it depends on operator support
   and can drop items silently). Handle errors per execution inside `execute` (it already ends
   with `onErrorResume`) and keep the outer pipeline error-free.
6. **Checkpoint with the version.** `persistAsync` after each stage should use
   `PlanExecution.version` and extend the lease (see [poller.md](poller.md#fitting-the-current-model)).
7. **Compensation from typed events.** `compensateAndFail` searches events by stage-name
   strings; with typed events it is a pattern match on the event type and its status.
8. **Unknown events.** Before running a stage, check `hasUnknownEvents()`; if true, do not run
   it, and let the lease expire so a newer worker takes the execution.
