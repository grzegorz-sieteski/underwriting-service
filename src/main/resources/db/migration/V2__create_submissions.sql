-- Submissions. One row per registered submission; immutable once written, because the spec
-- puts amendment, withdrawal and every lifecycle state beyond "registered" out of scope.
-- There is deliberately no status column: inventing one would invent a business concept.

CREATE TABLE submissions
(
    id            UUID PRIMARY KEY,
    tenant_id     UUID                    NOT NULL,
    -- COLLATE "C": BR-07 compares references exactly as supplied, including letter case and
    -- surrounding blank space. A database created with a case-insensitive ICU collation would
    -- otherwise make both this column's equality test and the unique index below case-folding,
    -- silently breaking BR-06 and BR-07. VARCHAR(64) counts characters, which is exactly the
    -- code-point measure the domain applies for BR-05, so an accepted reference always fits.
    external_ref  VARCHAR(64) COLLATE "C" NOT NULL,
    -- Unconstrained NUMERIC on purpose. BR-13 states the insured limit has no upper bound and
    -- that none may be introduced without a business decision: NUMERIC(19, 2) would quietly
    -- impose one. Scale is enforced by the CHECK below rather than by the column type, because
    -- NUMERIC(p, 2) would ROUND 1200.005 to 1200.01 instead of letting BR-12 refuse it.
    insured_limit NUMERIC                 NOT NULL,
    registered_by VARCHAR(255)            NOT NULL,
    registered_at TIMESTAMPTZ             NOT NULL,
    -- Optimistic lock. NOT NULL with no DEFAULT, exactly as in V1: Spring Data JDBC always
    -- supplies the value, and a DEFAULT would mask a mapper that forgot to carry it.
    version       BIGINT                  NOT NULL,

    -- BR-06 + BR-08: unique PER TENANT, so the same reference in another organisation is fine.
    -- This is also what settles the BR-09 race: the loser's insert conflicts.
    CONSTRAINT uq_submissions_tenant_external_ref UNIQUE (tenant_id, external_ref),

    -- Defence in depth for the domain invariants. Each one mirrors a business rule that is
    -- already refused in the aggregate; a row that violates one of them is a bug, not a refusal.
    CONSTRAINT ck_submissions_external_ref_not_empty CHECK (length(external_ref) > 0),          -- BR-04
    -- BR-29. Mirrors the domain check, and cannot fully mirror it: see ADR §8.3.
    CONSTRAINT ck_submissions_external_ref_no_control_chars
        CHECK (external_ref !~ '[\x01-\x1F\x7F-\x9F]'),                                         -- BR-29
    CONSTRAINT ck_submissions_insured_limit_positive CHECK (insured_limit > 0),                 -- BR-11
    CONSTRAINT ck_submissions_insured_limit_scale CHECK (scale(insured_limit) = 2)              -- BR-12
);

-- Search workhorse. Covers the tenant predicate that EVERY search carries (BR-17), the
-- registration-moment range, and the newest-first order (BR-24) with no sort step; the trailing
-- id makes that order total, which is what BR-28 needs.
CREATE INDEX idx_submissions_tenant_registered_at
    ON submissions (tenant_id, registered_at DESC, id DESC);

-- Insured limit range filter within a tenant.
CREATE INDEX idx_submissions_tenant_insured_limit
    ON submissions (tenant_id, insured_limit);

-- No index for the reference filter: uq_submissions_tenant_external_ref already builds a
-- btree on (tenant_id, external_ref), which serves both the uniqueness check and the
-- exact-reference search. A second index on the same columns would only cost write throughput.
