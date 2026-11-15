package com.example.plans.execution;

/**
 * One step of a saga stage. It reads the whole execution and returns its one event type; it
 * never changes the execution. Retrievers of one parallel group all receive the same input,
 * so a retriever that needs another's result must run after it, in a later step.
 *
 * <p>Reactive variant: {@code Mono<E> retrieve(PlanExecution input)}. Reduce a parallel group
 * in declared order, not in arrival order, e.g.
 * {@code Flux.mergeSequential(monos).reduce(input, (pe, ev) -> ev.applyTo(pe))}.
 *
 * @param <E> the event type this retriever produces (one-to-one)
 */
@FunctionalInterface
public interface Retriever<E extends AuditEvent> {

    E retrieve(PlanExecution input);
}
