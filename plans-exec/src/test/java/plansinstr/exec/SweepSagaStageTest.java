package plansinstr.exec;

import plansinstr.execution.AuditEvent;
import plansinstr.execution.AuditStatus;
import plansinstr.execution.PlanExecution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static plansinstr.exec.TestData.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SweepSagaStageTest {

    private final Scheduler scheduler = Schedulers.newBoundedElastic(8, 100, "test-retriever", 10, true);
    private final PlanExecution input = PlanExecution.schedule(UUID.randomUUID(), TestData.planInstr("500.00"), T0);

    @AfterEach
    void tearDown() {
        scheduler.dispose();
    }

    private static TestRetriever<PropOneEvent> propOne(String name, long delayMs, String value) {
        return new TestRetriever<>(name, Duration.ofMillis(delayMs),
                in -> PropOneEvent.retrieved(T0, value), (in, e) -> PropOneEvent.failed(T0, e));
    }

    private static TestRetriever<PropTwoEvent> propTwo(String name, long delayMs, String value) {
        return new TestRetriever<>(name, Duration.ofMillis(delayMs),
                in -> PropTwoEvent.completed(T0, value), (in, e) -> PropTwoEvent.failed(T0, e));
    }

    private SweepSagaStage stage(int maxConcurrency, Duration timeout, List<? extends Retriever<? extends AuditEvent>> retrievers) {
        return new SweepSagaStage("test", retrievers, maxConcurrency, timeout, scheduler);
    }

    private static long millis(Runnable action) {
        long start = System.nanoTime();
        action.run();
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    @Test
    @DisplayName("maxConcurrency > 1 runs retrievers at the same time")
    void parallel() {
        SweepSagaStage stage = stage(3, Duration.ofSeconds(2),
                List.of(propOne("a", 300, "1"), propOne("b", 300, "2"), propTwo("c", 300, "3")));

        long elapsed = millis(() -> assertEquals(3, stage.apply(input).block().auditEvents().size()));

        assertTrue(elapsed < 800, "expected parallel run, took " + elapsed + " ms");
    }

    @Test
    @DisplayName("maxConcurrency 1 runs retrievers one after another")
    void sequential() {
        SweepSagaStage stage = stage(1, Duration.ofSeconds(2),
                List.of(propOne("a", 300, "1"), propOne("b", 300, "2"), propTwo("c", 300, "3")));

        long elapsed = millis(() -> assertEquals(3, stage.apply(input).block().auditEvents().size()));

        assertTrue(elapsed >= 900, "expected sequential run, took " + elapsed + " ms");
    }

    @Test
    @DisplayName("events are folded in declared order, not in the order retrievers finish")
    void declaredOrder() {
        // "slow" finishes last but is listed first, so "fast" is applied last and wins
        SweepSagaStage stage = stage(2, Duration.ofSeconds(2),
                List.of(propOne("slow", 400, "700.00"), propOne("fast", 0, "900.00")));

        for (int i = 0; i < 5; i++) {
            PlanExecution out = stage.apply(input).block();
            assertEquals(new BigDecimal("400.00"), out.derivedPropOne());
            assertEquals(new BigDecimal("700.00"), ((PropOneEvent) out.auditEvents().get(0)).value());
        }
    }

    @Test
    @DisplayName("an exception becomes the retriever's own failure event; the others still apply")
    void failureEvent() {
        TestRetriever<PropOneEvent> broken = new TestRetriever<>("broken", Duration.ZERO,
                in -> { throw new IllegalStateException("downstream 503"); },
                (in, e) -> PropOneEvent.failed(T0, e));
        SweepSagaStage stage = stage(2, Duration.ofSeconds(2), List.of(broken, propTwo("ok", 0, "5")));

        PlanExecution out = stage.apply(input).block();

        PropOneEvent failed = assertInstanceOf(PropOneEvent.class, out.auditEvents().get(0));
        assertEquals(AuditStatus.PROP_ONE_FAILED, failed.status());
        assertTrue(failed.error().contains("downstream 503"), failed.error());
        assertEquals(AuditStatus.PROP_ONE_FAILED, out.auditStatus());   // first failure sticks
        assertEquals(new BigDecimal("5.00"), out.derivedPropTwo());
    }

    @Test
    @DisplayName("a retriever slower than the timeout becomes its failure event")
    void timeout() {
        SweepSagaStage stage = stage(1, Duration.ofMillis(100), List.of(propOne("slow", 1000, "1")));

        PlanExecution out = stage.apply(input).block();

        assertEquals(AuditStatus.PROP_ONE_FAILED, out.auditStatus());
        assertTrue(((PropOneEvent) out.auditEvents().get(0)).error().contains("Timeout"));
    }

    @Test
    @DisplayName("the input is never changed")
    void inputUnchanged() {
        stage(2, Duration.ofSeconds(1), List.of(propOne("a", 0, "1"))).apply(input).block();

        assertTrue(input.auditEvents().isEmpty());
        assertEquals(AuditStatus.SCHEDULED, input.auditStatus());
    }
}
