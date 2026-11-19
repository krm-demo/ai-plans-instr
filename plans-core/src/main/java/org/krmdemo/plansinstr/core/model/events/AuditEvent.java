package org.krmdemo.plansinstr.core.model.events;

import org.krmdemo.plansinstr.core.model.PlanExecution;
import org.krmdemo.plansinstr.core.model.enums.AuditStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.time.Instant;

/**
 * Result of one retriever. Each retriever produces exactly one event type, and each event
 * type comes from exactly one retriever.
 *
 * <p>The interface is open: implementations live next to their retrievers, outside this
 * library. Implementations should be records.
 *
 * <p>JSON: the type name is the {@code __eventType__} property and comes from the
 * implementation's {@link JsonTypeName} only. This library does not know the implementations,
 * so each one is registered with the mapper by the library that defines it, e.g.
 * {@code JsonMapper.builder().registerSubtypes(MyEvent.class)}. A type name the mapper does
 * not know is read as {@link UnknownAuditEvent}, which writes the original JSON back
 * unchanged. Never rename a {@link JsonTypeName} or a record component of an event that may
 * already be stored.
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = AuditEvent.TYPE_PROPERTY,
        defaultImpl = UnknownAuditEvent.class,
        visible = true)
@JsonPropertyOrder(AuditEvent.TYPE_PROPERTY)
public interface AuditEvent {

    String TYPE_PROPERTY = "__eventType__";

    /** When the retriever produced the event. */
    Instant at();

    /** The status this event reports; {@code null} only for {@link UnknownAuditEvent}. */
    AuditStatus status();

    /**
     * The type-specific part: returns {@code input} with the derived properties this event
     * writes, using {@code input}'s {@code with...} methods. Only the derived properties of the
     * result are kept; status and the event list are set by
     * {@link PlanExecution#apply(AuditEvent)}. Only called when the status
     * {@linkplain AuditStatus#carriesData() carries data}.
     */
    PlanExecution derive(PlanExecution input);

    /** {@code planExecOut = auditEvent.applyTo(planExecOut)}. */
    default PlanExecution applyTo(PlanExecution execution) {
        return execution.apply(this);
    }

    /** The JSON type name, taken from {@link JsonTypeName}. */
    @JsonProperty(value = TYPE_PROPERTY, access = JsonProperty.Access.READ_ONLY)
    default String eventType() {
        JsonTypeName name = getClass().getAnnotation(JsonTypeName.class);
        if (name == null) {
            throw new IllegalStateException(getClass().getName() + " needs @JsonTypeName");
        }
        return name.value();
    }

    @JsonIgnore
    default boolean isFailed() {
        return status() != null && status().isFailed();
    }
}
