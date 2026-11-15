package org.krmdemo.plansinstr.exec;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Runs the manual repair script {@code manual-pg/fix_attempts_scalar.sql} against a real
 * Postgres started in-process by Zonky (no Docker), with the example rows of
 * {@code manual-pg/data.sql}.
 *
 * <p>Each row of {@code data.sql} carries its own expectation in {@code expected_events}:
 * the JSON the script must leave, or {@code NULL} when the script must not touch the row.
 *
 * <p>The script is sent as one batch, the way a manual run from a test or a tool sends it.
 * Plain JDBC on purpose: the script uses the jsonb {@code ?} operator, which a
 * parameterized statement would take for a placeholder.
 */
class FixAttemptsScalarSqlTest {

    private static final String SCRIPT = "/manual-pg/fix_attempts_scalar.sql";
    private static final String DATA = "/manual-pg/data.sql";

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void startPostgres() throws IOException {
        postgres = EmbeddedPostgres.start();
        dataSource = postgres.getPostgresDatabase();
    }

    @AfterAll
    static void stopPostgres() throws IOException {
        if (postgres != null) {
            postgres.close();
        }
    }

    /** {@code data.sql} drops and recreates the table, so every test starts from the same rows. */
    @BeforeEach
    void loadExamples() throws Exception {
        runBatch(resource(DATA));
    }

    @Test
    @DisplayName("the script fixes exactly the legacy rows; preview, update count and verification agree")
    void fixesLegacyRows() throws Exception {
        Map<Long, String> before = auditEventsById();
        List<String> toFix = strings(
                "SELECT audit_events FROM cash_sweep_audit WHERE expected_events IS NOT NULL ORDER BY id");
        assertFalse(toFix.isEmpty(), "data.sql has rows to fix");

        BatchResult run = runBatch(resource(SCRIPT));

        assertEquals(2, run.resultSets().size(), "preview and verification");
        assertEquals(toFix.stream().sorted().toList(),
                run.resultSets().get(0).stream().map(row -> row.get(1)).sorted().toList(),
                "preview lists the rows to fix");
        assertEquals(toFix.size(), run.updatedRows(), "updated rows");
        assertEquals(List.of(List.of("0")), run.resultSets().get(1), "verification");

        assertEquals(List.of(), strings("""
                SELECT case_name FROM cash_sweep_audit
                WHERE expected_events IS NOT NULL
                  AND audit_events::jsonb IS DISTINCT FROM expected_events::jsonb
                ORDER BY id"""), "fixed rows that differ from expected_events");

        Map<Long, String> after = auditEventsById();
        for (long id : longs("SELECT id FROM cash_sweep_audit WHERE expected_events IS NULL ORDER BY id")) {
            assertEquals(before.get(id), after.get(id), "row " + id + " must stay byte for byte");
        }
    }

    @Test
    @DisplayName("events keep their order and only the legacy CHECK_ONE events lose \"attempts\"")
    void keepsEventOrder() throws Exception {
        runBatch(resource(SCRIPT));

        List<String> events = strings("""
                SELECT (e->>'__eventType__') || ':' || coalesce(jsonb_typeof(e->'attempts'), 'none')
                FROM cash_sweep_audit,
                     jsonb_array_elements(audit_events::jsonb) WITH ORDINALITY AS t(e, ord)
                WHERE case_name = 'mixed events keep their order'
                ORDER BY ord""");

        assertEquals(List.of("SCHEDULED:none", "CHECK_ONE:none", "CHECK_TWO:number", "CHECK_ONE:array",
                "CHECK_ONE:none"), events);
    }

    @Test
    @DisplayName("a second run finds nothing and changes nothing")
    void idempotent() throws Exception {
        runBatch(resource(SCRIPT));
        Map<Long, String> afterFirst = auditEventsById();

        BatchResult second = runBatch(resource(SCRIPT));

        assertEquals(List.of(), second.resultSets().get(0), "preview");
        assertEquals(0, second.updatedRows());
        assertEquals(afterFirst, auditEventsById());
    }

    @Test
    @DisplayName("one row with malformed JSON aborts the script and nothing is changed")
    void malformedJsonAbortsEverything() throws Exception {
        runBatch("INSERT INTO cash_sweep_audit VALUES (99, 'malformed', '[{\"__eventType__\":', NULL)");
        Map<Long, String> before = auditEventsById();

        assertThrows(SQLException.class, () -> runBatch(resource(SCRIPT)));

        assertEquals(before, auditEventsById());
    }

    // ---------------------------------------------------------------- helpers

    /** What a batch returned: every result set as rows of strings, and the sum of the update counts. */
    private record BatchResult(List<List<List<String>>> resultSets, int updatedRows) {
    }

    /**
     * Sends {@code sql} (one or more statements) in one {@code Statement.execute} call. A failed
     * batch leaves its transaction open; closing the connection rolls it back.
     */
    private static BatchResult runBatch(String sql) throws SQLException {
        List<List<List<String>>> resultSets = new ArrayList<>();
        int updatedRows = 0;
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.setEscapeProcessing(false);   // no JDBC {escapes}: the text is plain Postgres SQL
            boolean isResultSet = statement.execute(sql);
            while (true) {
                if (isResultSet) {
                    try (ResultSet rs = statement.getResultSet()) {
                        resultSets.add(rows(rs));
                    }
                } else {
                    int count = statement.getUpdateCount();
                    if (count == -1) {
                        break;   // no more results
                    }
                    updatedRows += count;   // BEGIN and COMMIT report 0
                }
                isResultSet = statement.getMoreResults();
            }
        }
        return new BatchResult(resultSets, updatedRows);
    }

    private static List<List<String>> rows(ResultSet rs) throws SQLException {
        int columns = rs.getMetaData().getColumnCount();
        List<List<String>> rows = new ArrayList<>();
        while (rs.next()) {
            List<String> row = new ArrayList<>();
            for (int i = 1; i <= columns; i++) {
                row.add(rs.getString(i));
            }
            rows.add(row);
        }
        return rows;
    }

    /** First column of a query. */
    private static List<String> strings(String query) throws SQLException {
        return runBatch(query).resultSets().get(0).stream().map(row -> row.get(0)).toList();
    }

    private static List<Long> longs(String query) throws SQLException {
        return strings(query).stream().map(Long::valueOf).toList();
    }

    /** {@code audit_events} of every row; a null column is kept as null. */
    private static Map<Long, String> auditEventsById() throws SQLException {
        Map<Long, String> byId = new LinkedHashMap<>();
        for (List<String> row : runBatch("SELECT id, audit_events FROM cash_sweep_audit ORDER BY id")
                .resultSets().get(0)) {
            byId.put(Long.valueOf(row.get(0)), row.get(1));
        }
        return byId;
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = FixAttemptsScalarSqlTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "test resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
