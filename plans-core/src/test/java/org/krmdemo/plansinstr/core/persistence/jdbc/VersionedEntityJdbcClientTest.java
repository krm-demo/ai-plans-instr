package org.krmdemo.plansinstr.core.persistence.jdbc;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The envelope against a real Postgres started in-process by Zonky (no Docker). The table
 * {@code note} has the envelope columns and two columns of its own.
 */
class VersionedEntityJdbcClientTest {

    /** Any payload will do: the envelope does not look inside. */
    public record Note(String title, List<String> tags) {
    }

    private static final Instant T0 = Instant.parse("2026-11-15T10:00:00Z");

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    private JdbcClient jdbc;
    private MutableClock clock;
    private VersionedEntityJdbcClient<Note> notes;

    @BeforeAll
    static void startPostgres() throws IOException {
        postgres = EmbeddedPostgres.start();
        dataSource = postgres.getPostgresDatabase();
        JdbcClient.create(dataSource).sql("""
                CREATE TABLE note (
                    id                  UUID        PRIMARY KEY,
                    version             INTEGER     NOT NULL CHECK (version >= 1),
                    last_updated_at     TIMESTAMPTZ NOT NULL,
                    last_updated_reason TEXT,
                    payload             JSONB       NOT NULL,
                    pinned              BOOLEAN     NOT NULL DEFAULT FALSE,
                    due_at              TIMESTAMPTZ
                )""").update();
    }

    @AfterAll
    static void stopPostgres() throws IOException {
        if (postgres != null) {
            postgres.close();
        }
    }

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE note").update();
        clock = new MutableClock(T0);
        notes = new VersionedEntityJdbcClient<>(jdbc, JsonMapper.builder().build(), clock, "note", Note.class);
    }

    @Test
    @DisplayName("insert: new UUIDv7 id, version 1, the clock's time, the reason")
    void insert() {
        VersionedEntity<Note> stored = notes.insert(new Note("first", List.of("a", "b")), "created");

        assertEquals(7, stored.id().version());
        assertEquals(1, stored.version());
        assertEquals(T0, stored.lastUpdatedAt());
        assertEquals(T0, stored.createdAt());
        assertEquals("created", stored.lastUpdatedReason());
        assertEquals(new Note("first", List.of("a", "b")), stored.payload());
        assertEquals(Optional.of(stored), notes.findById(stored.id()));
    }

    @Test
    @DisplayName("update: version + 1, new time and reason; the creation time stays")
    void update() {
        VersionedEntity<Note> v1 = notes.insert(new Note("first", List.of()), "created");
        clock.advance(Duration.ofMinutes(5));

        VersionedEntity<Note> v2 = notes.update(v1.id(), v1.version(), new Note("renamed", List.of("x")), "renamed",
                Map.of());

        assertEquals(2, v2.version());
        assertEquals(T0.plus(Duration.ofMinutes(5)), v2.lastUpdatedAt());
        assertEquals(T0, v2.createdAt());
        assertEquals("renamed", v2.lastUpdatedReason());
        assertEquals(Optional.of(v2), notes.findById(v1.id()));
    }

    @Test
    @DisplayName("update with an old version, or of a missing row, fails and changes nothing")
    void optimisticLocking() {
        VersionedEntity<Note> v1 = notes.insert(new Note("first", List.of()), "created");
        VersionedEntity<Note> v2 = notes.update(v1.id(), 1, new Note("second", List.of()), "edit", Map.of());

        assertThrows(OptimisticLockingFailureException.class,
                () -> notes.update(v1.id(), 1, new Note("stale", List.of()), "stale edit", Map.of()));
        assertThrows(OptimisticLockingFailureException.class,
                () -> notes.update(notes.nextId(), 1, new Note("nobody", List.of()), "missing", Map.of()));

        assertEquals(Optional.of(v2), notes.findById(v1.id()));
        assertEquals(1, jdbc.sql("SELECT count(*) FROM note").query(Integer.class).single());
    }

    @Test
    @DisplayName("extra columns are written with the envelope; a reason is optional")
    void extraColumns() {
        Map<String, Object> columns = new HashMap<>();
        columns.put("pinned", true);
        columns.put("due_at", T0.plus(Duration.ofDays(1)));
        VersionedEntity<Note> v1 = notes.insert(notes.nextId(), new Note("with columns", List.of()), null, columns);

        assertNull(v1.lastUpdatedReason());
        Map<String, Object> row = jdbc.sql("SELECT pinned, due_at, last_updated_reason FROM note").query().singleRow();
        assertEquals(true, row.get("pinned"));
        assertEquals(T0.plus(Duration.ofDays(1)), ((Timestamp) row.get("due_at")).toInstant());
        assertNull(row.get("last_updated_reason"));

        columns.put("pinned", false);
        columns.put("due_at", null);
        notes.update(v1.id(), 1, v1.payload(), "unpinned", columns);

        row = jdbc.sql("SELECT pinned, due_at, last_updated_reason FROM note").query().singleRow();
        assertEquals(false, row.get("pinned"));
        assertNull(row.get("due_at"));
        assertEquals("unpinned", row.get("last_updated_reason"));
    }

    @Test
    @DisplayName("find: the caller's conditions; ordering by id is ordering by creation time")
    void find() {
        for (String title : List.of("one", "two", "three")) {
            notes.insert(new Note(title, List.of("t-" + title)), "created");
            clock.advance(Duration.ofSeconds(1));
        }

        List<VersionedEntity<Note>> newestTwo = notes.find("ORDER BY id DESC LIMIT :n", Map.of("n", 2));
        assertEquals(List.of("three", "two"), newestTwo.stream().map(e -> e.payload().title()).toList());

        List<VersionedEntity<Note>> byTitle = notes.find("WHERE payload ->> 'title' = :title", Map.of("title", "one"));
        assertEquals(List.of(T0), byTitle.stream().map(VersionedEntity::createdAt).toList());
    }

    @Test
    @DisplayName("deleteById tells whether there was a row")
    void delete() {
        VersionedEntity<Note> stored = notes.insert(new Note("temp", List.of()), "created");

        assertTrue(notes.deleteById(stored.id()));
        assertEquals(Optional.empty(), notes.findById(stored.id()));
        assertFalse(notes.deleteById(stored.id()));
    }

    @Test
    @DisplayName("table and column names must be plain identifiers")
    void namesAreChecked() {
        JsonMapper json = JsonMapper.builder().build();
        assertThrows(IllegalArgumentException.class,
                () -> new VersionedEntityJdbcClient<>(jdbc, json, clock, "note; DROP TABLE note", Note.class));
        assertThrows(IllegalArgumentException.class,
                () -> notes.insert(notes.nextId(), new Note("x", List.of()), "created", Map.of("pinned = true --", true)));
        assertEquals(0, jdbc.sql("SELECT count(*) FROM note").query(Integer.class).single());
    }

    @Test
    @DisplayName("the envelope itself: version from 1, creation time only from a UUIDv7")
    void envelopeRules() {
        UUID id = notes.nextId();
        assertThrows(IllegalArgumentException.class, () -> new VersionedEntity<>(id, 0, T0, null, "payload"));
        assertThrows(NullPointerException.class, () -> new VersionedEntity<>(id, 1, T0, null, null));
        assertThrows(IllegalStateException.class,
                () -> new VersionedEntity<>(UUID.randomUUID(), 1, T0, null, "payload").createdAt());
    }

    /** A clock that the test moves by hand. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
