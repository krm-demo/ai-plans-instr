-- plan_instructions takes the columns of the VersionedEntity envelope
-- (org.krmdemo.plansinstr.core.persistence.jdbc): id, version, last_updated_at,
-- last_updated_reason, payload.

ALTER TABLE plan_instructions RENAME COLUMN plan_id TO id;
ALTER TABLE plan_instructions RENAME COLUMN updated_at TO last_updated_at;

ALTER TABLE plan_instructions
    -- the old column held UTC without a zone
    ALTER COLUMN last_updated_at TYPE TIMESTAMPTZ USING last_updated_at AT TIME ZONE 'UTC',
    ALTER COLUMN version DROP DEFAULT,
    ALTER COLUMN version TYPE INTEGER,
    ADD COLUMN last_updated_reason TEXT,
    -- the creation time is the timestamp inside the UUIDv7 id
    DROP COLUMN created_at;

-- Versions now start at 1. The envelope fields live in the columns only, so their copies
-- leave the payload.
UPDATE plan_instructions
   SET version = version + 1,
       payload = payload - 'planId' - 'createdAt' - 'updatedAt' - 'version';

ALTER TABLE plan_instructions
    ADD CONSTRAINT chk_plan_instructions_version CHECK (version >= 1);
