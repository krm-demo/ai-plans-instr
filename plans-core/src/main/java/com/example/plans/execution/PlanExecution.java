package com.example.plans.execution;

import com.example.plans.model.PlanInstructions;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Persistent saga state of one run of a plan: an immutable snapshot that every audit event
 * turns into a new snapshot.
 *
 * <p>A saga stage reduces the events of its retrievers with
 * {@code planExecOut = auditEvent.applyTo(planExecOut)} (or {@link #applyAll}). Because each
 * step returns a new object, the input every retriever received is never changed under it.
 *
 * @param executionId id of this run
 * @param plan        snapshot of the plan instructions taken when the run was scheduled; a later
 *                    edit of the plan does not change a running execution
 * @param scheduledAt when the run was scheduled
 * @param auditStatus derived: last status, except that the first failure sticks
 * @param derived     derived: properties computed by the events
 * @param auditEvents every event applied so far, in the order applied
 * @param version     optimistic-locking version of the stored row; {@code null} before insert
 */
public record PlanExecution(
        UUID executionId,
        PlanInstructions plan,
        Instant scheduledAt,
        AuditStatus auditStatus,
        Derived derived,
        List<AuditEvent> auditEvents,
        Long version) {

    public PlanExecution {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(scheduledAt, "scheduledAt");
        Objects.requireNonNull(auditStatus, "auditStatus");
        derived = derived == null ? Derived.EMPTY : derived;
        auditEvents = auditEvents == null ? List.of() : List.copyOf(auditEvents);
    }

    /** A new, not yet stored execution of {@code plan}. */
    public static PlanExecution schedule(UUID executionId, PlanInstructions plan, Instant scheduledAt) {
        return new PlanExecution(executionId, plan, scheduledAt, AuditStatus.SCHEDULED,
                Derived.EMPTY, List.of(), null);
    }

    /**
     * Applies one event: the event computes the derived properties (only when its status
     * carries data), the status is {@linkplain AuditStatus#merge merged}, and the event is
     * appended. Returns a new execution; this one is unchanged.
     */
    public PlanExecution apply(AuditEvent event) {
        Objects.requireNonNull(event, "event");
        AuditStatus incoming = event.status();
        Derived next = incoming != null && incoming.carriesData() ? event.derive(this) : derived;
        List<AuditEvent> events = new ArrayList<>(auditEvents.size() + 1);
        events.addAll(auditEvents);
        events.add(event);
        return new PlanExecution(executionId, plan, scheduledAt,
                AuditStatus.merge(auditStatus, incoming), next, events, version);
    }

    /** Applies the events in the given order. */
    public PlanExecution applyAll(Iterable<? extends AuditEvent> events) {
        PlanExecution out = this;
        for (AuditEvent event : events) {
            out = event.applyTo(out);
        }
        return out;
    }

    /**
     * Recomputes status and derived properties from {@link #auditEvents()}, starting from a
     * freshly scheduled execution. Equal to {@code this} unless the stored state was changed
     * some other way, so it can check a row read from the database.
     */
    public PlanExecution replay() {
        return new PlanExecution(executionId, plan, scheduledAt, AuditStatus.SCHEDULED,
                Derived.EMPTY, List.of(), version).applyAll(auditEvents);
    }

    /**
     * Whether a newer build wrote an event this build does not know. A worker should not run
     * the next stage of such an execution: it would compute derived properties without that
     * event. Leave it for a newer worker (for example let its visibility timeout expire).
     */
    @JsonIgnore
    public boolean hasUnknownEvents() {
        return auditEvents.stream().anyMatch(UnknownAuditEvent.class::isInstance);
    }

    /**
     * Properties computed from the events. Each property has exactly one event type that
     * writes it, so the result does not depend on the order in which events of one parallel
     * group arrive.
     *
     * @param ddaBalance     written by {@link DdaBalanceEvent}
     * @param diffAmt        written by {@link DdaBalanceEvent}: ddaBalance - thresholdMax
     * @param positionsValue written by {@link InvPositionsEvent}
     * @param sweepAmt       written by {@link SweepOrderEvent}
     */
    public record Derived(
            BigDecimal ddaBalance,
            BigDecimal diffAmt,
            BigDecimal positionsValue,
            BigDecimal sweepAmt) {

        public static final Derived EMPTY = new Derived(null, null, null, null);

        public Derived withDdaBalance(BigDecimal ddaBalance, BigDecimal diffAmt) {
            return new Derived(ddaBalance, diffAmt, positionsValue, sweepAmt);
        }

        public Derived withPositionsValue(BigDecimal positionsValue) {
            return new Derived(ddaBalance, diffAmt, positionsValue, sweepAmt);
        }

        public Derived withSweepAmt(BigDecimal sweepAmt) {
            return new Derived(ddaBalance, diffAmt, positionsValue, sweepAmt);
        }
    }
}
