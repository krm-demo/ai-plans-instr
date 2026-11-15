-- =====================================================================
-- File:    plans-exec/src/test/resources/manual-pg/fix_attempts_scalar.sql
-- Purpose: Repair legacy CHECK_ONE audit events in CASH_SWEEP_AUDIT.AUDIT_EVENTS.
--          "attempts" changed from a counter to a JSON array of attempts.
--          Legacy events whose "attempts" is not an array are fixed by
--          removing that property.
-- Assumes: AUDIT_EVENTS (text) holds a JSON array of event objects,
--          discriminated by the "__eventType__" property.
-- Usage:   by hand, in psql:
--            1) run the preview and review the rows,
--            2) run the update,
--            3) the verification must return 0,
--          then COMMIT (or ROLLBACK).
--          Run as one batch (as FixAttemptsScalarSqlTest does) it commits without
--          a pause; any error rolls the whole batch back.
-- Notes:   - If the table/column were created with quoted uppercase names,
--            use "CASH_SWEEP_AUDIT" / "AUDIT_EVENTS".
--          - Changed rows are normalized by jsonb (key order, whitespace,
--            duplicate keys). Unchanged rows are not touched.
--          - "attempts": null counts as non-array and is removed.
--          - One row with malformed JSON aborts the script; nothing is changed.
--          - Rows that hold a single event object (not an array) are skipped:
--            see the variant at the end.
--          - Take a backup of the rows from the preview before a production run.
-- Tested:  FixAttemptsScalarSqlTest, with the rows of data.sql in this folder.
-- =====================================================================

BEGIN;

-- 1. Preview: rows that will change
SELECT ctid, audit_events
FROM cash_sweep_audit
WHERE jsonb_typeof(audit_events::jsonb) = 'array'
  AND jsonb_path_exists(
        audit_events::jsonb,
        '$[*] ? (@."__eventType__" == "CHECK_ONE"
                 && exists(@.attempts)
                 && @.attempts.type() != "array")');

-- 2. Fix: drop non-array "attempts" from CHECK_ONE events
UPDATE cash_sweep_audit
SET audit_events = (
      SELECT jsonb_agg(
               CASE
                 WHEN e->>'__eventType__' = 'CHECK_ONE'
                  AND e ? 'attempts'
                  AND jsonb_typeof(e->'attempts') <> 'array'
                 THEN e - 'attempts'
                 ELSE e
               END
               ORDER BY ord
             )::text
      FROM jsonb_array_elements(audit_events::jsonb)
           WITH ORDINALITY AS t(e, ord)
    )
WHERE jsonb_typeof(audit_events::jsonb) = 'array'
  AND jsonb_path_exists(
        audit_events::jsonb,
        '$[*] ? (@."__eventType__" == "CHECK_ONE"
                 && exists(@.attempts)
                 && @.attempts.type() != "array")');

-- 3. Verify: must return 0
--    (same array guard as above: without it, lax "$[*]" also matches a single event object)
SELECT count(*)
FROM cash_sweep_audit
WHERE jsonb_typeof(audit_events::jsonb) = 'array'
  AND jsonb_path_exists(
        audit_events::jsonb,
        '$[*] ? (@."__eventType__" == "CHECK_ONE"
                 && exists(@.attempts)
                 && @.attempts.type() != "array")');

COMMIT; -- or ROLLBACK if the preview looks wrong

-- ---------------------------------------------------------------------
-- Variant: each row holds a SINGLE event object (not an array). Not covered by the test.
-- ---------------------------------------------------------------------
-- UPDATE cash_sweep_audit
-- SET audit_events = (audit_events::jsonb - 'attempts')::text
-- WHERE audit_events::jsonb->>'__eventType__' = 'CHECK_ONE'
--   AND audit_events::jsonb ? 'attempts'
--   AND jsonb_typeof(audit_events::jsonb->'attempts') <> 'array';
