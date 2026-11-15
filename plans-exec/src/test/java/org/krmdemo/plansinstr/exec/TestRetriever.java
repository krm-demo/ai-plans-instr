package org.krmdemo.plansinstr.exec;

import org.krmdemo.plansinstr.core.model.PlanExecution;
import org.krmdemo.plansinstr.core.model.events.AuditEvent;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;

/** A retriever that blocks for {@code delay} (like a synchronous HTTP call), then answers. */
final class TestRetriever<E extends AuditEvent> implements Retriever<E> {

    private final String name;
    private final Duration delay;
    private final Function<PlanExecution, E> answer;
    private final BiFunction<PlanExecution, Throwable, E> failure;
    final AtomicInteger calls = new AtomicInteger();

    TestRetriever(String name, Duration delay, Function<PlanExecution, E> answer,
                  BiFunction<PlanExecution, Throwable, E> failure) {
        this.name = name;
        this.delay = delay;
        this.answer = answer;
        this.failure = failure;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Mono<E> retrieve(PlanExecution input) {
        return Mono.fromCallable(() -> {
            calls.incrementAndGet();
            Thread.sleep(delay.toMillis());
            return answer.apply(input);
        });
    }

    @Override
    public E failed(PlanExecution input, Throwable cause) {
        return failure.apply(input, cause);
    }
}
