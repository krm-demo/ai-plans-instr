package com.example.plans.exec;

import com.example.plans.execution.PlanExecution;
import com.example.plans.model.PlanInstructions;
import com.example.plans.model.PlanStatus;
import com.example.plans.persistence.UuidV7Generator;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * The saga: poller → stages → checkpoints → completion and the next execution.
 *
 * <pre>
 * poller.poll()                       endless Flux of claimed executions
 *   .flatMap(execute, maxExecutions)  up to maxExecutions at once
 *
 * execute(claimed):
 *   unknown events?  → leave it; the lease expires and a newer worker takes it
 *   for each stage:  pe = stage.apply(pe), then checkpoint (version check, extends the lease);
 *                    stages after the first failure are skipped
 *   complete:        save as finished and insert the next execution, in one transaction.
 *                    The next one embeds a copy of the plan instructions current at that
 *                    moment (planInstr); there is no join to plan_instructions afterwards.
 * </pre>
 *
 * <p>Errors of one execution (including a lost lease) are logged and do not stop the pipeline;
 * the execution is retried when its lease expires.
 */
public final class SweepSagaPipeline implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(SweepSagaPipeline.class.getName());

    /** When to run a plan next, given the finished execution; empty = do not reschedule. */
    @FunctionalInterface
    public interface NextRunPolicy {
        Optional<Instant> nextRunAfter(PlanExecution finished);
    }

    private final SweepSagaPostgresPoller poller;
    private final List<SweepSagaStage> stages;
    private final PlanExecutionStore store;
    private final Function<UUID, Optional<PlanInstructions>> currentPlanInstr;
    private final NextRunPolicy nextRun;
    private final SweepSagaProperties properties;
    private final Scheduler dbScheduler;
    private final Clock clock;
    private final UuidV7Generator ids = new UuidV7Generator();

    private volatile Disposable subscription;

    /**
     * @param currentPlanInstr loads the plan instructions as they are now, by plan id
     *                         (e.g. {@code planJdbcClient::load}); used only to create the next
     *                         execution
     */
    public SweepSagaPipeline(SweepSagaPostgresPoller poller,
                             List<SweepSagaStage> stages,
                             PlanExecutionStore store,
                             Function<UUID, Optional<PlanInstructions>> currentPlanInstr,
                             NextRunPolicy nextRun,
                             SweepSagaProperties properties,
                             Scheduler dbScheduler,
                             Clock clock) {
        this.poller = Objects.requireNonNull(poller, "poller");
        this.stages = List.copyOf(stages);
        this.store = Objects.requireNonNull(store, "store");
        this.currentPlanInstr = Objects.requireNonNull(currentPlanInstr, "currentPlanInstr");
        this.nextRun = Objects.requireNonNull(nextRun, "nextRun");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.dbScheduler = Objects.requireNonNull(dbScheduler, "dbScheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Starts polling and executing. Calling it again while running does nothing. */
    public synchronized void start() {
        if (subscription != null && !subscription.isDisposed()) {
            return;
        }
        LOG.log(System.Logger.Level.INFO, "starting saga pipeline with stages {0}", stages);
        subscription = poller.poll()
                .flatMap(claimed -> execute(claimed).onErrorResume(error -> {
                    LOG.log(System.Logger.Level.WARNING,
                            "execution " + claimed.executionId() + " failed; retried after its lease", error);
                    return Mono.empty();
                }), properties.maxExecutions())
                .subscribe();
    }

    /** Stops polling; executions in flight are cancelled and retried after their lease. */
    public synchronized void stop() {
        if (subscription != null) {
            subscription.dispose();
            subscription = null;
        }
    }

    @Override
    public void close() {
        stop();
    }

    public boolean isRunning() {
        Disposable current = subscription;
        return current != null && !current.isDisposed();
    }

    /** Schedules the first execution of a plan. */
    public Mono<PlanExecution> schedule(PlanInstructions planInstr, Instant scheduledAt) {
        return onDb(() -> store.insert(PlanExecution.schedule(newId(), planInstr, scheduledAt)));
    }

    /**
     * Runs one claimed execution through all stages and completes it. Empty when the execution
     * has events this build does not know (it is left for a newer worker).
     */
    public Mono<PlanExecution> execute(PlanExecution claimed) {
        if (claimed.hasUnknownEvents()) {
            LOG.log(System.Logger.Level.INFO,
                    "execution {0} has unknown events; leaving it to a newer worker", claimed.executionId());
            return Mono.empty();
        }
        Mono<PlanExecution> run = Mono.just(claimed);
        for (SweepSagaStage stage : stages) {
            run = run.flatMap(execution -> execution.auditStatus().isFailure()
                    ? Mono.just(execution)
                    : stage.apply(execution).flatMap(this::checkpoint));
        }
        return run.flatMap(this::complete);
    }

    private Mono<PlanExecution> checkpoint(PlanExecution execution) {
        return onDb(() -> store.checkpoint(execution, properties.lease()));
    }

    private Mono<PlanExecution> complete(PlanExecution finished) {
        return onDb(() -> store.complete(finished, nextExecution(finished)));
    }

    /** The next execution: a new id, the plan instructions as they are now, the next run time. */
    private Optional<PlanExecution> nextExecution(PlanExecution finished) {
        return nextRun.nextRunAfter(finished).flatMap(at ->
                currentPlanInstr.apply(finished.planInstr().planId())
                        .filter(planInstr -> planInstr.planStatus() == PlanStatus.ACTIVE)
                        .map(planInstr -> PlanExecution.schedule(newId(), planInstr, at)));
    }

    private UUID newId() {
        return ids.next(clock.millis());
    }

    private <T> Mono<T> onDb(java.util.concurrent.Callable<T> call) {
        return Mono.fromCallable(call).subscribeOn(dbScheduler);
    }
}
