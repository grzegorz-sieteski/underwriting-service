package com.gs5.underwriting.submission.adapters.persistence;

import com.gs5.underwriting.submission.domain.RegisterSubmissionError;
import com.gs5.underwriting.submission.domain.SubmissionId;
import com.gs5.underwriting.submission.domain.SubmissionRoundTrip;
import com.gs5.underwriting.submission.domain.SubmissionSnapshot;
import com.gs5.underwriting.shared.kernel.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureDataSourceInitialization;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The write adapter against a real PostgreSQL.
 *
 * <p>Three things here cannot be tested against an in-memory fake, which is the whole reason this
 * file exists:
 *
 * <ul>
 *   <li><strong>The {@code version} trap.</strong> The submission id is a UUID generated in the
 *       domain, so Spring Data JDBC's "is this entity new?" test cannot use it. The column is
 *       {@code NOT NULL} with no {@code DEFAULT}, so a mapper that dropped {@code version} fails
 *       loudly here and nowhere else.</li>
 *   <li><strong>{@code UNIQUE (tenant_id, submission_number)} and {@code ON CONFLICT DO NOTHING}.</strong>
 *       BR-06, BR-08 and BR-09 are settled by the database, and a fake only imitates that.</li>
 *   <li><strong>The snapshot round trip.</strong> Insert, read the row back through the mapper,
 *       reconstitute it, and assert nothing was lost in either direction.</li>
 * </ul>
 *
 * <p>{@code postgres:17-alpine} is required, not incidental: the {@code scale(numeric)} CHECK in
 * {@code V2__create_submissions.sql} needs PostgreSQL 13 or newer.
 *
 * <p>Three annotations here are load-bearing and none is boilerplate:
 *
 * <ul>
 *   <li><strong>{@code @AutoConfigureDataSourceInitialization}.</strong> The {@code @DataJdbcTest}
 *       slice does <em>not</em> pull in Flyway — its auto-configuration imports are only
 *       {@code DataJdbcRepositoriesAutoConfiguration} and {@code DataSourceAutoConfiguration}.
 *       Without this annotation the container comes up with an empty schema and every test fails
 *       with "relation \\"submissions\\" does not exist". This is what runs V1 and V2.</li>
 *   <li><strong>{@code @Import(SubmissionCommandSqlAdapter.class)}.</strong> The adapter is a
 *       {@code @Component}, and a slice test's component scan is filtered down to Spring Data
 *       repositories, so it would not otherwise be a bean. Importing it by class also works
 *       because it is package-private and this test shares its package — which is the same reason
 *       the test can call {@code findOwn}.</li>
 *   <li><strong>{@code @ServiceConnection}.</strong> Wires the container to Spring with no
 *       {@code @DynamicPropertySource}.</li>
 * </ul>
 *
 * <p>{@code @DataJdbcTest} is meta-annotated {@code @Transactional}, so each test rolls back and
 * the row counts below start from zero. Flyway runs once at context startup, outside those
 * transactions, so the schema survives.
 */
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigureDataSourceInitialization
@Import(SubmissionCommandSqlAdapter.class)
@Testcontainers
class SubmissionCommandSqlAdapterIT {

    /**
     * Note the package: {@code org.testcontainers.postgresql.PostgreSQLContainer}.
     *
     * <p>Testcontainers 2.x moved it and <strong>deprecated</strong> the old
     * {@code org.testcontainers.containers.PostgreSQLContainer}, which is still what
     * {@code .github/instructions/testing-tdd.instructions.md} shows. The new class is not
     * generic, so there is no {@code <?>} either. It does still extend
     * {@code org.testcontainers.containers.JdbcDatabaseContainer}, which is the type Boot's
     * {@code JdbcContainerConnectionDetailsFactory} binds to — so {@code @ServiceConnection}
     * keeps working across the move.
     */
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    private static final UUID TENANT_A = UUID.fromString("0a000000-0000-0000-0000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("0b000000-0000-0000-0000-00000000000b");

    /** Whole seconds on purpose: {@code TIMESTAMPTZ} keeps microseconds, {@code Instant} keeps
     * nanoseconds, so {@code Instant.now()} would not survive a round trip unchanged. */
    private static final Instant REGISTERED_AT = Instant.parse("2026-11-01T10:15:30Z");

    @Autowired
    private SubmissionCommandSqlAdapter adapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static SubmissionSnapshot newSnapshot(UUID id,
                                                  UUID tenantId,
                                                  String submissionNumber,
                                                  String insuredLimit) {
        // version is null: that null is what says "this aggregate has never been stored".
        return new SubmissionSnapshot(id, tenantId, submissionNumber, new BigDecimal(insuredLimit),
                "uw-01", REGISTERED_AT, null);
    }

    private Long versionOf(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM submissions WHERE id = ?", Long.class, id);
    }

    private int rowCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM submissions", Integer.class);
    }

