-- Example rows for fix_attempts_scalar.sql, loaded by FixAttemptsScalarSqlTest before each test.
--
-- audit_events     what is stored before the script runs
-- expected_events  what the script must leave, compared as JSON;
--                  NULL = the script must not touch the row (audit_events stays byte for byte)

DROP TABLE IF EXISTS cash_sweep_audit;

CREATE TABLE cash_sweep_audit (
    id              BIGINT PRIMARY KEY,
    case_name       TEXT   NOT NULL UNIQUE,
    audit_events    TEXT,
    expected_events TEXT
);

-- rows the script must fix ------------------------------------------------

INSERT INTO cash_sweep_audit VALUES (1, 'legacy counter',
 '[{"__eventType__":"CHECK_ONE","at":"2026-11-01T10:00:00Z","status":"OK","attempts":3}]',
 '[{"__eventType__":"CHECK_ONE","at":"2026-11-01T10:00:00Z","status":"OK"}]');

INSERT INTO cash_sweep_audit VALUES (2, 'legacy counter zero',
 '[{"__eventType__":"CHECK_ONE","attempts":0,"status":"OK"}]',
 '[{"__eventType__":"CHECK_ONE","status":"OK"}]');

INSERT INTO cash_sweep_audit VALUES (3, 'attempts null',
 '[{"__eventType__":"CHECK_ONE","attempts":null,"status":"OK"}]',
 '[{"__eventType__":"CHECK_ONE","status":"OK"}]');

INSERT INTO cash_sweep_audit VALUES (4, 'attempts string',
 '[{"__eventType__":"CHECK_ONE","attempts":"3","status":"OK"}]',
 '[{"__eventType__":"CHECK_ONE","status":"OK"}]');

INSERT INTO cash_sweep_audit VALUES (5, 'attempts object',
 '[{"__eventType__":"CHECK_ONE","attempts":{"count":3},"status":"OK"}]',
 '[{"__eventType__":"CHECK_ONE","status":"OK"}]');

-- only the two legacy CHECK_ONE events change; the order of the five events is kept
INSERT INTO cash_sweep_audit VALUES (6, 'mixed events keep their order',
 '[{"__eventType__":"SCHEDULED","at":"2026-11-01T09:00:00Z"},
   {"__eventType__":"CHECK_ONE","at":"2026-11-01T10:00:00Z","attempts":2},
   {"__eventType__":"CHECK_TWO","at":"2026-11-01T10:01:00Z","attempts":5},
   {"__eventType__":"CHECK_ONE","at":"2026-11-01T10:02:00Z","attempts":[{"no":1,"error":"timeout"}]},
   {"__eventType__":"CHECK_ONE","at":"2026-11-01T10:03:00Z","attempts":1,"zeta":true,"alpha":[3,1,2]}]',
 '[{"__eventType__":"SCHEDULED","at":"2026-11-01T09:00:00Z"},
   {"__eventType__":"CHECK_ONE","at":"2026-11-01T10:00:00Z"},
   {"__eventType__":"CHECK_TWO","at":"2026-11-01T10:01:00Z","attempts":5},
   {"__eventType__":"CHECK_ONE","at":"2026-11-01T10:02:00Z","attempts":[{"no":1,"error":"timeout"}]},
   {"__eventType__":"CHECK_ONE","at":"2026-11-01T10:03:00Z","zeta":true,"alpha":[3,1,2]}]');

-- only the top-level "attempts" of the event is removed
INSERT INTO cash_sweep_audit VALUES (7, 'nested attempts stay',
 '[{"__eventType__":"CHECK_ONE","attempts":4,"details":{"attempts":4}}]',
 '[{"__eventType__":"CHECK_ONE","details":{"attempts":4}}]');

-- rows the script must not touch ------------------------------------------

-- odd spacing on purpose: an untouched row is not rewritten by jsonb
INSERT INTO cash_sweep_audit VALUES (10, 'already an array',
 '[ {"status" : "OK",   "__eventType__":"CHECK_ONE", "attempts":[ {"no":1}, {"no":2} ]} ]', NULL);

INSERT INTO cash_sweep_audit VALUES (11, 'empty attempts array',
 '[{"__eventType__":"CHECK_ONE","attempts":[]}]', NULL);

INSERT INTO cash_sweep_audit VALUES (12, 'no attempts',
 '[{"__eventType__":"CHECK_ONE","status":"OK"}]', NULL);

INSERT INTO cash_sweep_audit VALUES (13, 'other event type with a counter',
 '[{"__eventType__":"CHECK_TWO","attempts":3},{"__eventType__":"check_one","attempts":3}]', NULL);

INSERT INTO cash_sweep_audit VALUES (14, 'no event type',
 '[{"attempts":3}]', NULL);

INSERT INTO cash_sweep_audit VALUES (15, 'counter only in a nested object',
 '[{"__eventType__":"CHECK_ONE","details":{"attempts":3}}]', NULL);

INSERT INTO cash_sweep_audit VALUES (16, 'empty event list', '[]', NULL);

INSERT INTO cash_sweep_audit VALUES (17, 'null column', NULL, NULL);

INSERT INTO cash_sweep_audit VALUES (18, 'elements that are not objects',
 '[1, "CHECK_ONE", null, ["attempts"]]', NULL);

-- a single event object instead of an array: left for the variant at the end of the script
INSERT INTO cash_sweep_audit VALUES (19, 'single event object',
 '{"__eventType__":"CHECK_ONE","attempts":3}', NULL);
