package com.example.plans.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static com.example.plans.execution.TestPlans.amt;
import static com.example.plans.execution.TestPlans.at;
import static com.example.plans.execution.TestPlans.scheduled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanExecutionTest {

    @Test
    @DisplayName("balance event derives ddaBalance and diffAmt from the plan threshold")
    void derivesDiffAmt() {
        PlanExecution out = DdaBalanceEvent.retrieved(at(1), amt("730.25")).applyTo(scheduled());

        assertEquals(AuditStatus.DDA_BALANCE_RETRIEVED, out.auditStatus());
        assertEquals(amt("730.25"), out.derived().ddaBalance());
        assertEquals(amt("230.25"), out.derived().diffAmt());
        assertEquals(1, out.auditEvents().size());
    }

    @Test
    @DisplayName("a later event of the same type overwrites the derived properties")
    void laterEventOverwrites() {
        PlanExecution out = scheduled().applyAll(List.of(
                DdaBalanceEvent.retrieved(at(1), amt("730.25")),
                DdaBalanceEvent.retrieved(at(2), amt("400.00"))));

        assertEquals(amt("400.00"), out.derived().ddaBalance());
        assertEquals(amt("-100.00"), out.derived().diffAmt());
    }

    @Test
    @DisplayName("statuses overwrite each other, but the first failure sticks")
    void firstFailureSticks() {
        PlanExecution out = scheduled().applyAll(List.of(
                DdaBalanceEvent.retrieved(at(1), amt("730.25")),
                InvPositionsEvent.failed(at(2), "positions service 503"),
                DdaBalanceEvent.failed(at(3), "later failure"),
                SweepOrderEvent.completed(at(4), "ord-1", amt("230.25"))));

        assertEquals(AuditStatus.INV_POSITIONS_FAILED, out.auditStatus());
        // derived properties still follow the events
        assertEquals(amt("230.25"), out.derived().sweepAmt());
        assertEquals(4, out.auditEvents().size());
    }

    @Test
    @DisplayName("a retry status is transient: a later success replaces it")
    void retryIsNotSticky() {
        PlanExecution out = scheduled().applyAll(List.of(
                DdaBalanceEvent.retry(at(1), "timeout"),
                DdaBalanceEvent.retrieved(at(2), amt("730.25"))));

        assertEquals(AuditStatus.DDA_BALANCE_RETRIEVED, out.auditStatus());
    }

    @Test
    @DisplayName("events without data do not wipe derived properties")
    void failureKeepsDerived() {
        PlanExecution out = scheduled().applyAll(List.of(
                DdaBalanceEvent.retrieved(at(1), amt("730.25")),
                DdaBalanceEvent.failed(at(2), "boom")));

        assertEquals(amt("730.25"), out.derived().ddaBalance());
        assertEquals(AuditStatus.DDA_BALANCE_FAILED, out.auditStatus());
    }

    @Test
    @DisplayName("apply returns a new execution and leaves the input unchanged")
    void inputIsNeverChanged() {
        PlanExecution in = scheduled();
        PlanExecution out = in.apply(DdaBalanceEvent.retrieved(at(1), amt("1.00")));

        assertEquals(AuditStatus.SCHEDULED, in.auditStatus());
        assertTrue(in.auditEvents().isEmpty());
        assertSame(PlanExecution.Derived.EMPTY, in.derived());
        assertThrows(UnsupportedOperationException.class,
                () -> out.auditEvents().add(InvPositionsEvent.failed(at(2), "x")));
    }

    @Test
    @DisplayName("replay recomputes the same state from the stored events")
    void replay() {
        PlanExecution out = scheduled().applyAll(List.of(
                DdaBalanceEvent.retry(at(1), "timeout"),
                DdaBalanceEvent.retrieved(at(2), amt("730.25")),
                InvPositionsEvent.retrieved(at(3), amt("10000.00")),
                SweepOrderEvent.completed(at(4), "ord-1", amt("230.25"))));

        assertEquals(out, out.replay());
        assertEquals(AuditStatus.COMPLETED, out.auditStatus());
    }

    @Test
    @DisplayName("an unknown event keeps status and derived properties and flags the execution")
    void unknownEvent() {
        PlanExecution before = DdaBalanceEvent.retrieved(at(1), amt("730.25")).applyTo(scheduled());
        PlanExecution out = new UnknownAuditEvent(Map.of("__eventType__", "FX_RATE", "rate", 1.1))
                .applyTo(before);

        assertEquals(before.auditStatus(), out.auditStatus());
        assertEquals(before.derived(), out.derived());
        assertTrue(out.hasUnknownEvents());
        assertFalse(before.hasUnknownEvents());
    }

    @Test
    @DisplayName("a missing threshold gives a null diffAmt instead of an exception")
    void noThreshold() {
        PlanExecution noThreshold = PlanExecution.schedule(scheduled().executionId(),
                TestPlans.plan(null), TestPlans.T0);
        PlanExecution out = DdaBalanceEvent.retrieved(at(1), amt("10")).applyTo(noThreshold);

        assertNull(out.derived().diffAmt());
    }

    @Test
    @DisplayName("events reject a data-carrying status without data")
    void eventValidation() {
        assertThrows(NullPointerException.class,
                () -> new DdaBalanceEvent(at(1), AuditStatus.DDA_BALANCE_RETRIEVED, null, null));
    }

    /**
     * A stage: balance and positions in parallel (both see the same input), then the sweep,
     * which needs the balance. Retrievers finish in random order; the result is the same
     * because the group is reduced in declared order.
     */
    @Test
    @DisplayName("a parallel group reduced in declared order gives the same result every time")
    void parallelStageIsDeterministic() {
        Retriever<DdaBalanceEvent> balance = in -> slow(DdaBalanceEvent.retrieved(at(1), amt("730.25")));
        Retriever<InvPositionsEvent> positions = in -> slow(InvPositionsEvent.retrieved(at(1), amt("10000.00")));
        Retriever<SweepOrderEvent> sweep = in -> in.derived().diffAmt().signum() > 0
                ? SweepOrderEvent.completed(at(2), "ord-1", in.derived().diffAmt())
                : new SweepOrderEvent(at(2), AuditStatus.NOTHING_TO_SWEEP, null, amt("0"), null);

        PlanExecution first = null;
        for (int i = 0; i < 20; i++) {
            PlanExecution out = runStage(scheduled(), List.of(balance, positions), sweep);
            if (first == null) {
                first = out;
            }
            assertEquals(first, out);
        }
        assertEquals(AuditStatus.COMPLETED, first.auditStatus());
        assertEquals(amt("230.25"), first.derived().sweepAmt());
    }

    private static PlanExecution runStage(PlanExecution input,
                                          List<Retriever<? extends AuditEvent>> parallel,
                                          Retriever<? extends AuditEvent> next) {
        List<CompletableFuture<? extends AuditEvent>> futures = parallel.stream()
                .<CompletableFuture<? extends AuditEvent>>map(r -> CompletableFuture.supplyAsync(() -> r.retrieve(input)))
                .toList();
        PlanExecution out = input;
        for (CompletableFuture<? extends AuditEvent> future : futures) {   // declared order
            out = future.join().applyTo(out);
        }
        return next.retrieve(out).applyTo(out);
    }

    private static <E> E slow(E event) {
        try {
            TimeUnit.MILLISECONDS.sleep(ThreadLocalRandom.current().nextInt(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return event;
    }

    /** Compiles only while every event type is handled: the interface is sealed. */
    static String describe(AuditEvent event) {
        return switch (event) {
            case DdaBalanceEvent e -> "balance " + e.ddaBalance();
            case InvPositionsEvent e -> "positions " + e.positionsValue();
            case SweepOrderEvent e -> "sweep " + e.sweepAmt();
            case UnknownAuditEvent e -> "unknown " + e.eventType();
        };
    }

    @Test
    @DisplayName("exhaustive switch over the sealed interface")
    void exhaustiveSwitch() {
        assertEquals("balance 1.00", describe(DdaBalanceEvent.retrieved(at(1), amt("1.00"))));
        assertEquals("unknown FX_RATE", describe(new UnknownAuditEvent(Map.of("__eventType__", "FX_RATE"))));
    }
}