    @Nested
    @DisplayName("Inserting a brand-new aggregate whose id was generated in the domain")
    class InsertingANewAggregate {

        @Test
        @DisplayName("SC-82: the row is inserted and its version is NOT NULL afterwards, which is the "
                + "trap the @Version @Nullable Long design exists for")
        void shouldInsertRowAndSetVersionWhenAggregateIsNew() {
            // Given
            var id = UUID.randomUUID();
            var snapshot = newSnapshot(id, TENANT_A, "ABC-1", "1200.00");

            // When
            var result = adapter.insert(snapshot);

            // Then
            assertThat(result).isEqualTo(
                    Result.<SubmissionId, RegisterSubmissionError.NumberAlreadyUsed>success(
                            new SubmissionId(id)));
            // Asserted directly against the column, not through the mapper: without a @Version
            // property, save() on a fresh entity issues an UPDATE affecting zero rows and reports
            // success, so "the adapter said it worked" proves nothing on its own.
            assertThat(versionOf(id)).isNotNull();
            assertThat(rowCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("SC-82: every supplied field reaches the row exactly as supplied, scale included")
        void shouldStoreEveryFieldExactlyAsSuppliedWhenInsertingNewAggregate() {
            // Given
            var id = UUID.randomUUID();
            var snapshot = newSnapshot(id, TENANT_A, "ABC-1", "1200.00");

            // When
            adapter.insert(snapshot);

            // Then
            assertThat(adapter.findOwn(TENANT_A, id)).hasValueSatisfying(stored -> {
                assertThat(stored.id()).isEqualTo(id);
                assertThat(stored.tenantId()).isEqualTo(TENANT_A);
                assertThat(stored.submissionNumber()).isEqualTo("ABC-1");
                // isEqualTo, not isEqualByComparingTo: BR-12 is a rule about the written scale,
                // and 1200.00 must come back as 1200.00 rather than as 1200.
                assertThat(stored.insuredLimit()).isEqualTo(new BigDecimal("1200.00"));
                assertThat(stored.insuredLimit().scale()).isEqualTo(2);
                assertThat(stored.registeredBy()).isEqualTo("uw-01");
                assertThat(stored.registeredAt()).isEqualTo(REGISTERED_AT);
            });
        }

        @Test
        @DisplayName("SC-79: a number of a single hyphen reaches the row untouched: it is present, "
                + "uses only allowed characters, and BR-07 applies no normalisation")
        void shouldStoreSingleHyphenNumberUntouchedWhenInserting() {
            // Given
            var id = UUID.randomUUID();
            var snapshot = newSnapshot(id, TENANT_A, "-", "1200.00");

            // When
            var result = adapter.insert(snapshot);

            // Then
            assertThat(result.isSuccess()).isTrue();
            assertThat(adapter.findOwn(TENANT_A, id)).hasValueSatisfying(stored ->
                    assertThat(stored.submissionNumber()).isEqualTo("-"));
        }
    }

    @Nested
    @DisplayName("The snapshot round trip through the mapper and the aggregate")
    class SnapshotRoundTrip {

        @Test
        @DisplayName("SC-82: the version survives both directions: out through the mapper into the row, "
                + "back through the mapper, and through reconstitute()")
        void shouldPreserveVersionInBothDirectionsWhenRoundTrippingSnapshot() {
            // Given
            var id = UUID.randomUUID();
            adapter.insert(newSnapshot(id, TENANT_A, "ABC-1", "1200.00"));

            // When
            var loaded = adapter.findOwn(TENANT_A, id).orElseThrow();
            var reconstituted = SubmissionRoundTrip.reconstituteAndSnapshot(loaded);

            // Then
            // Loaded, so the version must now be present — the opposite of the null it went in as.
            assertThat(loaded.version()).isNotNull();

            // And reconstituting must not rewrite or drop it on the way through the aggregate.
            assertThat(reconstituted).isEqualTo(loaded);
            assertThat(reconstituted.version()).isEqualTo(loaded.version());
        }

        @Test
        @DisplayName("SC-82: the whole snapshot survives the round trip, not merely the version")
        void shouldPreserveWholeSnapshotWhenRoundTrippingThroughMapperAndAggregate() {
            // Given
            var id = UUID.randomUUID();
            adapter.insert(newSnapshot(id, TENANT_A, "ABC-1", "1200.00"));

            // When
            var loaded = adapter.findOwn(TENANT_A, id).orElseThrow();
            var reconstituted = SubmissionRoundTrip.reconstituteAndSnapshot(loaded);

            // Then
            assertThat(reconstituted).isEqualTo(new SubmissionSnapshot(id, TENANT_A, "ABC-1",
                    new BigDecimal("1200.00"), "uw-01", REGISTERED_AT, loaded.version()));
        }

        @Test
        @DisplayName("SC-85: a row of another organisation is not loadable, so the read-back is tenant "
                + "filtered like every other statement (BR-17)")
        void shouldNotLoadRowWhenTenantBelongsToAnotherOrganisation() {
            // Given
            var id = UUID.randomUUID();
            adapter.insert(newSnapshot(id, TENANT_A, "ABC-1", "1200.00"));

            // When
            var result = adapter.findOwn(TENANT_B, id);

            // Then
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("Uniqueness is per organisation (BR-06, BR-08, BR-09)")
    class Uniqueness {

        @Test
        @DisplayName("SC-83: a duplicate (tenant_id, submission_number) inserts nothing and is refused as "
                + "already used")
        void shouldRefuseInsertionAndReturnAlreadyUsedWhenDuplicateReferenceInserted() {
            // Given
            adapter.insert(newSnapshot(UUID.randomUUID(), TENANT_A, "ABC-1", "1200.00"));
            var duplicate = newSnapshot(UUID.randomUUID(), TENANT_A, "ABC-1", "77.00");

            // When
            var second = adapter.insert(duplicate);

            // Then
            assertThat(second).isEqualTo(
                    Result.<SubmissionId, RegisterSubmissionError.NumberAlreadyUsed>failure(
                            new RegisterSubmissionError.NumberAlreadyUsed("ABC-1")));
            assertThat(rowCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("SC-83: the earlier row is left completely untouched by the refused duplicate: "
                + "ON CONFLICT DO NOTHING must not become DO UPDATE")
        void shouldLeaveEarlierRowUntouchedWhenDuplicateReferenceRefused() {
            // Given
            var firstId = UUID.randomUUID();
            adapter.insert(newSnapshot(firstId, TENANT_A, "ABC-1", "1200.00"));
            var before = adapter.findOwn(TENANT_A, firstId).orElseThrow();

            // When
            adapter.insert(newSnapshot(UUID.randomUUID(), TENANT_A, "ABC-1", "77.00"));

            // Then
            assertThat(adapter.findOwn(TENANT_A, firstId)).hasValue(before);
        }

        @Test
        @DisplayName("SC-83: a refused duplicate leaves the session usable, which a caught "
                + "DuplicateKeyException inside a transaction would not")
        void shouldKeepSessionUsableWhenDuplicateReferenceIsRefused() {
            // Given
            adapter.insert(newSnapshot(UUID.randomUUID(), TENANT_A, "ABC-1", "1200.00"));
            adapter.insert(newSnapshot(UUID.randomUUID(), TENANT_A, "ABC-1", "77.00"));
            var subsequentSnapshot = newSnapshot(UUID.randomUUID(), TENANT_A, "ABC-2", "88.00");

            // When
            // A poisoned transaction would fail this with "current transaction is aborted".
            var afterwards = adapter.insert(subsequentSnapshot);

            // Then
            assertThat(afterwards.isSuccess()).isTrue();
            assertThat(rowCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("SC-84: the identical reference under a DIFFERENT tenant_id inserts normally: "
                + "uniqueness never crosses organisations (BR-08)")
        void shouldInsertSuccessfullyWhenSameReferenceUnderDifferentTenant() {
            // Given
            adapter.insert(newSnapshot(UUID.randomUUID(), TENANT_A, "ABC-1", "1200.00"));
            var otherTenantSnapshot = newSnapshot(UUID.randomUUID(), TENANT_B, "ABC-1", "1200.00");

            // When
            var otherTenant = adapter.insert(otherTenantSnapshot);

            // Then
            assertThat(otherTenant.isSuccess()).isTrue();
            assertThat(rowCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("SC-84: references differing only in letter case are different references, which is "
                + "what COLLATE \"C\" on the column protects (BR-07)")
        void shouldTreatReferencesDifferingOnlyInCaseAsDistinctWhenInserting() {
            // Given
            adapter.insert(newSnapshot(UUID.randomUUID(), TENANT_A, "ABC-1", "1200.00"));
            var lowerCaseSnapshot = newSnapshot(UUID.randomUUID(), TENANT_A, "abc-1", "1200.00");

            // When
            var lowerCase = adapter.insert(lowerCaseSnapshot);

            // Then
            // A case-insensitive ICU collation on the column would silently refuse this one and
            // break BR-06 and BR-07 together.
            assertThat(lowerCase.isSuccess()).isTrue();
            assertThat(rowCount()).isEqualTo(2);
        }
    }
}
