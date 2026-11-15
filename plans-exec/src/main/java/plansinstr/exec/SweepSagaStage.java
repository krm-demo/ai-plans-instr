package plansinstr.exec;

import plansinstr.execution.AuditEvent;
import plansinstr.execution.PlanExecution;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Function;

/**
 * One saga stage: runs its retrievers against the same input and folds their events into the
 * output with {@code planExecOut = auditEvent.applyTo(planExecOut)}.
 *
 * <ul>
 *   <li>{@code maxConcurrency} 1 runs the retrievers one after another; more runs up to that
 *       many at once.</li>
 *   <li>Events are folded in the order the retrievers are listed, not in the order they
 *       finish ({@code flatMapSequential}), so the result is the same on every run.</li>
 *   <li>An exception, a timeout or an empty result becomes the retriever's own
 *       {@link Retriever#failed} event; the stage itself does not fail.</li>
 * </ul>
 */
public final class SweepSagaStage implements Function<PlanExecution, Mono<PlanExecution>> {

    private final String name;
    private final List<Retriever<? extends AuditEvent>> retrievers;
    private final int maxConcurrency;
    private final Duration retrieverTimeout;
    private final Scheduler scheduler;

    /**
     * @param name             stage name for logs
     * @param retrievers       the retrievers, in the order their events are applied
     * @param maxConcurrency   1 = sequential, more = parallel up to that many
     * @param retrieverTimeout per retriever call
     * @param scheduler        where retrievers run (they may block)
     */
    public SweepSagaStage(String name,
                          List<? extends Retriever<? extends AuditEvent>> retrievers,
                          int maxConcurrency,
                          Duration retrieverTimeout,
                          Scheduler scheduler) {
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency must be at least 1");
        }
        this.name = Objects.requireNonNull(name, "name");
        this.retrievers = List.copyOf(retrievers);
        this.maxConcurrency = maxConcurrency;
        this.retrieverTimeout = Objects.requireNonNull(retrieverTimeout, "retrieverTimeout");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    public String name() {
        return name;
    }

    @Override
    public Mono<PlanExecution> apply(PlanExecution input) {
        return Flux.fromIterable(retrievers)
                .flatMapSequential(retriever -> call(retriever, input), maxConcurrency)
                .reduce(input, (execution, event) -> event.applyTo(execution));
    }

    private Mono<AuditEvent> call(Retriever<? extends AuditEvent> retriever, PlanExecution input) {
        return Mono.<AuditEvent>defer(() -> retriever.retrieve(input))
                .subscribeOn(scheduler)
                .timeout(retrieverTimeout)
                .switchIfEmpty(Mono.error(() -> new NoSuchElementException(retriever.name() + " returned no event")))
                .onErrorResume(cause -> Mono.<AuditEvent>fromSupplier(() -> retriever.failed(input, cause)));
    }

    @Override
    public String toString() {
        return "SweepSagaStage[" + name + ", " + retrievers.size() + " retrievers, "
                + (maxConcurrency == 1 ? "sequential" : "parallel " + maxConcurrency) + "]";
    }
}
