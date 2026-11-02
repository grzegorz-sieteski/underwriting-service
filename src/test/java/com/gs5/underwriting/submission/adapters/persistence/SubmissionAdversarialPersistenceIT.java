package com.gs5.underwriting.submission.adapters.persistence;

import com.gs5.underwriting.shared.kernel.Result;
import com.gs5.underwriting.submission.domain.SubmissionSnapshot;
import com.gs5.underwriting.submission.query.SubmissionSearchCriteria;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Adversarial probing of the persistence layer, added by {@code @qa-explorer}.
 *
 * <p><strong>Never executed.</strong> The environment this was written in has no Docker daemon
 * and no PostgreSQL, so Flyway has never run and the Spring context has never started. These
 * tests compile and are discovered by Failsafe; nothing here is a verified result. Each test
 * states the expectation a human running {@code ./mvnw -B verify} should check.
 *
 * <p><strong>Updated 2026-11-02 by {@code @test-designer}.</strong> This class used to carry
 * {@code a_nul_byte_reference_does_not_escape_as_an_exception}, which was expected to fail
 * because it pinned the QA-02 defect. QA-02 is closed: {@code BR-29} refuses every category-Cc
 * character in the domain, so a NUL-bearing reference can no longer reach this adapter. That test
 * is replaced by {@code every_reference_the_domain_accepts_is_storable}, which asserts the
 * property BR-29 was decided to guarantee — that "valid" and "recordable" cannot disagree.
 * Nothing in this class is expected to fail any more.
 *
 * <p><strong>Why this is a separate class from the two existing adapter ITs.</strong> Those are
 * {@code @DataJdbcTest}, which is transactional and rolls each test back. Two things here are
 * incompatible with that, and putting them in those classes would have made the existing suite
 * fail for mechanical reasons rather than real ones:
 *
 * <ul>
 *   <li>A <strong>constraint violation aborts the PostgreSQL transaction</strong>. Any query
 *       issued afterwards in the same transaction fails with "current transaction is aborted",
 *       so a test that provokes a {@code CHECK} violation and then counts rows cannot work
 *       inside one.</li>
 *   <li>A <strong>concurrent insert runs on its own connection</strong> and commits outside the
 *       test's transaction, so the rollback does not undo it and the test's own transaction
 *       cannot reliably see it. The committed rows would then leak into every later test's row
 *       count.</li>
 * </ul>
 *
 * <p>Hence {@code @Transactional(propagation = NOT_SUPPORTED)} and an explicit
 * {@code TRUNCATE} after each test: no ambient transaction, and the table is genuinely emptied
 * rather than rolled back.
 */
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigureDataSourceInitialization
@Import({SubmissionCommandSqlAdapter.class, SubmissionQuerySqlAdapter.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class SubmissionAdversarialPersistenceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    private static final UUID TENANT_A = UUID.fromString("0a000000-0000-0000-0000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("0b000000-0000-0000-0000-00000000000b");

    private static final Instant REGISTERED_AT = Instant.parse("2026-11-01T10:15:30Z");

    @Autowired
    private SubmissionCommandSqlAdapter commandAdapter;

    @Autowired
    private SubmissionQuerySqlAdapter queryAdapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void emptyTheTable() {
        jdbcTemplate.execute("TRUNCATE TABLE submissions");
    }

    private static SubmissionSnapshot snapshot(UUID tenantId,
                                               String externalRef,
                                               String insuredLimit) {
        return snapshot(UUID.randomUUID(), tenantId, externalRef, insuredLimit);
    }

    /** Same, with the id pinned, for a test that has to read the row back afterwards. */
    private static SubmissionSnapshot snapshot(UUID id,
                                               UUID tenantId,
                                               String externalRef,
                                               String insuredLimit) {
        return new SubmissionSnapshot(id, tenantId, externalRef,
                new BigDecimal(insuredLimit), "uw-01", REGISTERED_AT, null);
    }

    private int rowCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM submissions", Integer.class);
    }

    // ================================================================================

    @Nested
    @DisplayName("Inputs the domain accepts but the column may not")
    class InputsTheDomainAcceptsButTheColumnMayNot {

        /*
         * REVERSED 2026-11-02 by BR-29, by @test-designer.
         *
         * This slot held a_nul_byte_reference_does_not_escape_as_an_exception, which asserted that
         * the ADAPTER must turn a NUL-bearing reference into a Result rather than let PgJDBC's
         * "Zero bytes may not occur in string parameters" escape. That requirement no longer
         * exists: BR-29 refuses every category-Cc character in the domain, so a NUL reference is
         * now rejected before any port is called and cannot reach this adapter through the useCase
         * at all. Asserting it here would pin a requirement nobody has, and the old test bypassed
         * the domain to construct a snapshot the domain can no longer produce.
         *
         * The refusal is owned by RegisterSubmissionUseCaseTest$ControlCharactersInTheReference and
         * SubmissionRegistrationAdversarialTest$ReferenceCharacterSet. What is still worth
         * asserting HERE is the integration-level half of the same promise, below.
         */

        @Test
        @DisplayName("every reference the domain now accepts is storable, which is the property "
                + "BR-29 was decided to guarantee (QA-02, closed)")
        void shouldStoreEveryReferenceAcceptedByDomainWhenInserting() {
            // Given
            // The business point of BR-29 is that "valid" and "recordable" can no longer
            // disagree. These are the awkward references that remain valid after it — spaces
            // only, leading and trailing spaces, a format character, an emoji at the 64-code-
            // point limit — and every one of them must reach the column without a driver refusal.
            var stillValid = List.of(
                    "   ",                       // spaces only: BR-29 does not touch U+0020
                    " ABC-1",                    // leading space, preserved by BR-07
                    "ABC-1 ",                    // trailing space, preserved by BR-07
                    "‮exe.txt",             // U+202E is category Cf, not Cc
                    " ",                    // U+00A0 NO-BREAK SPACE is category Zs
                    "😀".repeat(64));  // 64 code points, 128 UTF-16 chars

            // When & Then
            assertThat(stillValid).allSatisfy(reference -> {
                var id = UUID.randomUUID();
                var inserted = commandAdapter.insert(
                        snapshot(id, TENANT_A, reference, "1200.00"));

                assertThat(inserted.isSuccess())
                        .describedAs("reference %s is valid under BR-29 and must be storable",
                                reference.codePoints().boxed().toList())
                        .isTrue();
                assertThat(commandAdapter.findOwn(TENANT_A, id))
                        .hasValueSatisfying(stored ->
                                assertThat(stored.externalRef()).isEqualTo(reference));
            });
        }

        @Test
        @DisplayName("an amount with 400 significant digits round-trips exactly: BR-13 says there "
                + "is no upper bound, and the unconstrained NUMERIC column is what honours it")
        void shouldRoundTripEnormousAmountExactlyWhenInserting() {
            // Given
            var enormous = "9".repeat(400) + ".00";
            var toInsert = snapshot(TENANT_A, "HUGE-1", enormous);

            // When
            var result = commandAdapter.insert(toInsert);

            // Then
            assertThat(result.isSuccess()).isTrue();
            assertThat(commandAdapter.findOwn(TENANT_A, toInsert.id()))
                    .get()
                    .satisfies(stored ->
                            assertThat(stored.insuredLimit()).isEqualTo(new BigDecimal(enormous)));
        }

        @Test
        @DisplayName("64 supplementary code points occupy 128 UTF-16 chars and still fit "
                + "VARCHAR(64), which is the claim the domain's length check rests on")
        void shouldFitSixtyFourSupplementaryCodePointsInColumnWhenInserting() {
            // Given
            var reference = "😀".repeat(64);
            assertThat(reference.length()).isEqualTo(128);

            // When
            // If VARCHAR(64) counted UTF-16 units or bytes this would be a 22001 string-data
            // right-truncation error, and BR-05 would be enforced in two places that disagree.
            var result = commandAdapter.insert(snapshot(TENANT_A, reference, "1200.00"));

            // Then
            assertThat(result.isSuccess()).isTrue();
            assertThat(rowCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("64 code points of base letters plus combining marks fit the column too, "
                + "although they render as only 32 graphemes")
        void shouldFitSixtyFourCombiningCodePointsInColumnWhenInserting() {
            // Given
            var snapshot = snapshot(TENANT_A, "é".repeat(32), "1200.00");

            // When
            var result = commandAdapter.insert(snapshot);

            // Then
            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("the scale CHECK is live: an amount that bypasses the aggregate is still "
                + "rejected by the column, so defence in depth is not decoration")
        void shouldFailScaleCheckWhenAmountExceedsAllowedScale() {
            // Given
            // Going through the adapter directly is the only way to skip BR-12. A green run with
            // no rejection would mean the CHECK was never created — the shape of failure this
            // repository's own notes warn about: a gate that inspects nothing and reports green.
            var badSnapshot = snapshot(TENANT_A, "BAD-SCALE", "1200.005");

            // When
            var thrown = catchThrowable(() ->
                    commandAdapter.insert(badSnapshot));

            // Then
            assertThat(thrown).isNotNull();
            assertThat(rowCount()).isZero();
        }

        @Test
        @DisplayName("the positivity CHECK is live as well")
        void shouldFailPositivityCheckWhenAmountIsNegative() {
            // Given
            var badSnapshot = snapshot(TENANT_A, "BAD-SIGN", "-1200.00");

            // When
            var thrown = catchThrowable(() ->
                    commandAdapter.insert(badSnapshot));

            // Then
            assertThat(thrown).isNotNull();
            assertThat(rowCount()).isZero();
        }

        @Test
        @DisplayName("the control-character CHECK is live as well (BR-29 defence in depth)")
        void shouldFailControlCharacterCheckWhenReferenceContainsControlCharacter() {
            // Given
            var badSnapshot = snapshot(TENANT_A, "BAD\tREF", "1200.00");

            // When
            var thrown = catchThrowable(() ->
                    commandAdapter.insert(badSnapshot));

            // Then
            assertThat(thrown).isNotNull();
            assertThat(rowCount()).isZero();
        }

        @Test
        @DisplayName("a whitespace-only reference is stored untouched and does not trip the "
                + "non-empty CHECK: BR-04 refuses no characters, and a space is a character")
        void shouldStoreWhitespaceOnlyReferenceAndPassNonEmptyCheckWhenInserting() {
            // Given
            var whitespaceSnapshot = snapshot(TENANT_A, "   ", "1200.00");

            // When
            var result = commandAdapter.insert(whitespaceSnapshot);

            // Then
            assertThat(result.isSuccess()).isTrue();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT external_ref FROM submissions", String.class)).isEqualTo("   ");
        }
    }

    // ================================================================================

    @Nested
    @DisplayName("The BR-09 race against the real unique index")
    class TheRaceAgainstTheRealIndex {

        @Test
        @DisplayName("32 inserts racing for one reference in one organisation leave exactly one "
                + "row: the unique index, not the application, is what settles it")
        void shouldLeaveExactlyOneRowWhenRacingInsertsForSameReferenceWithinTenant() {
            // Given
            // The useCase-level test proves the pipeline. Only a real index can show that
            // ON CONFLICT DO NOTHING under genuine contention returns zero rows to every loser
            // rather than deadlocking or raising a 23505 that escapes as an exception.

            // When
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var attempts = IntStream.range(0, 32)
                        .mapToObj(_ -> CompletableFuture.supplyAsync(
                                () -> commandAdapter.insert(
                                        snapshot(TENANT_A, "RACE-REAL", "1200.00")),
                                pool))
                        .toList()
                        .stream()
                        .map(CompletableFuture::join)
                        .toList();

                // Then
                assertThat(attempts.stream().filter(Result::isSuccess)).hasSize(1);
                assertThat(attempts.stream().filter(Result::isFailure)).hasSize(31);
            }

            assertThat(rowCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("the same reference racing across two organisations leaves one row EACH: "
                + "uniqueness never crosses an organisation, not even under contention (BR-08)")
        void shouldLeaveOneRowPerTenantWhenSameReferenceRacesAcrossTwoOrganisations() {
            // Given & When
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var successes = IntStream.range(0, 32)
                        .mapToObj(n -> CompletableFuture.supplyAsync(
                                () -> commandAdapter.insert(snapshot(
                                        n % 2 == 0 ? TENANT_A : TENANT_B, "SHARED-REAL",
                                        "1200.00")),
                                pool))
                        .toList()
                        .stream()
                        .map(CompletableFuture::join)
                        .filter(Result::isSuccess)
                        .count();

                // Then
                assertThat(successes).isEqualTo(2L);
            }

            assertThat(rowCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("32 searches running while 32 registrations race never return a torn row and "
                + "never return the other organisation's row")
        void shouldKeepSearchesIsolatedWithoutTornOrCrossTenantRowsWhenRacingUnderContention() {
            // Given & When
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var writers = IntStream.range(0, 32)
                        .mapToObj(n -> CompletableFuture.runAsync(
                                () -> commandAdapter.insert(snapshot(
                                        n % 2 == 0 ? TENANT_A : TENANT_B, "C-" + n, "1200.00")),
                                pool))
                        .toList();

                var readers = IntStream.range(0, 32)
                        .mapToObj(_ -> CompletableFuture.supplyAsync(
                                () -> queryAdapter.findPage(new SubmissionSearchCriteria(
                                        TENANT_A, null, null, null, null, null), 100, 0L),
                                pool))
                        .toList();

                writers.forEach(CompletableFuture::join);

                // Then
                readers.stream().map(CompletableFuture::join).forEach(rows ->
                        assertThat(rows)
                                .doesNotContainNull()
                                .allSatisfy(row -> {
                                    assertThat(row.submissionId()).isNotNull();
                                    assertThat(row.insuredLimit())
                                            .isEqualTo(new BigDecimal("1200.00"));
                                    // Only TENANT_A inserted even-numbered references.
                                    assertThat(Integer.parseInt(
                                            row.externalRef().substring(2)) % 2).isZero();
                                }));
            }

            assertThat(rowCount()).isEqualTo(32);
        }
    }

    // ================================================================================

    @Nested
    @DisplayName("Paging arithmetic against the real planner")
    class PagingArithmetic {

        @Test
        @DisplayName("the offset page = Integer.MAX_VALUE produces (214 748 364 700) binds as a "
                + "bigint and returns nothing, while the count stays true (BR-26, BR-27)")
        void shouldBindAsBigintAndReturnEmptyPageWhilePreservingCountWhenOffsetIsEnormous() {
            // Given
            IntStream.rangeClosed(1, 3).forEach(n ->
                    commandAdapter.insert(snapshot(TENANT_A, "P-" + n, "100.00")));
            var own = new SubmissionSearchCriteria(TENANT_A, null, null, null, null, null);

            // When
            // An int OFFSET would have been -100 here, which PostgreSQL rejects outright. This is
            // the test that the useCase's (long) cast is not merely a comment.
            var page = queryAdapter.findPage(own, 100, 214_748_364_700L);
            var totalCount = queryAdapter.countMatching(own);

            // Then
            assertThat(page).isEmpty();
            assertThat(totalCount).isEqualTo(3L);
        }

        @Test
        @DisplayName("a reference held only by another organisation is indistinguishable at the "
                + "SQL level from one nobody holds: both statements answer empty and zero (BR-17)")
        void shouldBeIndistinguishableFromNonexistentWhenReferenceBelongsToForeignTenant() {
            // Given
            commandAdapter.insert(snapshot(TENANT_B, "SECRET-REF", "1234567.89"));

            var foreign = new SubmissionSearchCriteria(TENANT_A, "SECRET-REF",
                    null, null, null, null);
            var nonexistent = new SubmissionSearchCriteria(TENANT_A, "NO-SUCH-REF",
                    null, null, null, null);

            // When
            var foreignPage = queryAdapter.findPage(foreign, 100, 0L);
            var nonexistentPage = queryAdapter.findPage(nonexistent, 100, 0L);
            var foreignCount = queryAdapter.countMatching(foreign);
            var nonexistentCount = queryAdapter.countMatching(nonexistent);

            // Then
            assertThat(foreignPage)
                    .isEqualTo(nonexistentPage)
                    .isEmpty();
            assertThat(foreignCount)
                    .isEqualTo(nonexistentCount)
                    .isZero();
        }
    }
}
