package org.krmdemo.plansinstr.core.persistence.jdbc;

import org.krmdemo.plansinstr.core.persistence.UuidV7Generator;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The envelope around everything stored in Postgres: one row of a table whose columns are
 * {@code id, version, last_updated_at, last_updated_reason, payload}. What a message is for a
 * queue, this is for a table: the payload is the business object, the rest is the same for
 * every kind of payload.
 *
 * <p>The envelope fields live in the columns only. A payload that has fields of its own for
 * them (a plan's {@code planId} or {@code version}) is stored without those values and gets
 * them from the envelope when it is read.
 *
 * @param id                primary key, a UUIDv7: ordering by it is ordering by creation time
 * @param version           optimistic-locking version: 1 after the insert, plus 1 on every update
 * @param lastUpdatedAt     when the row was last written (millisecond precision)
 * @param lastUpdatedReason why it was last written, free text; may be {@code null}
 * @param payload           the stored object, a JSONB column
 * @param <T>               type of the payload
 */
public record VersionedEntity<T>(
        UUID id,
        int version,
        Instant lastUpdatedAt,
        String lastUpdatedReason,
        T payload) {

    public VersionedEntity {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(lastUpdatedAt, "lastUpdatedAt");
        Objects.requireNonNull(payload, "payload");
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1, got " + version);
        }
    }

    /** When the row was created: the timestamp inside the UUIDv7 {@code id}. There is no column for it. */
    public Instant createdAt() {
        if (id.version() != 7) {
            throw new IllegalStateException("id is not a UUIDv7: " + id);
        }
        return Instant.ofEpochMilli(UuidV7Generator.timestampMillis(id));
    }
}
