package com.gs5.underwriting.submission.domain;

import com.gs5.underwriting.shared.kernel.ActorContext;
import com.gs5.underwriting.shared.kernel.ActorRole;
import com.gs5.underwriting.shared.kernel.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Registering a submission: BR-01 to BR-15, and all eight refusal reasons.
 *
 * <p>Every assertion compares a whole {@code Result} value. {@code isFailure()} would pass while
 * the refusal reason was entirely the wrong one, which is the only thing these tests are for.
 *
 * <p>No business rule is asserted with {@code assertThatThrownBy}: in this architecture a business
 * rule returns a failure, and a rule that throws is the bug.
 */
class RegisterSubmissionUseCaseTest {

    private static final UUID TENANT_A = UUID.fromString("0a000000-0000-0000-0000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("0b000000-0000-0000-0000-00000000000b");

    private static final Instant REGISTERED_AT = Instant.parse("2026-11-01T10:15:30Z");

    private static final ActorContext UNDERWRITER =
            new ActorContext(TENANT_A, "uw-01", ActorRole.UNDERWRITER);
    private static final ActorContext SENIOR_UNDERWRITER =
            new ActorContext(TENANT_A, "suw-01", ActorRole.SENIOR_UNDERWRITER);
    private static final ActorContext UNDERWRITER_OF_OTHER_TENANT =
            new ActorContext(TENANT_B, "uw-99", ActorRole.UNDERWRITER);

    private static final SubmissionId EXPECTED_ID =
            new SubmissionId(FixedSubmissionIdGenerator.SUBMISSION_UUID);

    private static final BigDecimal VALID_LIMIT = new BigDecimal("1200.00");

    private InMemorySubmissionCommandRepository repository;
    private InMemorySubmissionEventPublisher eventPublisher;
    private RegisterSubmissionUseCase useCase;

    @BeforeEach
    void setUp() {
        repository = new InMemorySubmissionCommandRepository();
        eventPublisher = new InMemorySubmissionEventPublisher();
        useCase = new RegisterSubmissionUseCase(repository, eventPublisher,
                new FixedSubmissionIdGenerator(), Clock.fixed(REGISTERED_AT, ZoneOffset.UTC));
    }

    private Result<SubmissionId, RegisterSubmissionError> registerAs(ActorContext actor,
                                                                     String externalRef,
                                                                     BigDecimal insuredLimit) {
        return RegisterSubmissionCommand.parse(externalRef, insuredLimit)
                .flatMap(command -> useCase.execute(actor, command));
    }

    private static Result<SubmissionId, RegisterSubmissionError> accepted() {
        return Result.success(EXPECTED_ID);
    }

    private static Result<SubmissionId, RegisterSubmissionError> refused(
            RegisterSubmissionError reason) {
        return Result.failure(reason);
    }

    // ============================================================ BR-04, BR-07

    @Nested
    @DisplayName("A submission reference must be supplied (BR-04, BR-07)")
    class ReferencePresence {

        @ParameterizedTest(name = "[{index}] externalRef={0}")
        @NullSource
        @ValueSource(strings = {""})
        @DisplayName("an absent reference, and one containing no characters at all, are refused")
        void shouldRefuseRegistrationWhenReferenceIsAbsentOrEmpty(String externalRef) {
            // Given & When
            var result = registerAs(UNDERWRITER, externalRef, VALID_LIMIT);

            // Then
            assertThat(result).isEqualTo(refused(new RegisterSubmissionError.ReferenceMissing()));
        }

        @ParameterizedTest(name = "[{index}] externalRef=\"{0}\"")
        @ValueSource(strings = {" ", "   ", "        ", " ABC-1", "ABC-1 ", "AB CD"})
        @DisplayName("a reference made only of SPACES is VALID: BR-04 refuses a reference with no "
                + "characters at all, and BR-07 applies no trimming because none is confirmed")
        void shouldAcceptRegistrationWhenReferenceContainsSpacesOnly(String externalRef) {
            // Given & When
            var result = registerAs(UNDERWRITER, externalRef, VALID_LIMIT);

            // Then
            assertThat(result).isEqualTo(accepted());
            assertThat(repository.storedSubmission(TENANT_A, externalRef))
                    .hasValueSatisfying(stored ->
                            assertThat(stored.externalRef()).isEqualTo(externalRef));
        }

        @Test
        @DisplayName("references differing only in letter case or surrounding blank space are "
                + "four different references, all four of which register (BR-07)")
        void shouldTreatReferencesAsDistinctWhenDifferingByCaseOrSpaces() {
            // Given & When
            var results = Stream.of(
                    registerAs(UNDERWRITER, "ABC-1", VALID_LIMIT),
                    registerAs(UNDERWRITER, "abc-1", VALID_LIMIT),
                    registerAs(UNDERWRITER, " ABC-1", VALID_LIMIT),
                    registerAs(UNDERWRITER, "ABC-1 ", VALID_LIMIT)).toList();

            // Then
            assertThat(results).containsExactly(accepted(), accepted(), accepted(), accepted());
            assertThat(repository.storedCount()).isEqualTo(4);
        }
    }

    // ================================================================== BR-05

    @Nested
    @DisplayName("A submission reference is at most 64 characters (BR-05)")
    class ReferenceLength {

        @Test
        @DisplayName("a reference of exactly 64 characters is accepted")
        void shouldAcceptRegistrationWhenReferenceIsExactly64Characters() {
            // Given & When
            var result = registerAs(UNDERWRITER, "A".repeat(64), VALID_LIMIT);

            // Then
            assertThat(result).isEqualTo(accepted());
        }

        @Test
        @DisplayName("a reference of 65 characters is refused, and the reason carries both lengths")
        void shouldRefuseRegistrationWhenReferenceExceeds64Characters() {
            // Given & When
            var result = registerAs(UNDERWRITER, "A".repeat(65), VALID_LIMIT);

            // Then
            assertThat(result).isEqualTo(
                    refused(new RegisterSubmissionError.ReferenceTooLong(64, 65)));
        }

        @Test
        @DisplayName("length is counted in characters, so 64 supplementary characters are accepted "
                + "even though they occupy 128 UTF-16 code units")
        void shouldCountLengthInCodePointsWhenSupplementaryCharactersPresent() {
            // Given
            var sixtyFourEmoji = "😀".repeat(64);
            assertThat(sixtyFourEmoji.length()).isEqualTo(128);

            // When
            var result = registerAs(UNDERWRITER, sixtyFourEmoji, VALID_LIMIT);

            // Then
            assertThat(result).isEqualTo(accepted());
        }

        @Test
        @DisplayName("65 supplementary characters are refused, with an actual length of 65")
        void shouldRefuseRegistrationWhenSupplementaryCharactersExceed64CodePoints() {
            // Given & When
            var result = registerAs(UNDERWRITER, "😀".repeat(65), VALID_LIMIT);

            // Then
            assertThat(result).isEqualTo(
                    refused(new RegisterSubmissionError.ReferenceTooLong(64, 65)));
        }
    }

    // ================================================================== BR-29

    @Nested
    @DisplayName("A reference may not contain a control character (BR-29)")
    class ControlCharactersInTheReference {

        @Test
        @DisplayName("a reference containing NUL is refused, which is the input that used to be "
                + "accepted here and then fail in the driver as an unexplained 500")
        void shouldRefuseRegistrationWhenReferenceContainsNulByte() {
            // Given & When
            var result = registerAs(UNDERWRITER, "ABC\u0000DEF", VALID_LIMIT);

            // Then
            assertThat(result).isEqualTo(refused(
                    new RegisterSubmissionError.ReferenceContainsControlCharacter(0x0000, 3)));
        }

        @ParameterizedTest(name = "[{index}] codePoint={0}")
        @CsvSource({"9", "10"})
        @DisplayName("a reference consisting of a single TAB or a single NEWLINE is refused, "
                + "reversing the previously confirmed behaviour that accepted both")
        void shouldRefuseRegistrationWhenReferenceIsSingleTabOrNewline(int codePoint) {
            // Given & When
            var result = registerAs(UNDERWRITER, Character.toString(codePoint), VALID_LIMIT);

            // Then
            assertThat(result).isEqualTo(refused(
                    new RegisterSubmissionError.ReferenceContainsControlCharacter(codePoint, 0)));
        }

        @Test
        @DisplayName("a control character in the middle of an otherwise ordinary reference is "
                + "refused, and the reference is refused whole rather than stripped")
        void shouldRefuseRegistrationWhenReferenceContainsControlCharacterInside() {
            // Given & When
            var result = registerAs(UNDERWRITER, "AB\tCD", VALID_LIMIT);

            // Then
            assertThat(result).isEqualTo(refused(
                    new RegisterSubmissionError.ReferenceContainsControlCharacter(0x0009, 2)));
            assertThat(repository.storedSubmissions()).isEmpty();
            assertThat(eventPublisher.publishedEvents()).isEmpty();
        }

        @ParameterizedTest(name = "[{index}] codePoint={0}")
        @CsvSource({
            "0",    // U+0000 — first of the lower range
            "31",   // U+001F — last of the lower range
            "127",  // U+007F — first of the upper range
            "159",  // U+009F — last of the upper range
        })
        @DisplayName("every boundary of both control-character ranges is refused")
        void shouldRefuseRegistrationWhenReferenceHitsControlCharacterBoundaries(int codePoint) {
            // Given & When
            var result = registerAs(UNDERWRITER, "AB" + Character.toString(codePoint) + "CD",
                    VALID_LIMIT);

            // Then
            assertThat(result).isEqualTo(refused(
                    new RegisterSubmissionError.ReferenceContainsControlCharacter(codePoint, 2)));
        }

        @ParameterizedTest(name = "[{index}] codePoint={0}")
        @CsvSource({
            "32",   // U+0020 SPACE — immediately above the lower range, and explicitly valid
            "126",  // U+007E TILDE — immediately below the upper range
            "160",  // U+00A0 NO-BREAK SPACE — immediately above the upper range; category Zs
        })
        @DisplayName("the characters immediately outside both ranges are accepted, so the rule is "
                + "not implemented one code point too wide")
        void shouldAcceptRegistrationWhenCharactersLieImmediatelyOutsideControlRanges(int codePoint) {
            // Given
            var reference = "AB" + Character.toString(codePoint) + "CD";

            // When & Then
            assertThat(registerAs(UNDERWRITER, reference, VALID_LIMIT)).isEqualTo(accepted());
            assertThat(repository.storedSubmission(TENANT_A, reference))
                    .hasValueSatisfying(stored ->
                            assertThat(stored.externalRef()).isEqualTo(reference));
        }

        @Test
        @DisplayName("U+0085 from the upper range is refused even though it is the one control "
                + "character Java's own isWhitespace() disagrees about")
        void shouldRefuseRegistrationWhenReferenceContainsNextLineU0085() {
            // Given & When
            var result = registerAs(UNDERWRITER, "AB\u0085CD", VALID_LIMIT);

            // Then
            assertThat(result).isEqualTo(refused(
                    new RegisterSubmissionError.ReferenceContainsControlCharacter(0x0085, 2)));
        }

        @Test
        @DisplayName("the reported index counts CODE POINTS, not UTF-16 code units, the same way "
                + "BR-05 counts length")
        void shouldReportCodePointIndexWhenControlCharacterFollowsSupplementaryCharacter() {
            // Given & When
            var result = registerAs(UNDERWRITER, "😀\tAB", VALID_LIMIT);

            // Then
            assertThat(result).isEqualTo(refused(
                    new RegisterSubmissionError.ReferenceContainsControlCharacter(0x0009, 1)));
        }

        @Test
        @DisplayName("the first offending character is the one reported, when there is more than "
                + "one")
        void shouldReportFirstOffendingCharacterWhenMultipleControlCharactersExist() {
            // Given & When
            var result = registerAs(UNDERWRITER, "A\tB\nC", VALID_LIMIT);

            // Then
            assertThat(result).isEqualTo(refused(
                    new RegisterSubmissionError.ReferenceContainsControlCharacter(0x0009, 1)));
        }

        @Test
        @DisplayName("a reference that is both too long AND contains a control character is "
                + "refused as too long, because BR-05 is checked before BR-29")
        void shouldPrioritizeLengthCheckOverControlCharacterCheckWhenBothViolated() {
            // Given & When
            var result = registerAs(UNDERWRITER, "A".repeat(64) + "\t", VALID_LIMIT);

            // Then
            assertThat(result).isEqualTo(
                    refused(new RegisterSubmissionError.ReferenceTooLong(64, 65)));
        }

        @Test
        @DisplayName("an empty reference is still refused as missing, not as a control character")
        void shouldPrioritizePresenceCheckOverControlCharacterCheckWhenReferenceIsEmpty() {
            // Given & When
            var result = registerAs(UNDERWRITER, "", VALID_LIMIT);

            // Then
            assertThat(result).isEqualTo(refused(new RegisterSubmissionError.ReferenceMissing()));
        }

        @Test
        @DisplayName("the reference is checked before the insured limit, so a control character "
                + "with an absent amount reports the reference")
        void shouldCheckReferenceBeforeInsuredLimitWhenBothInvalid() {
            // Given & When
            var result = registerAs(UNDERWRITER, "AB\tCD", null);

            // Then
            assertThat(result).isEqualTo(refused(
                    new RegisterSubmissionError.ReferenceContainsControlCharacter(0x0009, 2)));
        }

        @Test
        @DisplayName("the refusal carries no part of the reference itself, so an unprintable "
                + "character cannot reach a log line or a ProblemDetail")
        void shouldEnsureRefusalCarriesOnlyNumericInformationWhenInspected() {
            // Given
            var error = new RegisterSubmissionError.ReferenceContainsControlCharacter(0x000A, 2);

            // When & Then
            assertThat(error.toString()).doesNotContain("\n", "\u0000", "\t");
            var componentTypes = Arrays.stream(
                            RegisterSubmissionError.ReferenceContainsControlCharacter.class
                                    .getRecordComponents())
                    .map(RecordComponent::getType)
                    .toList();
            assertThat(componentTypes).allMatch(type -> type.equals(int.class));
        }
    }

    // ============================================= BR-10, BR-11, BR-12, BR-13

    @Nested
    @DisplayName("The insured limit (BR-10 to BR-14)")
    class InsuredLimit {

        @Test
        @DisplayName("an absent insured limit is refused")
        void shouldRefuseRegistrationWhenInsuredLimitIsAbsent() {
            // Given & When
            var result = registerAs(UNDERWRITER, "ABC-1", null);

            // Then
            assertThat(result).isEqualTo(
                    refused(new RegisterSubmissionError.InsuredLimitMissing()));
        }

        @ParameterizedTest(name = "[{index}] insuredLimit={0}")
        @ValueSource(strings = {"0.01", "1200.00", "0.10", "1000000.00",
                                "99999999999999999999999999999999.00"})
        @DisplayName("a strictly positive amount with exactly two decimal places is accepted, "
                + "with no upper bound (BR-11, BR-12, BR-13)")
        void shouldAcceptRegistrationWhenInsuredLimitIsPositiveWithTwoDecimals(String insuredLimit) {
            // Given & When
            var result = registerAs(UNDERWRITER, "ABC-1", new BigDecimal(insuredLimit));

            // Then
            assertThat(result).isEqualTo(accepted());
        }

        @ParameterizedTest(name = "[{index}] insuredLimit={0}")
        @ValueSource(strings = {"0.00", "-0.01", "-1200.00"})
        @DisplayName("zero and negative amounts are refused, and the reason carries no amount "
                + "because BR-22 forbids showing it")
        void shouldRefuseRegistrationWhenInsuredLimitIsNotPositive(String insuredLimit) {
            // Given & When
            var result = registerAs(UNDERWRITER, "ABC-1", new BigDecimal(insuredLimit));

            // Then
            assertThat(result).isEqualTo(
                    refused(new RegisterSubmissionError.InsuredLimitNotPositive()));
        }

        @Test
        @DisplayName("BigDecimal.ZERO is refused as not positive rather than as imprecise: "
                + "the sign is checked before the number of decimal places")
        void shouldCheckSignBeforePrecisionWhenAmountIsZero() {
            // Given & When
            var result = registerAs(UNDERWRITER, "ABC-1", BigDecimal.ZERO);

            // Then
            assertThat(result).isEqualTo(
                    refused(new RegisterSubmissionError.InsuredLimitNotPositive()));
        }

        @ParameterizedTest(name = "[{index}] insuredLimit={0}, actualScale={1}")
        @CsvSource({"1200, 0", "1200.5, 1", "1200.005, 3", "1200.0000, 4", "0.001, 3"})
        @DisplayName("an amount with any number of decimal places other than two is refused, and "
                + "the reason carries only the two scales — never the amount")
        void shouldRefuseRegistrationWhenInsuredLimitPrecisionIsNotTwo(String insuredLimit, int actualScale) {
            // Given & When
            var result = registerAs(UNDERWRITER, "ABC-1", new BigDecimal(insuredLimit));

            // Then
            assertThat(result).isEqualTo(refused(
                    new RegisterSubmissionError.InsuredLimitPrecisionInvalid(2, actualScale)));
        }

        @Test
        @DisplayName("1200 and 1200.00 are not the same amount: trailing zeros are never stripped "
                + "and the scale is never normalised")
        void shouldDistinguishTrailingZerosWhenPrecisionChecked() {
            // Given & When & Then
            assertThat(registerAs(UNDERWRITER, "ACCEPTED", new BigDecimal("1200.00")))
                    .isEqualTo(accepted());
            assertThat(registerAs(UNDERWRITER, "REFUSED", new BigDecimal("1200")))
                    .isEqualTo(refused(
                            new RegisterSubmissionError.InsuredLimitPrecisionInvalid(2, 0)));
        }

        @Test
        @DisplayName("the amount is stored exactly as supplied, scale included")
        void shouldStoreInsuredLimitExactlyAsSuppliedWithScalePreserved() {
            // Given & When
            registerAs(UNDERWRITER, "ABC-1", new BigDecimal("1200.00"));

            // Then
            assertThat(repository.storedSubmission(TENANT_A, "ABC-1"))
                    .hasValueSatisfying(stored -> {
                        assertThat(stored.insuredLimit()).isEqualTo(new BigDecimal("1200.00"));
                        assertThat(stored.insuredLimit().scale()).isEqualTo(2);
                    });
        }
    }

    // ====================================================== BR-06, BR-08, BR-09

    @Nested
    @DisplayName("A reference is unique within one organisation (BR-06, BR-08, BR-09)")
    class DuplicateReference {

        @Test
        @DisplayName("the same reference in the same organisation is refused as already used")
        void shouldRefuseRegistrationWhenReferenceAlreadyUsedInSameTenant() {
            // Given
            registerAs(UNDERWRITER, "ABC-1", VALID_LIMIT);

            // When
            var second = registerAs(SENIOR_UNDERWRITER, "ABC-1", new BigDecimal("77.00"));

            // Then
            assertThat(second).isEqualTo(
                    refused(new RegisterSubmissionError.ReferenceAlreadyUsed("ABC-1")));
        }

        @Test
        @DisplayName("the earlier submission is left unchanged by the refused duplicate")
        void shouldLeaveEarlierSubmissionUntouchedWhenDuplicateRefused() {
            // Given
            registerAs(UNDERWRITER, "ABC-1", new BigDecimal("1200.00"));

            // When
            registerAs(SENIOR_UNDERWRITER, "ABC-1", new BigDecimal("77.00"));

            // Then
            assertThat(repository.storedCount()).isEqualTo(1);
            assertThat(repository.storedSubmission(TENANT_A, "ABC-1"))
                    .hasValueSatisfying(stored -> {
                        assertThat(stored.insuredLimit()).isEqualTo(new BigDecimal("1200.00"));
                        assertThat(stored.registeredBy()).isEqualTo("uw-01");
                    });
            assertThat(eventPublisher.publishedEvents()).hasSize(1);
        }

        @Test
        @DisplayName("the same reference used by a DIFFERENT organisation is accepted: "
                + "uniqueness never crosses organisations (BR-08)")
        void shouldAcceptSameReferenceWhenRegisteredInDifferentTenant() {
            // Given
            repository.givenAlreadyRegistered(TENANT_B, "ABC-1");

            // When
            var result = registerAs(UNDERWRITER, "ABC-1", VALID_LIMIT);

            // Then
            assertThat(result).isEqualTo(accepted());
        }

        @Test
        @DisplayName("two organisations may each hold the identical reference at the same time")
        void shouldAllowBothOrganizationsToHoldIdenticalReference() {
            // Given & When
            var first = registerAs(UNDERWRITER, "ABC-1", VALID_LIMIT);
            var second = registerAs(UNDERWRITER_OF_OTHER_TENANT, "ABC-1", VALID_LIMIT);

            // Then
            assertThat(first).isEqualTo(accepted());
            assertThat(second).isEqualTo(accepted());
            assertThat(repository.storedCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("when two registrations race for the same reference, exactly one is recorded "
                + "and the other is refused as already used (BR-09)")
        void shouldRecordExactlyOneWhenTwoRegistrationsRaceForSameReference() {
            // Given
            List<Result<SubmissionId, RegisterSubmissionError>> outcomes;

            // When
            try (var pool = Executors.newFixedThreadPool(2)) {
                var first = CompletableFuture.supplyAsync(
                        () -> registerAs(UNDERWRITER, "RACE-1", VALID_LIMIT), pool);
                var second = CompletableFuture.supplyAsync(
                        () -> registerAs(UNDERWRITER, "RACE-1", VALID_LIMIT), pool);
                outcomes = Stream.of(first.join(), second.join()).toList();
            }

            // Then
            assertThat(outcomes).containsExactlyInAnyOrder(
                    accepted(),
                    refused(new RegisterSubmissionError.ReferenceAlreadyUsed("RACE-1")));
            assertThat(repository.storedCount()).isEqualTo(1);
            assertThat(eventPublisher.publishedEvents()).hasSize(1);
        }
    }

    // ================================================================== BR-15

    @Nested
    @DisplayName("What a successful registration records and announces (BR-15)")
    class SuccessfulRegistration {

        @Test
        @DisplayName("the submission belongs to the caller's own organisation, carries the "
                + "supplied reference and amount, names the caller, and carries the moment")
        void shouldRecordSubmissionWithAllDetailsWhenRegistrationSucceeds() {
            // Given & When
            registerAs(UNDERWRITER, "ABC-1", VALID_LIMIT);

            // Then
            assertThat(repository.storedSubmissions()).containsExactly(
                    new SubmissionSnapshot(FixedSubmissionIdGenerator.SUBMISSION_UUID,
                            TENANT_A, "ABC-1", VALID_LIMIT, "uw-01", REGISTERED_AT, null));
        }

        @Test
        @DisplayName("the registration moment comes from the clock, never from the caller")
        void shouldObtainRegistrationMomentFromClockWhenRegistrationSucceeds() {
            // Given & When
            registerAs(UNDERWRITER, "ABC-1", VALID_LIMIT);

            // Then
            assertThat(repository.storedSubmission(TENANT_A, "ABC-1"))
                    .hasValueSatisfying(stored ->
                            assertThat(stored.registeredAt()).isEqualTo(REGISTERED_AT));
        }

        @Test
        @DisplayName("a brand-new submission carries no version yet, which is what tells the "
                + "persistence adapter it has never been stored")
        void shouldLeaveVersionNullWhenNewSubmissionCreated() {
            // Given & When
            registerAs(UNDERWRITER, "ABC-1", VALID_LIMIT);

            // Then
            assertThat(repository.storedSubmission(TENANT_A, "ABC-1"))
                    .hasValueSatisfying(stored -> assertThat(stored.version()).isNull());
        }

        @Test
        @DisplayName("the fact is announced exactly once, carrying everything a downstream system "
                + "needs including the amount")
        void shouldAnnounceSubmissionRegisteredEventWhenRegistrationSucceeds() {
            // Given & When
            registerAs(UNDERWRITER, "ABC-1", VALID_LIMIT);

            // Then
            assertThat(eventPublisher.publishedEvents()).containsExactly(
                    new SubmissionDomainEvent.SubmissionRegistered(
                            FixedSubmissionIdGenerator.EVENT_UUID, EXPECTED_ID, TENANT_A,
                            "ABC-1", VALID_LIMIT, "uw-01", REGISTERED_AT));
        }

        @Test
        @DisplayName("the submission is recorded before the fact is announced, so nothing is "
                + "announced about a row that does not exist")
        void shouldAnnounceNothingWhenStorageFailsOrRefuses() {
            // Given
            repository.givenAlreadyRegistered(TENANT_A, "ABC-1");

            // When
            var result = registerAs(UNDERWRITER, "ABC-1", VALID_LIMIT);

            // Then
            assertThat(result).isEqualTo(
                    refused(new RegisterSubmissionError.ReferenceAlreadyUsed("ABC-1")));
            assertThat(eventPublisher.publishedEvents()).isEmpty();
        }

        @ParameterizedTest(name = "[{index}] role={0}")
        @EnumSource(value = ActorRole.class, names = {"UNDERWRITER", "SENIOR_UNDERWRITER"})
        @DisplayName("the registering caller recorded is the identified caller, for either "
                + "permitted role")
        void shouldRecordIdentifiedCallerWhenEitherPermittedRoleRegisters(ActorRole role) {
            // Given
            var actor = new ActorContext(TENANT_A, "actor-" + role, role);

            // When
            useCase.execute(actor, SubmissionCommandFixtures.parsedCommand("ABC-1", VALID_LIMIT));

            // Then
            assertThat(repository.storedSubmission(TENANT_A, "ABC-1"))
                    .hasValueSatisfying(stored ->
                            assertThat(stored.registeredBy()).isEqualTo("actor-" + role));
        }
    }

    // ============================================ the shape of the error hierarchy

    @Nested
    @DisplayName("The refusal reasons themselves (BR-22)")
    class RefusalReasonShape {

        @Test
        @DisplayName("the domain has exactly the seven business refusal reasons")
        void shouldContainExactlySevenBusinessRefusalReasonsWhenErrorHierarchyInspected() {
            // Given & When
            var variantNames = Arrays.stream(RegisterSubmissionError.class.getPermittedSubclasses())
                    .map(Class::getSimpleName)
                    .sorted()
                    .toList();

            // Then
            assertThat(variantNames).containsExactly(
                    "InsuredLimitMissing",
                    "InsuredLimitNotPositive",
                    "InsuredLimitPrecisionInvalid",
                    "ReferenceAlreadyUsed",
                    "ReferenceContainsControlCharacter",
                    "ReferenceMissing",
                    "ReferenceTooLong");
        }

        @Test
        @DisplayName("NO refusal reason carries the insured limit: an error record is logged and "
                + "serialised, so a value inside one is a value published")
        void shouldNeverCarryInsuredLimitInsideAnyRefusalReason() {
            // Given & When
            var carriedTypes = Arrays.stream(
                            RegisterSubmissionError.class.getPermittedSubclasses())
                    .flatMap(variant -> Arrays.stream(variant.getRecordComponents()))
                    .map(RecordComponent::getType)
                    .toList();

            // Then
            assertThat(carriedTypes)
                    .noneMatch(type -> type.equals(BigDecimal.class) || Number.class.isAssignableFrom(type));
        }

        @Test
        @DisplayName("every refusal reason is a record, so it compares by value")
        void shouldEnsureEveryRefusalReasonIsRecord() {
            // Given & When & Then
            assertThat(RegisterSubmissionError.class.getPermittedSubclasses())
                    .isNotEmpty()
                    .allMatch(Class::isRecord);
        }
    }
}
