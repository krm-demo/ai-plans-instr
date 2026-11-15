package com.example.plans.execution;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.time.Instant;

/**
 * Result of one retriever. Each retriever produces exactly one event type, and each event
 * type comes from exactly one retriever.
 *
 * <p>The interface is sealed, so a {@code switch} over an {@code AuditEvent} without a
 * {@code default} branch stops compiling when a new event type is added and not handled.
 *
 * <p>JSON: the type name is the {@code __eventType__} property and comes from the record's
 * {@link JsonTypeName} only (one source of truth). A type name this build does not know is
 * read as {@link UnknownAuditEvent}, which writes the original JSON back unchanged.
 * To add an event type: add the record, put it in {@code permits} and in {@link JsonSubTypes},
 * and give it a {@link JsonTypeName} that is never renamed afterwards.
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = AuditEvent.TYPE_PROPERTY,
        defaultImpl = UnknownAuditEvent.class,
        visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(DdaBalanceEvent.class),
        @JsonSubTypes.Type(InvPositionsEvent.class),
        @JsonSubTypes.Type(SweepOrderEvent.class)
})
@JsonPropertyOrder(AuditEvent.TYPE_PROPERTY)
public sealed interface AuditEvent
        permits DdaBalanceEvent, InvPositionsEvent, SweepOrderEvent, UnknownAuditEvent {

    String TYPE_PROPERTY = "__eventType__";

    /** When the retriever produced the event. */
    Instant at();

    /** The status this event reports; {@code null} only for {@link UnknownAuditEvent}. */
    AuditStatus status();

    /**
     * The type-specific part: the derived properties after this event, computed from the
     * execution the event is applied to. Only called for events whose status
     * {@linkplain AuditStatus#carriesData() carries data}. Status and the event list are
     * handled by {@link PlanExecution#apply(AuditEvent)}, not here.
     */
    PlanExecution.Derived derive(PlanExecution input);

    /** {@code planExecOut = auditEvent.applyTo(planExecOut)}. */
    default PlanExecution applyTo(PlanExecution execution) {
        return execution.apply(this);
    }

    /** The JSON type name, taken from {@link JsonTypeName}. */
    @JsonProperty(value = TYPE_PROPERTY, access = JsonProperty.Access.READ_ONLY)
    default String eventType() {
        return getClass().getAnnotation(JsonTypeName.class).value();
    }

    @JsonIgnore
    default boolean isFailure() {
        return status() != null && status().isFailure();
    }
}
