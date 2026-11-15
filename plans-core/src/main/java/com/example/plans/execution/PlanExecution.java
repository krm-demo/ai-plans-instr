package com.example.plans.execution;

import com.example.plans.model.PlanInstructions;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * <p>Derived amounts are stored as Postgres {@code numeric(18,2)}: the constructor rounds them
 * to 2 decimals (half-even) and rejects values with more than 16 integer digits, so the record,
 * its JSON and the column always hold the same value.
 *
 * @param executionId    id of this run
 * @param planInstr      the plan instructions as they were when this run was scheduled, embedded
 *                       by value: the execution never joins or refers to the
 *                       {@code plan_instructions} table, so a later edit or deletion of the plan
 *                       cannot change or break it. The next run copies the plan instructions
 *                       current at that moment.
 * @param scheduledAt    when the run was scheduled
 * @param auditStatus    derived: last status, except that the first failure sticks
 * @param derivedPropOne derived; written by exactly one event type
 * @param derivedPropTwo derived; written by exactly one event type
 * @param auditEvents    every event applied so far, in the order applied
 * @param version        optimistic-locking version of the stored row; {@code null} before insert
 */
public record PlanExecution(
        UUID executionId,
        PlanInstructions planInstr,
        Instant scheduledAt,
        AuditStatus auditStatus,
        BigDecimal derivedPropOne,
        BigDecimal derivedPropTwo,
        List<AuditEvent> auditEvents,
        Long version) {

    /** Scale of the {@code numeric(18,2)} columns. */
    public static final int AMOUNT_SCALE = 2;
    /** Precision of the {@code numeric(18,2)} columns. */
    public static final int AMOUNT_PRECISION = 18;

    public PlanExecution {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(planInstr, "planInstr");
        Objects.requireNonNull(scheduledAt, "scheduledAt");
        Objects.requireNonNull(auditStatus, "auditStatus");
        derivedPropOne = numeric18x2("derivedPropOne", derivedPropOne);
        derivedPropTwo = numeric18x2("derivedPropTwo", derivedPropTwo);
        auditEvents = auditEvents == null ? List.of() : List.copyOf(auditEvents);
    }

    /** A new, not yet stored execution of {@code planInstr}. */
    public static PlanExecution schedule(UUID executionId, PlanInstructions planInstr, Instant scheduledAt) {
        return new PlanExecution(executionId, planInstr, scheduledAt, AuditStatus.SCHEDULED,
                null, null, List.of(), null);
    }

    /**
     * Applies one event: the event computes the derived properties (only when its status
     * carries data), the status is {@linkplain AuditStatus#merge merged}, and the event is
     * appended. Of what {@link AuditEvent#derive} returns, only the derived properties are
     * kept. Returns a new execution; this one is unchanged.
     */
    public PlanExecution apply(AuditEvent event) {
        Objects.requireNonNull(event, "event");
        AuditStatus incoming = event.status();
        PlanExecution derived = incoming != null && incoming.carriesData() ? event.derive(this) : this;
        List<AuditEvent> events = new ArrayList<>(auditEvents.size() + 1);
        events.addAll(auditEvents);
        events.add(event);
        return new PlanExecution(executionId, planInstr, scheduledAt,
                AuditStatus.merge(auditStatus, incoming),
                derived.derivedPropOne(), derived.derivedPropTwo(),
                events, version);
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
        return new PlanExecution(executionId, planInstr, scheduledAt, AuditStatus.SCHEDULED,
                null, null, List.of(), version).applyAll(auditEvents);
    }

    /**
     * Whether an event of a type this build does not know is present. A worker should not run
     * the next stage of such an execution: it would compute derived properties without that
     * event. Leave it for a worker that knows it (for example let its visibility timeout expire).
     */
    @JsonIgnore
    public boolean hasUnknownEvents() {
        return auditEvents.stream().anyMatch(UnknownAuditEvent.class::isInstance);
    }

    /** For the repository: a copy with the stored row's {@code version}. */
    public PlanExecution withVersion(Long version) {
        return new PlanExecution(executionId, planInstr, scheduledAt, auditStatus,
                derivedPropOne, derivedPropTwo, auditEvents, version);
    }

    /** For {@link AuditEvent#derive}: a copy with {@code derivedPropOne} replaced. */
    public PlanExecution withDerivedPropOne(BigDecimal derivedPropOne) {
        return new PlanExecution(executionId, planInstr, scheduledAt, auditStatus,
                derivedPropOne, derivedPropTwo, auditEvents, version);
    }

    /** For {@link AuditEvent#derive}: a copy with {@code derivedPropTwo} replaced. */
    public PlanExecution withDerivedPropTwo(BigDecimal derivedPropTwo) {
        return new PlanExecution(executionId, planInstr, scheduledAt, auditStatus,
                derivedPropOne, derivedPropTwo, auditEvents, version);
    }

    private static BigDecimal numeric18x2(String name, BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal scaled = value.setScale(AMOUNT_SCALE, RoundingMode.HALF_EVEN);
        if (scaled.precision() > AMOUNT_PRECISION) {
            throw new IllegalArgumentException(
                    name + " does not fit numeric(" + AMOUNT_PRECISION + "," + AMOUNT_SCALE + "): " + value);
        }
        return scaled;
    }
}
