package com.example.plans.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.example.plans.execution.TestPlans.amt;
import static com.example.plans.execution.TestPlans.at;
import static com.example.plans.execution.TestPlans.scheduled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanExecutionTest {

    @Test
    @DisplayName("an event derives its property from the execution it is applied to")
    void derives() {
        PlanExecution out = PropOneEvent.retrieved(at(1), "730.25").applyTo(scheduled());

        assertEquals(AuditStatus.PROP_ONE_RETRIEVED, out.auditStatus());
        assertEquals(amt("230.25"), out.derivedPropOne());
        assertNull(out.derivedPropTwo());
        assertEquals(1, out.auditEvents().size());
    }

    @Test
    @DisplayName("a later event of the same type overwrites the derived property")
    void laterEventOverwrites() {
        PlanExecution out = scheduled().applyAll(List.of(
                PropOneEvent.retrieved(at(1), "730.25"),
                PropOneEvent.retrieved(at(2), "400.00")));

        assertEquals(amt("-100.00"), out.derivedPropOne());
    }

    @Test
    @DisplayName("statuses overwrite each other, but the first failure sticks")
    void firstFailureSticks() {
        PlanExecution out = scheduled().applyAll(List.of(
                PropOneEvent.retrieved(at(1), "730.25"),
                PropTwoEvent.failed(at(2), "service 503"),
                PropOneEvent.failed(at(3), "later failure"),
                PropTwoEvent.completed(at(4), "230.25")));

        assertEquals(AuditStatus.PROP_TWO_FAILED, out.auditStatus());
        assertEquals(amt("230.25"), out.derivedPropTwo());   // derived values still follow the events
        assertEquals(4, out.auditEvents().size());
    }

    @Test
    @DisplayName("a retry status is transient: a later success replaces it")
    void retryIsNotSticky() {
        PlanExecution out = scheduled().applyAll(List.of(
                PropOneEvent.retry(at(1), "timeout"),
                PropOneEvent.retrieved(at(2), "730.25")));

        assertEquals(AuditStatus.PROP_ONE_RETRIEVED, out.auditStatus());
    }

    @Test
    @DisplayName("events without data do not wipe derived properties")
    void failureKeepsDerived() {
        PlanExecution out = scheduled().applyAll(List.of(
                PropOneEvent.retrieved(at(1), "730.25"),
                PropOneEvent.failed(at(2), "boom")));

        assertEquals(amt("230.25"), out.derivedPropOne());
        assertEquals(AuditStatus.PROP_ONE_FAILED, out.auditStatus());
    }

    @Test
    @DisplayName("only derived properties of what derive() returns are kept")
    void deriveCannotChangeOtherFields() {
        AuditEvent rogue = new RogueEvent();
        PlanExecution in = scheduled();
        PlanExecution out = in.apply(rogue);

        assertEquals(in.executionId(), out.executionId());
        assertEquals(AuditStatus.PROP_TWO_RETRIEVED, out.auditStatus());
        assertEquals(List.of(rogue), out.auditEvents());
        assertEquals(amt("7.00"), out.derivedPropTwo());
    }

    @Test
    @DisplayName("apply returns a new execution and leaves the input unchanged")
    void inputIsNeverChanged() {
        PlanExecution in = scheduled();
        PlanExecution out = in.apply(PropOneEvent.retrieved(at(1), "1.00"));

        assertEquals(AuditStatus.SCHEDULED, in.auditStatus());
        assertTrue(in.auditEvents().isEmpty());
        assertNull(in.derivedPropOne());
        assertThrows(UnsupportedOperationException.class,
                () -> out.auditEvents().add(PropTwoEvent.failed(at(2), "x")));
    }

    @Test
    @DisplayName("amounts are stored as numeric(18,2)")
    void numeric18x2() {
        PlanExecution out = scheduled().withDerivedPropOne(new BigDecimal("400"))
                .withDerivedPropTwo(new BigDecimal("0.125"));

        assertEquals("400.00", out.derivedPropOne().toPlainString());
        assertEquals("0.12", out.derivedPropTwo().toPlainString());   // half-even
        assertEquals("9999999999999999.99",
                scheduled().withDerivedPropOne(new BigDecimal("9999999999999999.99")).derivedPropOne().toPlainString());
        assertThrows(IllegalArgumentException.class,
                () -> scheduled().withDerivedPropOne(new BigDecimal("10000000000000000")));
    }

    @Test
    @DisplayName("replay recomputes the same state from the stored events")
    void replay() {
        PlanExecution out = scheduled().applyAll(List.of(
                PropOneEvent.retry(at(1), "timeout"),
                PropOneEvent.retrieved(at(2), "730.25"),
                PropTwoEvent.completed(at(3), "230.25")));

        assertEquals(out, out.replay());
        assertEquals(AuditStatus.COMPLETED, out.auditStatus());
    }

    @Test
    @DisplayName("an unknown event keeps status and derived properties and flags the execution")
    void unknownEvent() {
        PlanExecution before = PropOneEvent.retrieved(at(1), "730.25").applyTo(scheduled());
        PlanExecution out = new UnknownAuditEvent(Map.of("__eventType__", "FX_RATE", "rate", 1.1))
                .applyTo(before);

        assertEquals(before.auditStatus(), out.auditStatus());
        assertEquals(before.derivedPropOne(), out.derivedPropOne());
        assertTrue(out.hasUnknownEvents());
        assertFalse(before.hasUnknownEvents());
    }

    @Test
    @DisplayName("a missing threshold gives a null derived value instead of an exception")
    void noThreshold() {
        PlanExecution noThreshold = PlanExecution.schedule(UUID.randomUUID(), TestPlans.plan(null), TestPlans.T0);

        assertNull(PropOneEvent.retrieved(at(1), "10").applyTo(noThreshold).derivedPropOne());
    }

    /** Record patterns with a default branch: the interface is open, so default is required. */
    static String describe(AuditEvent event) {
        return switch (event) {
            case PropOneEvent(var at, var status, var value, var error) when status.isFailure() ->
                    "prop one failed: " + error;
            case PropOneEvent(var at, var status, var value, var error) -> "prop one " + value;
            case UnknownAuditEvent(var raw) -> "unknown " + raw.get("__eventType__");
            case null, default -> "other " + (event == null ? null : event.eventType());
        };
    }

    @Test
    @DisplayName("switch with record patterns over the open interface")
    void recordPatterns() {
        assertEquals("prop one 1.00", describe(PropOneEvent.retrieved(at(1), "1.00")));
        assertEquals("prop one failed: boom", describe(PropOneEvent.failed(at(1), "boom")));
        assertEquals("unknown FX_RATE", describe(new UnknownAuditEvent(Map.of("__eventType__", "FX_RATE"))));
        assertEquals("other PROP_TWO", describe(PropTwoEvent.retrieved(at(1), "2")));
    }

    /** An event whose derive() also tries to change status, events and identity. */
    @com.fasterxml.jackson.annotation.JsonTypeName("ROGUE")
    private record RogueEvent() implements AuditEvent {
        @Override public java.time.Instant at() { return TestPlans.T0; }
        @Override public AuditStatus status() { return AuditStatus.PROP_TWO_RETRIEVED; }
        @Override public PlanExecution derive(PlanExecution input) {
            return new PlanExecution(UUID.randomUUID(), input.plan(), input.scheduledAt(), AuditStatus.COMPLETED,
                    input.derivedPropOne(), new BigDecimal("7"), List.of(), 99L);
        }
    }
}
