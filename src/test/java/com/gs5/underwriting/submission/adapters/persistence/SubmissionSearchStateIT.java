package com.gs5.underwriting.submission.adapters.persistence;

import com.gs5.underwriting.shared.kernel.ActorContext;
import com.gs5.underwriting.shared.kernel.ActorRole;
import com.gs5.underwriting.submission.domain.SearchSubmissionsQuery;
import com.gs5.underwriting.submission.domain.SearchSubmissionsUseCase;
import com.gs5.underwriting.submission.domain.SubmissionCommandRepository;
import com.gs5.underwriting.submission.domain.SubmissionQueryRepository;
import com.gs5.underwriting.submission.domain.SubmissionSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureDataSourceInitialization;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * Search shows each submission's state (BR-17, specification section 4, design section 19.9), read
 * against a real PostgreSQL, because the state is a left join on {@code submission_approvals}. A
 * fake cannot show whether that join is tenant-scoped.
 *
 * <p>The assertions read the response as JSON, not through the record's accessors, so the test
 * states the wire contract ({@code submissions[].state}) and compiles before the field exists (D8).
 *
 * <p>Approvals are inserted with SQL, not through the approval port, so this test does not depend on
 * a port signature that the fix is expected to change.
 */
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigureDataSourceInitialization
@Import({SubmissionQuerySqlAdapter.class, SubmissionCommandSqlAdapter.class})
@Testcontainers
class SubmissionSearchStateIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    private static final UUID TENANT_A = UUID.fromString("0a000000-0000-0000-0000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("0b000000-0000-0000-0000-00000000000b");
    private static final Instant REGISTERED_AT = Instant.parse("2026-11-01T10:15:30Z");
    private static final Instant APPROVED_AT = Instant.parse("2026-11-02T08:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private SubmissionCommandRepository commands;

    @Autowired
    private SubmissionQueryRepository queries;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("a search shows REGISTERED for an unapproved submission and APPROVED for an approved one (BR-17)")
    void aSearchShowsEachSubmissionsStateWithinTheCallersOwnOrganisation() {
        var approved = register(TENANT_A, "REF-APPROVED", REGISTERED_AT);
        register(TENANT_A, "REF-OPEN", REGISTERED_AT.plusSeconds(60));
        approve(approved, TENANT_A, "suw-01");

        var page = search(TENANT_A);

        assertThat(stateByReference(page))
                .containsOnly(entry("REF-APPROVED", "APPROVED"), entry("REF-OPEN", "REGISTERED"));
    }

    @Test
    @DisplayName("another organisation's approval never reaches the caller, even when the reference is the same (BR-17)")
    void anotherOrganisationsApprovalIsInvisibleEvenForTheSameReference() {
        var approvedInA = register(TENANT_A, "SHARED-REF", REGISTERED_AT);
        approve(approvedInA, TENANT_A, "suw-01");
        register(TENANT_B, "SHARED-REF", REGISTERED_AT);

        var page = search(TENANT_B);

        assertThat(page.path("totalMatchCount").asLong()).isEqualTo(1L);
        assertThat(stateByReference(page)).containsOnly(entry("SHARED-REF", "REGISTERED"));
    }

    @Test
    @DisplayName("guard: the total match count is the same whatever the state of each submission (BR-26)")
    void theTotalMatchCountDoesNotDependOnState() {
        var approved = register(TENANT_A, "REF-1", REGISTERED_AT);
        register(TENANT_A, "REF-2", REGISTERED_AT.plusSeconds(60));
        approve(approved, TENANT_A, "suw-01");

        var page = search(TENANT_A);

        assertThat(page.path("totalMatchCount").asLong()).isEqualTo(2L);
        assertThat(page.path("submissions")).hasSize(2);
    }

    // ------------------------------------------------------------------ helpers

    private UUID register(UUID tenantId, String submissionNumber, Instant registeredAt) {
        var id = UUID.randomUUID();
        commands.insert(new SubmissionSnapshot(id, tenantId, submissionNumber, new BigDecimal("100.00"),
                "uw-01", registeredAt, null));
        return id;
    }

    private void approve(UUID submissionId, UUID tenantId, String approvedBy) {
        jdbcTemplate.update("""
                INSERT INTO submission_approvals (submission_id, tenant_id, approved_by, approved_at, version)
                VALUES (?, ?, ?, ?, 0)
                """, submissionId, tenantId, approvedBy, Timestamp.from(APPROVED_AT));
    }

    private JsonNode search(UUID tenantId) {
        var actor = new ActorContext(tenantId, "viewer-01", ActorRole.VIEWER);
        var page = new SearchSubmissionsUseCase(queries).ask(actor,
                SearchSubmissionsQuery.parse(null, null, null, null, null, 0, 100));
        return JSON.valueToTree(page);
    }

    private static Map<String, String> stateByReference(JsonNode page) {
        var states = new LinkedHashMap<String, String>();
        for (JsonNode row : page.path("submissions")) {
            states.put(row.path("submissionNumber").asString(), row.path("state").asString());
        }
        return states;
    }
}
