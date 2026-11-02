package com.example.service.submission;

import com.example.service.shared.kernel.ActorContext;
import com.example.service.shared.kernel.ActorRole;
import com.example.service.submission.domain.RegisterSubmissionCommand;
import com.example.service.submission.query.SearchSubmissionsQuery;
import com.example.service.submission.query.SubmissionSearchCriteria;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C-1 / SEC-03 / SEC-04 / SEC-02: every record that prints a caller-supplied reference must
 * print it escaped, and the record that holds the actor id first must not print it at all.
 *
 * <p>The escape had been applied at exactly one of the records that render {@code externalRef}.
 * {@code RegisterSubmissionCommand} is the worst of the misses: a reference BR-29 refuses is
 * guaranteed to pass through it, on the refusal path. A search filter is validated by nothing,
 * since BR-29 governs registration only.
 */
class ReferenceLogInjectionTest {

    /** A reference that, printed raw, forges what reads as a second log entry. */
    private static final String HOSTILE = "S-1\n2026-11-02 INFO admin login ok";
    private static final String ESCAPED_NEWLINE = "\\u000A";

    @Test
    @DisplayName("the register command prints a hostile reference escaped, never raw")
    void register_command_escapes_the_reference() {
        var printed = new RegisterSubmissionCommand(HOSTILE, new BigDecimal("1.00")).toString();

        assertThat(printed).doesNotContain("\n").contains(ESCAPED_NEWLINE);
    }

    @Test
    @DisplayName("the search query prints a hostile reference filter escaped, never raw")
    void search_query_escapes_the_reference() {
        var printed = new SearchSubmissionsQuery(HOSTILE, null, null, null, null, 0, 10)
                .toString();

        assertThat(printed).doesNotContain("\n").contains(ESCAPED_NEWLINE);
    }

    @Test
    @DisplayName("the search criteria prints a hostile reference filter escaped, never raw "
            + "(already fixed by QA-01; pinned so it stays fixed)")
    void search_criteria_escapes_the_reference() {
        var printed = new SubmissionSearchCriteria(UUID.randomUUID(), HOSTILE,
                null, null, null, null).toString();

        assertThat(printed).doesNotContain("\n").contains(ESCAPED_NEWLINE);
    }

    @Test
    @DisplayName("SEC-02: ActorContext does not print the actor id — it is the first and "
            + "upstream holder of it, so masking everything downstream was not enough (OPEN-01)")
    void actor_context_masks_the_actor_id() {
        var actor = new ActorContext(UUID.randomUUID(), "uw-secret-identity",
                ActorRole.UNDERWRITER);

        assertThat(actor.toString()).doesNotContain("uw-secret-identity");
        assertThat(actor.actorId()).isEqualTo("uw-secret-identity");
    }
}
