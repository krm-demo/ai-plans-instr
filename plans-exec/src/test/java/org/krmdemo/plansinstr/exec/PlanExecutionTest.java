package org.krmdemo.plansinstr.exec;

import org.krmdemo.plansinstr.core.model.PlanExecution;
import org.krmdemo.plansinstr.core.model.enums.AuditStatus;
import org.krmdemo.plansinstr.core.model.events.AuditEvent;
import org.krmdemo.plansinstr.core.model.events.UnknownAuditEvent;
import org.krmdemo.plansinstr.exec.model.events.BalanceEvent;
import org.krmdemo.plansinstr.exec.model.events.PropOneEvent;
import org.krmdemo.plansinstr.exec.model.events.PropTwoEvent;
import org.krmdemo.plansinstr.exec.model.events.SweepEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.krmdemo.plansinstr.exec.TestPlans.amt;
import static org.krmdemo.plansinstr.exec.TestPlans.at;
import static org.krmdemo.plansinstr.exec.TestPlans.scheduled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanExecutionTest {

    @Test
    @DisplayName("an event derives its property from the execution it is applied to")
    void derives() {
        PlanExecution out = PropOneEvent.passed(at(1), "730.25").applyTo(scheduled());

        assertEquals(AuditStatus.CHECK_ONE_PASSED, out.auditStatus());
        assertEquals(amt("230.25"), out.derivedPropOne());
        assertNull(out.derivedPropTwo());
        assertEquals(1, out.auditEvents().size());
    }

    @Test
    @DisplayName("a later event of the same type overwrites the derived property")
    void laterEventOverwrites() {
        PlanExecution out = scheduled().applyAll(List.of(
                PropOneEvent.passed(at(1), "730.25"),
                PropOneEvent.passed(at(2), "400.00")));

        assertEquals(amt("-100.00"), out.derivedPropOne());
    }

    @Test
    @DisplayName("statuses overwrite each other, but the first failure sticks")
    void firstFailureSticks() {
        PlanExecution out = scheduled().applyAll(List.of(
                PropOneEvent.passed(at(1), "730.25"),
                PropTwoEvent.failed(at(2), AuditStatus.HIGH_BALANCE, "service 503"),
                PropOneEvent.failed(at(3), "later failure"),
                PropTwoEvent.passed(at(4), AuditStatus.HIGH_BALANCE, "230.25")));

        assertEquals(AuditStatus.CHECK_HIGH_FAILED, out.auditStatus());
        assertEquals(amt("230.25"), out.derivedPropTwo());   // derived values still follow the events
        assertEquals(4, out.auditEvents().size());
    }

    /** Runs the whole saga on one execution, one event after another. */
    private static PlanExecution runSaga(PlanExecution start, String balance) {
        PlanExecution checked = PropOneEvent.passed(at(1), balance).applyTo(start);
        PlanExecution split = BalanceEvent.of(at(2), checked).applyTo(checked);
        PlanExecution passed = PropTwoEvent.passed(at(3), split.auditStatus(), "12.00").applyTo(split);
        return SweepEvent.swept(at(4), passed.auditStatus()).applyTo(passed);
    }

    private static List<AuditStatus> statuses(PlanExecution execution) {
        return execution.auditEvents().stream().map(AuditEvent::status).toList();
    }

    @Test
    @DisplayName("a balance below the threshold takes the Swept-In branch")
    void sweptIn() {
        PlanExecution out = runSaga(scheduled(), "400.00");   // threshold 500

        assertEquals(List.of(AuditStatus.CHECK_ONE_PASSED, AuditStatus.LOW_BALANCE,
                AuditStatus.CHECK_LOW_PASSED, AuditStatus.SWEPT_IN), statuses(out));
        assertEquals(AuditStatus.SWEPT_IN, out.auditStatus());
        assertTrue(out.auditStatus().isFinal());
    }

    @Test
    @DisplayName("a balance at or above the threshold takes the Swept-Out branch")
    void sweptOut() {
        PlanExecution out = runSaga(scheduled(), "730.25");
        PlanExecution atThreshold = runSaga(scheduled(), "500.00");

        assertEquals(List.of(AuditStatus.CHECK_ONE_PASSED, AuditStatus.HIGH_BALANCE,
                AuditStatus.CHECK_HIGH_PASSED, AuditStatus.SWEPT_OUT), statuses(out));
        assertEquals(AuditStatus.SWEPT_OUT, atThreshold.auditStatus());   // balance ≥ T
    }

    @Test
    @DisplayName("a failure grounds out in the failure pair of its checkpoint")
    void failureGroundsOutPerCheckpoint() {
        assertEquals(AuditStatus.CHECK_ONE_FAILED, AuditStatus.SCHEDULED.failure(false));
        assertEquals(AuditStatus.CHECK_ONE_TIMEOUT, AuditStatus.SCHEDULED.failure(true));
        assertEquals(AuditStatus.CHECK_LOW_FAILED, AuditStatus.LOW_BALANCE.failure(false));
        assertEquals(AuditStatus.CHECK_LOW_TIMEOUT, AuditStatus.CHECK_LOW_PASSED.failure(true));
        assertEquals(AuditStatus.CHECK_HIGH_FAILED, AuditStatus.HIGH_BALANCE.failure(false));
        assertEquals(AuditStatus.CHECK_HIGH_TIMEOUT, AuditStatus.HIGH_BALANCE.failure(true));
        assertEquals(AuditStatus.CHECK_LOW_FAILED, AuditStatus.CHECK_LOW_FAILED.failure(true));   // sticks
        assertEquals(AuditStatus.CHECK_HIGH_TIMEOUT,
                PropTwoEvent.failed(at(1), AuditStatus.HIGH_BALANCE, new java.util.concurrent.TimeoutException()).status());
    }

    @Test
    @DisplayName("a transition the diagram does not have is rejected")
    void noSuchTransition() {
        assertThrows(IllegalStateException.class, AuditStatus.SCHEDULED::checkPassed);
        assertThrows(IllegalStateException.class, AuditStatus.LOW_BALANCE::swept);
        assertThrows(IllegalStateException.class, () -> AuditStatus.SWEPT_IN.failure(false));
    }

    @Test
    @DisplayName("events without data do not wipe derived properties")
    void failureKeepsDerived() {
        PlanExecution out = scheduled().applyAll(List.of(
                PropOneEvent.passed(at(1), "730.25"),
                PropOneEvent.failed(at(2), "boom")));

        assertEquals(amt("230.25"), out.derivedPropOne());
        assertEquals(AuditStatus.CHECK_ONE_FAILED, out.auditStatus());
    }

    @Test
    @DisplayName("only derived properties of what derive() returns are kept")
    void deriveCannotChangeOtherFields() {
        AuditEvent rogue = new RogueEvent();
        PlanExecution in = scheduled();
        PlanExecution out = in.apply(rogue);

        assertEquals(in.executionId(), out.executionId());
        assertEquals(AuditStatus.CHECK_ONE_PASSED, out.auditStatus());
        assertEquals(List.of(rogue), out.auditEvents());
        assertEquals(amt("7.00"), out.derivedPropTwo());
    }

    @Test
    @DisplayName("apply returns a new execution and leaves the input unchanged")
    void inputIsNeverChanged() {
        PlanExecution in = scheduled();
        PlanExecution out = in.apply(PropOneEvent.passed(at(1), "1.00"));

        assertEquals(AuditStatus.SCHEDULED, in.auditStatus());
        assertTrue(in.auditEvents().isEmpty());
        assertNull(in.derivedPropOne());
        assertThrows(UnsupportedOperationException.class,
                () -> out.auditEvents().add(PropTwoEvent.failed(at(2), AuditStatus.HIGH_BALANCE, "x")));
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
        PlanExecution out = runSaga(scheduled(), "730.25");

        assertEquals(out, out.replay());
        assertEquals(AuditStatus.SWEPT_OUT, out.auditStatus());
    }

    @Test
    @DisplayName("an unknown event keeps status and derived properties and flags the execution")
    void unknownEvent() {
        PlanExecution before = PropOneEvent.passed(at(1), "730.25").applyTo(scheduled());
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

        PlanExecution checked = PropOneEvent.passed(at(1), "10").applyTo(noThreshold);

        assertNull(checked.derivedPropOne());
        assertThrows(IllegalStateException.class, () -> BalanceEvent.of(at(2), checked));   // no split without T
    }

    /** Record patterns with a default branch: the interface is open, so default is required. */
    static String describe(AuditEvent event) {
        return switch (event) {
            case PropOneEvent(var at, var status, var value, var error) when status.isFailed() ->
                    "prop one failed: " + error;
            case PropOneEvent(var at, var status, var value, var error) -> "prop one " + value;
            case UnknownAuditEvent(var raw) -> "unknown " + raw.get("__eventType__");
            case null, default -> "other " + (event == null ? null : event.eventType());
        };
    }

    @Test
    @DisplayName("switch with record patterns over the open interface")
    void recordPatterns() {
        assertEquals("prop one 1.00", describe(PropOneEvent.passed(at(1), "1.00")));
        assertEquals("prop one failed: boom", describe(PropOneEvent.failed(at(1), "boom")));
        assertEquals("unknown FX_RATE", describe(new UnknownAuditEvent(Map.of("__eventType__", "FX_RATE"))));
        assertEquals("other PROP_TWO", describe(PropTwoEvent.passed(at(1), AuditStatus.HIGH_BALANCE, "2")));
    }

    /** An event whose derive() also tries to change status, events and identity. */
    @com.fasterxml.jackson.annotation.JsonTypeName("ROGUE")
    private record RogueEvent() implements AuditEvent {
        @Override public java.time.Instant at() { return TestPlans.T0; }
        @Override public AuditStatus status() { return AuditStatus.CHECK_ONE_PASSED; }
        @Override public PlanExecution derive(PlanExecution input) {
            return new PlanExecution(UUID.randomUUID(), input.planInstr(), input.scheduledAt(), AuditStatus.SWEPT_OUT,
                    input.derivedPropOne(), new BigDecimal("7"), List.of(), 99L);
        }
    }
}
