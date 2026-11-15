package org.krmdemo.plansinstr.exec;

import org.krmdemo.plansinstr.core.model.PlanExecution;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.List;
import java.util.Objects;

/**
 * PGMQ-style poller: an endless {@code Flux} of executions claimed from Postgres.
 *
 * <p>Each round claims up to {@code batchSize} due rows ({@code FOR UPDATE SKIP LOCKED}, with a
 * lease) and emits them. When a round finds nothing, the next round starts after
 * {@code pollInterval}; after a full batch it starts as soon as the subscriber has taken all
 * rows of the batch. So rows are only claimed when there is demand for them, and many poller
 * instances (threads, pods, Lambdas) can run against the same table.
 *
 * <p>A failing claim (database down) is logged and retried after {@code errorBackoff}; the
 * {@code Flux} itself never ends with an error.
 */
public final class SweepSagaPostgresPoller {

    private static final System.Logger LOG = System.getLogger(SweepSagaPostgresPoller.class.getName());

    private final PlanExecutionStore store;
    private final SweepSagaProperties properties;
    private final Scheduler dbScheduler;

    public SweepSagaPostgresPoller(PlanExecutionStore store, SweepSagaProperties properties, Scheduler dbScheduler) {
        this.store = Objects.requireNonNull(store, "store");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.dbScheduler = Objects.requireNonNull(dbScheduler, "dbScheduler");
    }

    /** Endless; cancel the subscription to stop. */
    public Flux<PlanExecution> poll() {
        return Flux.defer(this::round).repeat();
    }

    /** One round: claim a batch; if it is empty, wait {@code pollInterval} before completing. */
    private Flux<PlanExecution> round() {
        return claim()
                .flatMapMany(batch -> {
                    if (batch.isEmpty()) {
                        return Mono.delay(properties.pollInterval()).thenMany(Flux.<PlanExecution>empty());
                    }
                    return Flux.fromIterable(batch);
                })
                .onErrorResume(error -> {
                    LOG.log(System.Logger.Level.WARNING, "claiming plan executions failed; retrying", error);
                    return Mono.delay(properties.errorBackoff()).thenMany(Flux.<PlanExecution>empty());
                });
    }

    private Mono<List<PlanExecution>> claim() {
        return Mono.fromCallable(() -> store.claimDue(properties.batchSize(), properties.lease()))
                .subscribeOn(dbScheduler);
    }

    /** For tests and diagnostics: one claim, no waiting. */
    public Mono<List<PlanExecution>> claimOnce() {
        return claim();
    }
}
