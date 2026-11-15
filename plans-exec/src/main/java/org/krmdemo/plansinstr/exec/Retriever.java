package org.krmdemo.plansinstr.exec;

import org.krmdemo.plansinstr.core.model.PlanExecution;
import org.krmdemo.plansinstr.core.model.events.AuditEvent;
import reactor.core.publisher.Mono;

/**
 * One step of a saga stage. It reads the whole execution and returns its one event type
 * (one-to-one); it never changes the execution.
 *
 * <p>Retrievers of one stage all receive the same input, so a retriever that needs another's
 * result belongs to a later stage.
 *
 * @param <E> the event type this retriever produces
 */
public interface Retriever<E extends AuditEvent> {

    /** Name for logs; defaults to the class name. */
    default String name() {
        return getClass().getSimpleName();
    }

    /**
     * Retrieves and returns the event. May block: the stage subscribes on its own scheduler.
     * Should complete with exactly one event.
     */
    Mono<E> retrieve(PlanExecution input);

    /**
     * This retriever's event for a failed attempt (an exception, a timeout or no result),
     * typically with this retriever's retry or failure status.
     */
    E failed(PlanExecution input, Throwable cause);
}
