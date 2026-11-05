-- V3: approval. Edited in place only because V3 was never applied to a kept database.
-- Otherwise this would be V4 (design section 19.8).
ALTER TABLE submissions
    ADD CONSTRAINT uq_submissions_id_tenant UNIQUE (id, tenant_id);   -- a foreign-key target must be unique

CREATE TABLE submission_approvals
(
    submission_id UUID PRIMARY KEY,                       -- one approval per submission (BR-36, BR-39)
    tenant_id     UUID         NOT NULL,
    approved_by   VARCHAR(255) NOT NULL,                  -- an ActorContext.actorId: at most 255 code points, no Cc
    approved_at   TIMESTAMPTZ  NOT NULL,                  -- the clock, never the caller (BR-37)
    version       BIGINT       NOT NULL,                  -- as in V1 and V2: the mapper must not drop it

    CONSTRAINT fk_submission_approvals_submission         -- the approval's tenant IS the submission's tenant
        FOREIGN KEY (submission_id, tenant_id)
        REFERENCES submissions (id, tenant_id)
);
