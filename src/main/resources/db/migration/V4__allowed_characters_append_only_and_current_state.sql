-- V4 (design section 21.3, revision 7, 2026-11-08). Three changes, each a business rule:
--   1. BR-29 rewritten: a submission number has no special characters.
--   2. BR-44: recorded facts are never changed or removed.
--   3. BR-45, BR-46, BR-47: the latest state of each submission is one database definition, and it
--      carries no internal identity.
-- The service is not in production, so no database has applied any other V2/V3 content (Q15).

-- 1. BR-29. Replaces the control-character CHECK of V2 with the allow list the domain applies:
-- ASCII letters, digits and the hyphen, nothing else. The column is COLLATE "C", so the ranges
-- are byte ranges and cannot widen under a locale. Emptiness stays with
-- ck_submissions_submission_number_not_empty (BR-04), although '+' would also refuse it.
ALTER TABLE submissions
    DROP CONSTRAINT ck_submissions_submission_number_no_control_chars;
ALTER TABLE submissions
    ADD CONSTRAINT ck_submissions_submission_number_allowed_chars
        CHECK (submission_number ~ '^[A-Za-z0-9-]+$');                                  -- BR-29

-- 2. BR-44. A statement-level trigger refuses every UPDATE, DELETE and TRUNCATE on the two fact
-- tables before a single row is touched. Statement level, so a statement that would match no row
-- is refused too: the service has no business issuing one at all. The error is
-- insufficient_privilege (42501), which is what it is.
--
-- Accepted limit (review finding R-03): the service connects as the owner of these tables, and an
-- owner can disable or drop a trigger. The trigger stops every ordinary statement; it does not
-- stop someone who holds the owner's credentials and means to remove it. A separate migration role
-- would close that, and is deferred while the service is not in production.
CREATE FUNCTION refuse_change_to_recorded_fact() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'recorded facts are append-only (BR-44): % on % refused', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'insufficient_privilege';
END
$$;

CREATE TRIGGER submissions_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON submissions
    FOR EACH STATEMENT EXECUTE FUNCTION refuse_change_to_recorded_fact();

CREATE TRIGGER submission_approvals_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON submission_approvals
    FOR EACH STATEMENT EXECUTE FUNCTION refuse_change_to_recorded_fact();

-- 3. BR-45, BR-46: one row per submission, in its most current state. A submission carries at
-- most one approval (primary key of submission_approvals), so the LEFT JOIN cannot duplicate a
-- row. The join matches on the tenant as well as the id, as the composite foreign key does.
-- BR-47: no internal id. Search reads this view only, and orders by registered_at and then by
-- submission_number, which is unique within a tenant and so makes the order total (BR-28).
-- The search order is now registered_at DESC, submission_number DESC. The V2 index ends in
-- id DESC, which no longer serves the tie-break, so the newest-first page would need a sort step.
-- This one covers the tenant predicate, the order and its tie-break exactly. The V2 index is
-- dropped rather than kept beside it: nothing orders by id any more.
CREATE INDEX idx_submissions_tenant_registered_at_number
    ON submissions (tenant_id, registered_at DESC, submission_number DESC);
DROP INDEX idx_submissions_tenant_registered_at;

CREATE VIEW submission_current AS
SELECT s.tenant_id,
       s.submission_number,
       s.insured_limit,
       s.registered_by,
       s.registered_at,
       (a.submission_id IS NOT NULL) AS approved
FROM submissions s
LEFT JOIN submission_approvals a
       ON a.submission_id = s.id
      AND a.tenant_id     = s.tenant_id;
