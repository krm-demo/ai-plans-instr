package com.example.plans.execution;

import com.fasterxml.jackson.annotation.JsonCreator;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.jsontype.TypeSerializer;
import tools.jackson.databind.ser.std.StdSerializer;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An event whose {@code __eventType__} is not registered with the mapper: written by a newer
 * build, or by a library this service does not have on its classpath. It keeps the original
 * JSON object (field order, nulls and nested values included) and writes it back unchanged,
 * so saving the execution does not lose it.
 *
 * <p>It does not change the status or the derived properties. A worker that finds one should
 * leave the execution to a worker that knows it: see {@link PlanExecution#hasUnknownEvents()}.
 *
 * <p>Written by {@link Serializer}, not {@code @JsonValue}: Jackson 3 wraps a polymorphic
 * {@code @JsonValue} in a type array ({@code ["UnknownAuditEvent", {...}]}).
 *
 * @param raw the original JSON object, including {@code __eventType__}
 */
@JsonSerialize(using = UnknownAuditEvent.Serializer.class)
public record UnknownAuditEvent(Map<String, Object> raw) implements AuditEvent {

    public UnknownAuditEvent {
        // LinkedHashMap rather than Map.copyOf: keeps field order and allows null values
        raw = Collections.unmodifiableMap(new LinkedHashMap<>(raw));
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static UnknownAuditEvent fromJson(Map<String, Object> raw) {
        return new UnknownAuditEvent(raw);
    }

    /** Writes {@link #raw()} as it is; it already holds {@code __eventType__}, so no type id is added. */
    public static final class Serializer extends StdSerializer<UnknownAuditEvent> {

        public Serializer() {
            super(UnknownAuditEvent.class);
        }

        @Override
        public void serialize(UnknownAuditEvent value, JsonGenerator gen, SerializationContext ctxt) {
            gen.writePOJO(value.raw());
        }

        @Override
        public void serializeWithType(UnknownAuditEvent value, JsonGenerator gen, SerializationContext ctxt,
                                      TypeSerializer typeSer) {
            serialize(value, gen, ctxt);
        }
    }

    @Override
    public String eventType() {
        return String.valueOf(raw.get(TYPE_PROPERTY));
    }

    /** Not interpreted; {@code null}. */
    @Override
    public Instant at() {
        return null;
    }

    /** Unknown to this build; {@code null}, so the status is left unchanged. */
    @Override
    public AuditStatus status() {
        return null;
    }

    @Override
    public PlanExecution derive(PlanExecution input) {
        return input;
    }
}
