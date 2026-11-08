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
                                                                     String submissionNumber,
                                                                     BigDecimal insuredLimit) {
        return RegisterSubmissionCommand.parse(submissionNumber, insuredLimit)
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

        @ParameterizedTest(name = "SC-03: [{index}] submissionNumber={0}")
        @NullSource
        @ValueSource(strings = {""})
        @DisplayName("SC-03: an absent reference, and one containing no characters at all, are refused")
        void shouldRefuseRegistrationWhenReferenceIsAbsentOrEmpty(String submissionNumber) {
            // Given & When
            var result = registerAs(UNDERWRITER, submissionNumber, VALID_LIMIT);

            // Then
            assertThat(result).isEqualTo(refused(new RegisterSubmissionError.NumberMissing()));
        }

        @ParameterizedTest(name = "SC-04: [{index}] submissionNumber=\"{0}\"")
        @ValueSource(strings = {"ABC-1", "abc-1", "Q-2026-001", "0123456789", "-", "Zz9"})
        @DisplayName("SC-04: a number of ASCII letters, digits and hyphens is valid and stored exactly as "
                + "supplied (BR-29, BR-07)")
        void shouldAcceptRegistrationWhenNumberUsesOnlyAllowedCharacters(String submissionNumber) {
            // Given & When
            var result = registerAs(UNDERWRITER, submissionNumber, VALID_LIMIT);

            // Then
            assertThat(result).isEqualTo(accepted());
            assertThat(repository.storedSubmission(TENANT_A, submissionNumber))
                    .hasValueSatisfying(stored ->
                            assertThat(stored.submissionNumber()).isEqualTo(submissionNumber));
        }

        @Test
        @DisplayName("SC-05: numbers differing only in letter case are different numbers, and both "
                + "register (BR-07)")
        void shouldTreatNumbersAsDistinctWhenDifferingByCase() {
            // Given & When
            var results = Stream.of(
                    registerAs(UNDERWRITER, "ABC-1", VALID_LIMIT),
                    registerAs(UNDERWRITER, "abc-1", VALID_LIMIT),
                    registerAs(UNDERWRITER, "Abc-1", VALID_LIMIT)).toList();

            // Then
            assertThat(results).containsExactly(accepted(), accepted(), accepted());
            assertThat(repository.storedCount()).isEqualTo(3);
        }
    }

    // ================================================================== BR-05

    @Nested
    @DisplayName("A submission number is at most 64 characters (BR-05)")
    class ReferenceLength {

        @Test
        @DisplayName("SC-07: a number of exactly 64 characters is accepted")
        void shouldAcceptRegistrationWhenReferenceIsExactly64Characters() {
            // Given & When
            var result = registerAs(UNDERWRITER, "A".repeat(64), VALID_LIMIT);

            // Then
            assertThat(result).isEqualTo(accepted());
        }

        @Test
        @DisplayName("SC-08: a number of 65 characters is refused, and the reason carries both lengths")
        void shouldRefuseRegistrationWhenReferenceExceeds64Characters() {
            // Given & When
            var result = registerAs(UNDERWRITER, "A".repeat(65), VALID_LIMIT);

            // Then
            assertThat(result).isEqualTo(
                    refused(new RegisterSubmissionError.NumberTooLong(64, 65)));
        }

        @Test
        @DisplayName("SC-08: 65 emoji are refused as too long with an actual length of 65, because "
                + "length is judged before content and counted in characters")
        void shouldRefuseRegistrationWhenSupplementaryCharactersExceed64CodePoints() {
            // Given & When
            var result = registerAs(UNDERWRITER, "😀".repeat(65), VALID_LIMIT);

            // Then
            assertThat(result).isEqualTo(
                    refused(new RegisterSubmissionError.NumberTooLong(64, 65)));
        }
    }

    // ================================================================== BR-29

    @Nested
    @DisplayName("A number contains only ASCII letters, digits and the hyphen (BR-29)")
    class CharactersInTheNumber {

        @ParameterizedTest(name = "SC-09: [{index}] codePoint={0}")
        @CsvSource({
            "0",       // NUL, which used to reach the driver and fail as a 500
            "9",       // TAB
            "10",      // NEWLINE
            "32",      // SPACE, valid under the 2026-11-02 rule, refused since 2026-11-08
            "59",      // ';' — the character that made one number approve another (R-01)
            "47",      // '/'
            "92",      // '\'
            "46",      // '.'
            "37",      // '%'
            "95",      // '_' — just outside the allowed set
            "126",     // '~'
            "133",     // U+0085 NEXT LINE
            "160",     // NO-BREAK SPACE
            "321",     // 'Ł'
            "128512"   // an emoji, reported as one code point
        })
        @DisplayName("SC-09: a number containing any other character is refused whole, naming the "
                + "character by code point and position, and nothing is recorded or announced")
        void shouldRefuseRegistrationWhenNumberContainsCharacterNotAllowed(int codePoint) {
            // Given & When
            var result = registerAs(UNDERWRITER, "AB" + Character.toString(codePoint) + "CD",
                    VALID_LIMIT);

            // Then
            assertThat(result).isEqualTo(refused(
                    new RegisterSubmissionError.NumberContainsCharacterNotAllowed(codePoint, 2)));
            assertThat(repository.storedSubmissions()).isEmpty();
            assertThat(eventPublisher.publishedEvents()).isEmpty();
        }

        @ParameterizedTest(name = "SC-10: [{index}] submissionNumber=\"{0}\"")
        @ValueSource(strings = {" ", "   ", " ABC-1", "ABC-1 ", "AB CD"})
        @DisplayName("SC-10: a number with spaces, including one made only of spaces, is refused: a "
                + "space is not allowed, and nothing is trimmed to make it acceptable")
        void shouldRefuseRegistrationWhenNumberContainsSpaces(String submissionNumber) {
            // Given & When
            var result = registerAs(UNDERWRITER, submissionNumber, VALID_LIMIT);

            // Then
            assertThat(result).isEqualTo(refused(
                    new RegisterSubmissionError.NumberContainsCharacterNotAllowed(
                            0x0020, submissionNumber.indexOf(' '))));
        }

        @Test
        @DisplayName("SC-11: the first character that is not allowed is the one reported")
        void shouldReportFirstCharacterNotAllowedWhenSeveralExist() {
            // Given & When
            var result = registerAs(UNDERWRITER, "A;B/C", VALID_LIMIT);

            // Then
            assertThat(result).isEqualTo(refused(
                    new RegisterSubmissionError.NumberContainsCharacterNotAllowed(0x003B, 1)));
        }

        @Test
        @DisplayName("SC-11: an emoji is reported as one whole code point at its own position, not as "
                + "a UTF-16 half, and the ';' after it is not reported")
        void shouldReportWholeCodePointWhenSupplementaryCharacterIsFirstOffender() {
            // Given & When
            var result = registerAs(UNDERWRITER, "A😀;B", VALID_LIMIT);

            // Then
            assertThat(result).isEqualTo(refused(
                    new RegisterSubmissionError.NumberContainsCharacterNotAllowed(0x1F600, 1)));
        }

        @Test
        @DisplayName("SC-12: a number that is both too long and contains a space is refused as too "
                + "long, because BR-05 is checked before BR-29")
        void shouldPrioritizeLengthCheckOverCharacterCheckWhenBothViolated() {
            // Given & When
            var result = registerAs(UNDERWRITER, "A".repeat(64) + " ", VALID_LIMIT);

            // Then
            assertThat(result).isEqualTo(
                    refused(new RegisterSubmissionError.NumberTooLong(64, 65)));
        }

        @Test
        @DisplayName("SC-12: an empty number is still refused as missing, not as a character problem")
        void shouldPrioritizePresenceCheckOverCharacterCheckWhenNumberIsEmpty() {
            // Given & When
            var result = registerAs(UNDERWRITER, "", VALID_LIMIT);

            // Then
            assertThat(result).isEqualTo(refused(new RegisterSubmissionError.NumberMissing()));
        }

        @Test
        @DisplayName("SC-13: the number is checked before the insured limit, so a character that is "
                + "not allowed with an absent amount reports the number")
        void shouldCheckNumberBeforeInsuredLimitWhenBothInvalid() {
            // Given & When
            var result = registerAs(UNDERWRITER, "AB;CD", null);

            // Then
            assertThat(result).isEqualTo(refused(
                    new RegisterSubmissionError.NumberContainsCharacterNotAllowed(0x003B, 2)));
        }

        @Test
        @DisplayName("SC-24: the refusal carries no part of the number itself, only two integers")
        void shouldEnsureRefusalCarriesOnlyNumericInformationWhenInspected() {
            // Given
            var error = new RegisterSubmissionError.NumberContainsCharacterNotAllowed(0x000A, 2);

            // When & Then
            assertThat(error.toString()).doesNotContain("\n", "\u0000", "\t");
            var componentTypes = Arrays.stream(
                            RegisterSubmissionError.NumberContainsCharacterNotAllowed.class
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
        @DisplayName("SC-14: an absent insured limit is refused")
        void shouldRefuseRegistrationWhenInsuredLimitIsAbsent() {
            // Given & When
            var result = registerAs(UNDERWRITER, "ABC-1", null);

            // Then
            assertThat(result).isEqualTo(
                    refused(new RegisterSubmissionError.InsuredLimitMissing()));
        }

        @ParameterizedTest(name = "SC-19: [{index}] insuredLimit={0}")
        @ValueSource(strings = {"0.01", "1200.00", "0.10", "1000000.00",
                                "99999999999999999999999999999999.00"})
        @DisplayName("SC-19: a strictly positive amount with exactly two decimal places is accepted, "
                + "with no upper bound (BR-11, BR-12, BR-13)")
        void shouldAcceptRegistrationWhenInsuredLimitIsPositiveWithTwoDecimals(String insuredLimit) {
            // Given & When
            var result = registerAs(UNDERWRITER, "ABC-1", new BigDecimal(insuredLimit));

            // Then
            assertThat(result).isEqualTo(accepted());
        }

        @ParameterizedTest(name = "SC-15: [{index}] insuredLimit={0}")
        @ValueSource(strings = {"0.00", "-0.01", "-1200.00"})
        @DisplayName("SC-15: zero and negative amounts are refused, and the reason carries no amount "
                + "because BR-22 forbids showing it")
        void shouldRefuseRegistrationWhenInsuredLimitIsNotPositive(String insuredLimit) {
            // Given & When
            var result = registerAs(UNDERWRITER, "ABC-1", new BigDecimal(insuredLimit));

            // Then
            assertThat(result).isEqualTo(
                    refused(new RegisterSubmissionError.InsuredLimitNotPositive()));
        }

        @Test
        @DisplayName("SC-15: BigDecimal.ZERO is refused as not positive rather than as imprecise: "
                + "the sign is checked before the number of decimal places")
        void shouldCheckSignBeforePrecisionWhenAmountIsZero() {
            // Given & When
            var result = registerAs(UNDERWRITER, "ABC-1", BigDecimal.ZERO);

            // Then
            assertThat(result).isEqualTo(
                    refused(new RegisterSubmissionError.InsuredLimitNotPositive()));
        }

        @ParameterizedTest(name = "SC-17: [{index}] insuredLimit={0}, actualScale={1}")
        @CsvSource({"1200, 0", "1200.5, 1", "1200.005, 3", "1200.0000, 4", "0.001, 3"})
        @DisplayName("SC-17: an amount with any number of decimal places other than two is refused, and "
                + "the reason carries only the two scales — never the amount")
        void shouldRefuseRegistrationWhenInsuredLimitPrecisionIsNotTwo(String insuredLimit, int actualScale) {
            // Given & When
            var result = registerAs(UNDERWRITER, "ABC-1", new BigDecimal(insuredLimit));

            // Then
            assertThat(result).isEqualTo(refused(
                    new RegisterSubmissionError.InsuredLimitPrecisionInvalid(2, actualScale)));
        }

        @Test
        @DisplayName("SC-17: 1200 and 1200.00 are not the same amount: trailing zeros are never stripped "
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
        @DisplayName("SC-19: the amount is stored exactly as supplied, scale included")
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
        @DisplayName("SC-20: the same reference in the same organisation is refused as already used")
        void shouldRefuseRegistrationWhenReferenceAlreadyUsedInSameTenant() {
            // Given
            registerAs(UNDERWRITER, "ABC-1", VALID_LIMIT);

            // When
            var second = registerAs(SENIOR_UNDERWRITER, "ABC-1", new BigDecimal("77.00"));

            // Then
            assertThat(second).isEqualTo(
                    refused(new RegisterSubmissionError.NumberAlreadyUsed("ABC-1")));
        }

        @Test
        @DisplayName("SC-20: the earlier submission is left unchanged by the refused duplicate")
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
        @DisplayName("SC-21: the same reference used by a DIFFERENT organisation is accepted: "
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
        @DisplayName("SC-21: two organisations may each hold the identical reference at the same time")
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
        @DisplayName("SC-22: when two registrations race for the same reference, exactly one is recorded "
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
                    refused(new RegisterSubmissionError.NumberAlreadyUsed("RACE-1")));
            assertThat(repository.storedCount()).isEqualTo(1);
            assertThat(eventPublisher.publishedEvents()).hasSize(1);
        }
    }

    // ================================================================== BR-15

    @Nested
    @DisplayName("What a successful registration records and announces (BR-15)")
    class SuccessfulRegistration {

        @Test
        @DisplayName("SC-02: the submission belongs to the caller's own organisation, carries the "
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
        @DisplayName("SC-02: the registration moment comes from the clock, never from the caller")
        void shouldObtainRegistrationMomentFromClockWhenRegistrationSucceeds() {
            // Given & When
            registerAs(UNDERWRITER, "ABC-1", VALID_LIMIT);

            // Then
            assertThat(repository.storedSubmission(TENANT_A, "ABC-1"))
                    .hasValueSatisfying(stored ->
                            assertThat(stored.registeredAt()).isEqualTo(REGISTERED_AT));
        }

        @Test
        @DisplayName("SC-02: a brand-new submission carries no version yet, which is what tells the "
                + "persistence adapter it has never been stored")
        void shouldLeaveVersionNullWhenNewSubmissionCreated() {
            // Given & When
            registerAs(UNDERWRITER, "ABC-1", VALID_LIMIT);

            // Then
            assertThat(repository.storedSubmission(TENANT_A, "ABC-1"))
                    .hasValueSatisfying(stored -> assertThat(stored.version()).isNull());
        }

        @Test
        @DisplayName("SC-93: the fact is announced exactly once, carrying everything a downstream system "
                + "needs including the amount")
        void shouldAnnounceSubmissionRegisteredEventWhenRegistrationSucceeds() {
            // Given & When
            registerAs(UNDERWRITER, "ABC-1", VALID_LIMIT);

            // Then
            assertThat(eventPublisher.publishedEvents()).containsExactly(
                    new SubmissionDomainEvent.SubmissionRegistered(
                            FixedSubmissionIdGenerator.EVENT_UUID, SubmissionNumber.parse("ABC-1").toOptional().orElseThrow(), TENANT_A,
                            VALID_LIMIT, "uw-01", REGISTERED_AT));
        }

        @Test
        @DisplayName("SC-94: the submission is recorded before the fact is announced, so nothing is "
                + "announced about a row that does not exist")
        void shouldAnnounceNothingWhenStorageFailsOrRefuses() {
            // Given
            repository.givenAlreadyRegistered(TENANT_A, "ABC-1");

            // When
            var result = registerAs(UNDERWRITER, "ABC-1", VALID_LIMIT);

            // Then
            assertThat(result).isEqualTo(
                    refused(new RegisterSubmissionError.NumberAlreadyUsed("ABC-1")));
            assertThat(eventPublisher.publishedEvents()).isEmpty();
        }

        @ParameterizedTest(name = "SC-01: [{index}] role={0}")
        @EnumSource(value = ActorRole.class, names = {"UNDERWRITER", "SENIOR_UNDERWRITER"})
        @DisplayName("SC-01: the registering caller recorded is the identified caller, for either "
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
        @DisplayName("SC-23: the domain has exactly the seven business refusal reasons")
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
                    "NumberAlreadyUsed",
                    "NumberContainsCharacterNotAllowed",
                    "NumberMissing",
                    "NumberTooLong");
        }

        @Test
        @DisplayName("SC-24: NO refusal reason carries the insured limit: an error record is logged and "
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
        @DisplayName("SC-23: every refusal reason is a record, so it compares by value")
        void shouldEnsureEveryRefusalReasonIsRecord() {
            // Given & When & Then
            assertThat(RegisterSubmissionError.class.getPermittedSubclasses())
                    .isNotEmpty()
                    .allMatch(Class::isRecord);
        }
    }
}
