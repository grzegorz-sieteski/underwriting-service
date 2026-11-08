package com.gs5.underwriting.submission.adapters.persistence;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Empties the two fact tables between integration tests.
 *
 * <p>Since V4 the fact tables are append-only (BR-44): a statement-level trigger refuses
 * {@code UPDATE}, {@code DELETE} and {@code TRUNCATE}. A test that needs a clean table therefore
 * has to switch the triggers off for the clean-up, and only the table owner can do that.
 *
 * <p>That this works at all is the point worth keeping visible: the service connects as the owner
 * (design section 21.3, review finding R-03, accepted while the service is not in production), so
 * anything holding the service's credentials can do what this helper does. The trigger stops every
 * ordinary statement; it does not stop an owner who means to remove it.
 */
final class FactTables {

    private FactTables() {
    }

    static void clear(JdbcTemplate jdbc) {
        jdbc.execute("ALTER TABLE submission_approvals DISABLE TRIGGER USER");
        jdbc.execute("ALTER TABLE submissions DISABLE TRIGGER USER");
        try {
            jdbc.execute("TRUNCATE TABLE submission_approvals, submissions");
        } finally {
            jdbc.execute("ALTER TABLE submissions ENABLE TRIGGER USER");
            jdbc.execute("ALTER TABLE submission_approvals ENABLE TRIGGER USER");
        }
    }
}
