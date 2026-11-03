CREATE TABLE submission_approvals
(
    submission_id UUID PRIMARY KEY,
    tenant_id     UUID                    NOT NULL,
    approved_by   VARCHAR(255)            NOT NULL,
    approved_at   TIMESTAMPTZ             NOT NULL,
    version       BIGINT                  NOT NULL,

    CONSTRAINT fk_submission_approvals_submission
        FOREIGN KEY (submission_id) REFERENCES submissions (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX idx_submission_approvals_tenant_submission
    ON submission_approvals (tenant_id, submission_id);
