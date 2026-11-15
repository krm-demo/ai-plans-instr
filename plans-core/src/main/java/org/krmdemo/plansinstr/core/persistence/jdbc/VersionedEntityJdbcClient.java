package org.krmdemo.plansinstr.core.persistence.jdbc;

import org.krmdemo.plansinstr.core.persistence.UuidV7Generator;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Reads and writes {@link VersionedEntity} rows of one table with Spring's {@link JdbcClient}.
 * Every store of this project is built on it, so all tables share the envelope columns:
 *
 * <pre>
 * id                  UUID        PRIMARY KEY,
 * version             INTEGER     NOT NULL CHECK (version &gt;= 1),
 * last_updated_at     TIMESTAMPTZ NOT NULL,
 * last_updated_reason TEXT,
 * payload             JSONB       NOT NULL
 * </pre>
 *
 * <p>A table may have more columns, for example copies of payload fields to index or poll on.
 * {@code insert} and {@code update} write them from the {@code extraColumns} map (column name to
 * value); an {@link Instant} value is written as a UTC timestamp.
 *
 * @param <T> type of the payload
 */
public final class VersionedEntityJdbcClient<T> {

    /** The envelope columns, in the order {@link #mapRow} reads them. */
    public static final String COLUMNS = "id, version, last_updated_at, last_updated_reason, payload";

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]*");

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;
    private final String table;
    private final Class<T> payloadType;
    private final UuidV7Generator ids = new UuidV7Generator();

    /**
     * @param json  mapper for the payload
     * @param clock source of {@code last_updated_at} and of the time inside new ids
     * @param table table name: lower case letters, digits and underscores
     */
    public VersionedEntityJdbcClient(JdbcClient jdbc, JsonMapper json, Clock clock, String table, Class<T> payloadType) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.table = checkedName(table);
        this.payloadType = payloadType;
    }

    /** A new time-ordered id (UUIDv7) for the current time of the clock. */
    public UUID nextId() {
        return ids.next(clock.millis());
    }

    /** Inserts the payload under a new id, as version 1. */
    public VersionedEntity<T> insert(T payload, String reason) {
        return insert(nextId(), payload, reason, Map.of());
    }

    /**
     * Inserts the payload under the given id, as version 1.
     *
     * @param extraColumns values of the table's own columns; an empty map when it has none
     */
    public VersionedEntity<T> insert(UUID id, T payload, String reason, Map<String, ?> extraColumns) {
        VersionedEntity<T> entity = new VersionedEntity<>(id, 1, now(), reason, payload);
        StringBuilder columns = new StringBuilder(COLUMNS);
        StringBuilder values = new StringBuilder(
                ":id, :version, :lastUpdatedAt, :lastUpdatedReason, CAST(:payload AS jsonb)");
        for (String column : extraColumns.keySet()) {
            columns.append(", ").append(checkedName(column));
            values.append(", :x_").append(column);
        }
        bind(jdbc.sql("INSERT INTO " + table + " (" + columns + ") VALUES (" + values + ")"), entity, extraColumns)
                .update();
        return entity;
    }

    /**
     * Replaces the payload of the row that is still at {@code expectedVersion}.
     *
     * @return the stored entity, at {@code expectedVersion + 1}
     * @throws OptimisticLockingFailureException when there is no row with this id at this version:
     *         it was changed by someone else, or it does not exist
     */
    public VersionedEntity<T> update(UUID id, int expectedVersion, T payload, String reason,
                                     Map<String, ?> extraColumns) {
        VersionedEntity<T> entity = new VersionedEntity<>(id, expectedVersion + 1, now(), reason, payload);
        StringBuilder set = new StringBuilder("version = :version, last_updated_at = :lastUpdatedAt, "
                + "last_updated_reason = :lastUpdatedReason, payload = CAST(:payload AS jsonb)");
        for (String column : extraColumns.keySet()) {
            set.append(", ").append(checkedName(column)).append(" = :x_").append(column);
        }
        int rows = bind(jdbc.sql("UPDATE " + table + " SET " + set
                        + " WHERE id = :id AND version = :expectedVersion"), entity, extraColumns)
                .param("expectedVersion", expectedVersion)
                .update();
        if (rows == 0) {
            throw new OptimisticLockingFailureException(
                    table + " " + id + " is not at version " + expectedVersion + " (changed concurrently, or missing)");
        }
        return entity;
    }

    public Optional<VersionedEntity<T>> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM " + table + " WHERE id = :id")
                .param("id", id)
                .query(this::mapRow)
                .optional();
    }

    /**
     * Selects entities with the caller's own conditions.
     *
     * @param clauses what follows {@code SELECT … FROM table}: {@code WHERE …}, {@code ORDER BY …},
     *                {@code LIMIT …}; values go in as named parameters, never as text
     */
    public List<VersionedEntity<T>> find(String clauses, Map<String, ?> params) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM " + table + " " + clauses)
                .params(params)
                .query(this::mapRow)
                .list();
    }

    /** @return false when there was no such row */
    public boolean deleteById(UUID id) {
        return jdbc.sql("DELETE FROM " + table + " WHERE id = :id").param("id", id).update() > 0;
    }

    /** Maps a row that has the {@link #COLUMNS}; for statements a store writes itself. */
    public VersionedEntity<T> mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new VersionedEntity<>(
                rs.getObject("id", UUID.class),
                rs.getInt("version"),
                rs.getObject("last_updated_at", OffsetDateTime.class).toInstant(),
                rs.getString("last_updated_reason"),
                json.readValue(rs.getString("payload"), payloadType));
    }

    /** The clock's time as it is stored: in milliseconds, like the time inside the ids. */
    public Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, VersionedEntity<T> entity,
                                          Map<String, ?> extraColumns) {
        JdbcClient.StatementSpec bound = statement
                .param("id", entity.id())
                .param("version", entity.version())
                .param("lastUpdatedAt", entity.lastUpdatedAt().atOffset(ZoneOffset.UTC))
                .param("lastUpdatedReason", entity.lastUpdatedReason())
                .param("payload", json.writeValueAsString(entity.payload()));
        for (Map.Entry<String, ?> column : extraColumns.entrySet()) {
            Object value = column.getValue();
            bound = bound.param("x_" + column.getKey(),
                    value instanceof Instant instant ? instant.atOffset(ZoneOffset.UTC) : value);
        }
        return bound;
    }

    /** Table and column names are put into the SQL text, so only plain identifiers are accepted. */
    private static String checkedName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("not a plain lower-case SQL identifier: " + name);
        }
        return name;
    }
}
