package com.gs5.underwriting.submission.query;

import com.gs5.underwriting.shared.kernel.ActorContext;
import com.gs5.underwriting.shared.kernel.ActorRole;
import com.gs5.underwriting.shared.kernel.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Adversarial probing of the submission read side, added by {@code @qa-explorer}.
 *
 * <p>This class deliberately asserts nothing the happy-path suite already asserts. Everything
 * here is an attack: a cross-tenant probe, paging arithmetic at {@code Integer.MAX_VALUE}, a
 * clamp bypass attempted by building the query record directly rather than through the
 * controller, every {@link ActorRole} against the one operation every role may perform, many
 * searches racing, and a masking sweep over <em>every</em> read-side record that carries a value
 * BR-22 or OPEN-01 forbids in a log — not only the two the happy-path suite happens to name.
 */
class SubmissionSearchAdversarialTest {

    private static final UUID TENANT_A = UUID.fromString("0a000000-0000-0000-0000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("0b000000-0000-0000-0000-00000000000b");

    private static final Instant T0 = Instant.parse("2026-11-01T10:00:00Z");

    private static final String SECRET_AMOUNT = "1234567.89";

    private InMemorySubmissionQueryRepository repository;
    private SearchSubmissionsUseCase useCase;

    @BeforeEach
    void setUp() {
        repository = new InMemorySubmissionQueryRepository();
        useCase = new SearchSubmissionsUseCase(repository);
    }

    private static UUID uuid(int n) {
        return UUID.fromString("00000000-0000-0000-0000-0000000000" + String.format("%02d", n));
    }

    private static Instant at(int hours) {
        return T0.plus(Duration.ofHours(hours));
    }

    private static ActorContext actor(UUID tenantId, ActorRole role) {
        return new ActorContext(tenantId, "actor-of-" + tenantId, role);
    }

    private Result<SubmissionPageView, SearchSubmissionsError> searchAs(ActorContext caller,
                                                                        SearchSubmissionsQuery q) {
        return ActorContext.callWith(caller, () -> useCase.ask(q));
    }

    private static SearchSubmissionsQuery unfiltered(int page, int size) {
        return new SearchSubmissionsQuery(null, null, null, null, null, page, size);
    }

    private static SearchSubmissionsQuery byReference(String externalRef) {
        return new SearchSubmissionsQuery(externalRef, null, null, null, null, 0, 100);
    }

    // ================================================================ BOLA

    @Nested
    @DisplayName("BOLA: another organisation's data is indistinguishable from data nobody holds "
            + "(BR-17)")
    class CrossTenantProbing {

        @BeforeEach
        void seedOtherTenantOnly() {
            // Everything in the store belongs to TENANT_B. TENANT_A owns nothing at all.
            repository.givenSubmission(TENANT_B, uuid(1), "SECRET-REF", SECRET_AMOUNT, at(0));
            repository.givenSubmission(TENANT_B, uuid(2), "SECRET-REF-2", "999.00", at(1));
        }

        @Test
        @DisplayName("a reference held ONLY by another organisation gives byte-for-byte the same "
                + "answer as a reference nobody holds anywhere")
        void shouldReturnByteExactSameEmptyResultWhenQueryingForeignReferenceVersusNonexistent() {
            // Given
            var caller = actor(TENANT_A, ActorRole.UNDERWRITER);

            // When
            var foreign = searchAs(caller, byReference("SECRET-REF"));
            var nonexistent = searchAs(caller, byReference("NO-SUCH-REFERENCE-ANYWHERE"));

            // Then
            assertThat(foreign).isEqualTo(nonexistent);
            assertThat(foreign).isEqualTo(
                    Result.success(new SubmissionPageView(List.of(), 0L, 0, 100)));
        }

        @Test
        @DisplayName("the TOTAL MATCH COUNT does not leak the other organisation either: a count "
                + "of 2 would disclose two submissions exist without returning a single row")
        void shouldReturnZeroCountWhenQueryingTenantWithNoSubmissions() {
            // Given
            var caller = actor(TENANT_A, ActorRole.UNDERWRITER);

            // When
            var result = searchAs(caller, unfiltered(0, 100));

            // Then
            assertThat(result).isEqualTo(
                    Result.success(new SubmissionPageView(List.of(), 0L, 0, 100)));
        }

        @Test
        @DisplayName("a cross-tenant probe is a SUCCESS with an empty page, never a refusal: a "
                + "403-shaped answer would confirm the record exists")
        void shouldReturnEmptySuccessWithoutRefusalWhenCrossTenantProbed() {
            // Given
            var caller = actor(TENANT_A, ActorRole.VIEWER);

            // When
            var result = searchAs(caller, byReference("SECRET-REF"));

            // Then
            assertThat(result.isSuccess()).isTrue();
            assertThat(SearchSubmissionsError.class.getPermittedSubclasses())
                    .containsExactly(SearchSubmissionsError.CallerNotIdentified.class);
        }

        @Test
        @DisplayName("no filter combination reaches across the organisation boundary, including "
                + "one aimed exactly at the other organisation's amount and moment")
        void shouldNeverReturnOtherTenantRowsWhenFilterMatchesExactPropertiesOfForeignSubmission() {
            // Given
            var caller = actor(TENANT_A, ActorRole.SENIOR_UNDERWRITER);
            var aimedAtTheOtherTenantsRow = new SearchSubmissionsQuery(
                    "SECRET-REF",
                    new BigDecimal(SECRET_AMOUNT),
                    new BigDecimal(SECRET_AMOUNT),
                    at(0), at(0), 0, 100);

            // When
            var result = searchAs(caller, aimedAtTheOtherTenantsRow);

            // Then
            assertThat(result).isEqualTo(Result.success(new SubmissionPageView(List.of(), 0L, 0, 100)));
        }

        @Test
        @DisplayName("paging past the end of an empty own matching set never spills into the "
                + "other organisation's rows")
        void shouldNeverSpillIntoOtherTenantRowsWhenPagingPastEmptyOwnSet() {
            // Given
            var caller = actor(TENANT_A, ActorRole.UNDERWRITER);

            // When & Then
            for (var page : List.of(0, 1, 2, 1000)) {
                assertThat(searchAs(caller, unfiltered(page, 100)))
                        .isEqualTo(Result.success(
                                new SubmissionPageView(List.of(), 0L, page, 100)));
            }
        }

        @Test
        @DisplayName("the caller cannot name an organisation: the only tenant the useCase can use "
                + "is the actor's own, because the query record has no tenant component at all")
        void shouldEnsureQueryRecordHasNoTenantFieldWhenInspectingRecordStructure() {
            // Given & When
            var componentNames = Arrays.stream(SearchSubmissionsQuery.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            // Then
            assertThat(componentNames)
                    .doesNotContain("tenantId", "tenant", "organisation", "organizationId");
        }
    }

    // ================================================================ paging arithmetic

    @Nested
    @DisplayName("Paging arithmetic at the edges (BR-25, BR-28)")
    class PagingArithmetic {

        @Test
        @DisplayName("page = Integer.MAX_VALUE produces a POSITIVE offset: (long) page * size, "
                + "not an int multiplication that silently goes negative")
        void shouldComputePositiveLongOffsetWhenPageNumberIsMaxInt() {
            // Given
            var recorder = new RecordingQueryRepository();
            var recordingUseCase = new SearchSubmissionsUseCase(recorder);

            // When
            ActorContext.runWith(actor(TENANT_A, ActorRole.UNDERWRITER),
                    () -> recordingUseCase.ask(unfiltered(Integer.MAX_VALUE, 100)));

            // Then
            assertThat(recorder.lastOffset).isEqualTo(214_748_364_700L).isPositive();
            assertThat(recorder.lastLimit).isEqualTo(100);
        }

        @Test
        @DisplayName("the largest page request the clamp allows still fits a long offset")
        void shouldFitWithinLongLimitsWhenOffsetCalculatedAtMaxPageAndClamp() {
            // Given
            var recorder = new RecordingQueryRepository();
            var recordingUseCase = new SearchSubmissionsUseCase(recorder);

            // When
            ActorContext.runWith(actor(TENANT_A, ActorRole.UNDERWRITER),
                    () -> recordingUseCase.ask(
                            unfiltered(Integer.MAX_VALUE, SearchSubmissionsQuery.MAX_PAGE_SIZE)));

            // Then
            assertThat(recorder.lastOffset)
                    .isPositive()
                    .isLessThan(Long.MAX_VALUE / 2);
        }

        @Test
        @DisplayName("the clamp CANNOT be bypassed by building the query record directly instead "
                + "of going through the controller")
        void shouldEnforceClampingWhenConstructingRecordDirectly() {
            // Given & When
            var hostile = new SearchSubmissionsQuery(null, null, null, null, null,
                    Integer.MIN_VALUE, Integer.MAX_VALUE);

            // Then
            assertThat(hostile.page()).isZero();
            assertThat(hostile.size()).isEqualTo(SearchSubmissionsQuery.MAX_PAGE_SIZE);
        }

        @Test
        @DisplayName("a client that asks for size 500, receives 100, and then pages on the SIZE "
                + "IT WAS GIVEN skips nothing: the echoed size is what makes clamping safe")
        void shouldSkipNoRowsWhenClientPagesUsingEchoedClampedSize() {
            // Given
            var expected = new ArrayList<SubmissionSummaryView>();
            IntStream.rangeClosed(1, 12).forEach(n ->
                    repository.givenSubmission(TENANT_A, uuid(n), "S-" + n, n + ".00", at(n)));
            var caller = actor(TENANT_A, ActorRole.VIEWER);

            // When
            var first = searchAs(caller, unfiltered(0, 500));
            var appliedSize = first.toOptional().orElseThrow().size();

            // Then
            assertThat(appliedSize).isEqualTo(SearchSubmissionsQuery.MAX_PAGE_SIZE);

            var pageNumber = 0;
            while (true) {
                var view = searchAs(caller, unfiltered(pageNumber, appliedSize))
                        .toOptional().orElseThrow();
                if (view.submissions().isEmpty()) {
                    break;
                }
                expected.addAll(view.submissions());
                pageNumber++;
            }

            assertThat(expected).hasSize(12).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("a client that ignores the echoed size and keeps paging on 500 skips rows — "
                + "which is why the echo exists, and is the caller's bug, not the service's")
        void shouldDemonstrateRowSkippingWhenClientIgnoresEchoedSize() {
            // Given
            IntStream.rangeClosed(1, 12).forEach(n ->
                    repository.givenSubmission(TENANT_A, uuid(n), "S-" + n, n + ".00", at(n)));
            var caller = actor(TENANT_A, ActorRole.VIEWER);

            // When
            var page0 = searchAs(caller, unfiltered(0, 500)).toOptional().orElseThrow();
            var page1 = searchAs(caller, unfiltered(1, 500)).toOptional().orElseThrow();

            // Then
            assertThat(page0.size()).isEqualTo(SearchSubmissionsQuery.MAX_PAGE_SIZE);
            assertThat(page1.size()).isEqualTo(SearchSubmissionsQuery.MAX_PAGE_SIZE);
            assertThat(page0.submissions()).hasSize(12);
            assertThat(page1.submissions()).isEmpty();
            assertThat(page1.totalMatchCount()).isEqualTo(12L);
        }
    }

    // ================================================================ roles

    @Nested
    @DisplayName("Every role gets the identical answer, exhaustively over ActorRole (BR-16)")
    class EveryRole {

        @ParameterizedTest(name = "[{index}] role={0}")
        @EnumSource(ActorRole.class)
        @DisplayName("every role, including one added to the enum after this was written, gets "
                + "the identical page and count")
        void shouldReturnIdenticalResultsWhenSearchedByAnyRole(ActorRole role) {
            // Given
            IntStream.rangeClosed(1, 3).forEach(n ->
                    repository.givenSubmission(TENANT_A, uuid(n), "S-" + n, n + ".00", at(n)));

            // When
            var asViewer = searchAs(actor(TENANT_A, ActorRole.VIEWER), unfiltered(0, 100));
            var asRole = searchAs(actor(TENANT_A, role), unfiltered(0, 100));

            // Then
            assertThat(asRole).isEqualTo(asViewer);
            assertThat(asRole.toOptional().orElseThrow().totalMatchCount()).isEqualTo(3L);
        }

        @Test
        @DisplayName("the read side never reads the role at all: ActorRole is not referenced "
                + "anywhere in the query package's own vocabulary")
        void shouldNotReferenceActorRoleInQueryPackageVocabularyWhenInspected() {
            // Given
            var readSideRecords = List.of(SearchSubmissionsQuery.class,
                    SubmissionSearchCriteria.class, SubmissionSummaryView.class,
                    SubmissionPageView.class);

            // When & Then
            assertThat(readSideRecords)
                    .allSatisfy(type -> {
                        var componentTypes = Arrays.stream(type.getRecordComponents())
                                .map(RecordComponent::getType)
                                .toList();
                        assertThat(componentTypes)
                                .noneMatch(compType -> compType.equals(ActorRole.class));
                    });
        }
    }

    // ================================================================ concurrency

    @Nested
    @DisplayName("Many searches at once, on virtual threads")
    class Concurrency {

        @Test
        @DisplayName("64 concurrent searches from two organisations never see each other's rows, "
                + "and no answer is a refusal")
        void shouldMaintainStrictTenantIsolationWhenManySearchesRaceConcurrently() {
            // Given
            repository.givenSubmission(TENANT_A, uuid(1), "A-1", "100.00", at(1));
            repository.givenSubmission(TENANT_A, uuid(2), "A-2", "200.00", at(2));
            repository.givenSubmission(TENANT_B, uuid(3), "B-1", "300.00", at(3));

            var answers = new ConcurrentLinkedQueue<SubmissionPageView>();

            // When
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var tasks = IntStream.range(0, 64)
                        .mapToObj(n -> CompletableFuture.supplyAsync(
                                () -> searchAs(
                                        actor(n % 2 == 0 ? TENANT_A : TENANT_B,
                                                ActorRole.values()[n % ActorRole.values().length]),
                                        unfiltered(0, 100)),
                                pool))
                        .toList();
                tasks.forEach(task -> {
                    var result = task.join();
                    assertThat(result.isSuccess()).isTrue();
                    answers.add(result.toOptional().orElseThrow());
                });
            }

            // Then
            assertThat(answers).hasSize(64);
            assertThat(answers.stream().map(SubmissionPageView::totalMatchCount).distinct().toList())
                    .containsExactlyInAnyOrder(2L, 1L);
            assertThat(answers).allSatisfy(view -> {
                var references = view.submissions().stream()
                        .map(SubmissionSummaryView::externalRef)
                        .toList();
                if (view.totalMatchCount() == 1L) {
                    assertThat(references).containsExactly("B-1");
                } else {
                    assertThat(references).containsExactlyInAnyOrder("A-1", "A-2");
                }
            });
        }

        @Test
        @DisplayName("a search running while rows are being added returns a self-consistent page: "
                + "its page never holds more rows than its own count reports")
        void shouldReturnSelfConsistentPageWhenSearchRacesWithInsertions() {
            // Given
            var caller = actor(TENANT_A, ActorRole.VIEWER);

            // When
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var writer = CompletableFuture.runAsync(() ->
                        IntStream.rangeClosed(1, 40).forEach(n -> repository.givenSubmission(
                                TENANT_A, uuid(n), "S-" + n, n + ".00", at(n))), pool);

                var readers = IntStream.range(0, 32)
                        .mapToObj(_ -> CompletableFuture.supplyAsync(
                                () -> searchAs(caller, unfiltered(0, 100)), pool))
                        .toList();

                writer.join();

                // Then
                readers.forEach(reader -> {
                    var result = reader.join();
                    assertThat(result.isSuccess()).isTrue();
                    var view = result.toOptional().orElseThrow();
                    assertThat((long) view.submissions().size())
                            .isLessThanOrEqualTo(view.totalMatchCount());
                    assertThat(view.submissions()).doesNotContainNull().doesNotHaveDuplicates();
                });
            }
        }
    }

    // ================================================================ masking

    @Nested
    @DisplayName("Masking sweep over EVERY read-side record, not only the ones already tested "
            + "(BR-22, OPEN-01)")
    class MaskingSweep {

        private static final BigDecimal LOWER = new BigDecimal("1234567.89");
        private static final BigDecimal UPPER = new BigDecimal("9876543.21");

        @Test
        @DisplayName("FINDING QA-01 — SubmissionSearchCriteria masks both insured limit bounds "
                + "when logged")
        void shouldMaskBothInsuredLimitBoundsWhenSubmissionSearchCriteriaPrinted() {
            // Given
            var criteria = new SubmissionSearchCriteria(TENANT_A, "S-1", LOWER, UPPER,
                    at(0), at(1));

            // When
            var printed = criteria.toString();

            // Then
            assertThat(printed)
                    .doesNotContain("1234567.89")
                    .doesNotContain("9876543.21");
        }

        @Test
        @DisplayName("the query record itself masks both bounds (regression guard on the one "
                + "that is right)")
        void shouldMaskBothAmountBoundsWhenSearchSubmissionsQueryPrinted() {
            // Given
            var query = new SearchSubmissionsQuery("S-1", LOWER, UPPER, at(0), at(1), 0, 100);

            // When
            var printed = query.toString();

            // Then
            assertThat(printed)
                    .doesNotContain("1234567.89")
                    .doesNotContain("9876543.21")
                    .contains("S-1");
        }

        @Test
        @DisplayName("a page view discloses neither amount nor registering caller — it has no "
                + "masking toString() of its own and is safe only because every row it holds has "
                + "one, so this test is the guard on that transitive property")
        void shouldDiscloseNeitherAmountNorCallerWhenSubmissionPageViewPrinted() {
            // Given
            var row = new SubmissionSummaryView(uuid(1), "S-1", LOWER, "uw-secret-01", at(0));
            var view = new SubmissionPageView(List.of(row), 1L, 0, 100);

            // When
            var printed = view.toString();

            // Then
            assertThat(printed)
                    .doesNotContain("1234567.89")
                    .doesNotContain("uw-secret-01")
                    .contains("S-1");
        }

        @Test
        @DisplayName("the search refusal hierarchy is exactly one variant carrying no component at "
                + "all, so it cannot carry a value forbidden in a log")
        void shouldHaveExactlyOneSearchRefusalReasonCarryingNoComponents() {
            // C-3: this test used to claim a read-side AND write-side sweep while inspecting only
            // SearchSubmissionsError, and asserted allSatisfy over an EMPTY list, which passes
            // vacuously whatever it contains. The write side is swept by
            // SubmissionRegistrationAdversarialTest. Here the size and the emptiness are asserted
            // outright, so a second variant or a first component fails this instead of slipping by.
            var variants = SearchSubmissionsError.class.getPermittedSubclasses();
            var components = Arrays.stream(variants)
                    .flatMap(variant -> Arrays.stream(variant.getRecordComponents()))
                    .toList();

            assertThat(variants).hasSize(1);
            assertThat(components).isEmpty();
        }
    }

    /** Captures the limit and offset the useCase computes, which no fake in the suite exposes. */
    private static final class RecordingQueryRepository implements SubmissionQueryRepository {

        private int lastLimit;
        private long lastOffset;

        @Override
        public List<SubmissionSummaryView> findPage(SubmissionSearchCriteria criteria,
                                                    int limit,
                                                    long offset) {
            lastLimit = limit;
            lastOffset = offset;
            return List.of();
        }

        @Override
        public long countMatching(SubmissionSearchCriteria criteria) {
            return 0L;
        }
    }
}
