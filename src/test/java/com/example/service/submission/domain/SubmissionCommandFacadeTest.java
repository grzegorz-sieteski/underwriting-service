package com.example.service.submission.domain;

import com.example.service.shared.kernel.ActorContext;
import com.example.service.shared.kernel.ActorRole;
import com.example.service.shared.kernel.Result;
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
class SubmissionCommandFacadeTest {

    private static final UUID TENANT_A = UUID.fromString("0a000000-0000-0000-0000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("0b000000-0000-0000-0000-00000000000b");

    private static final Instant REGISTERED_AT = Instant.parse("2026-11-01T10:15:30Z");

    private static final ActorContext UNDERWRITER =
            new ActorContext(TENANT_A, "uw-01", ActorRole.UNDERWRITER);
    private static final ActorContext SENIOR_UNDERWRITER =
            new ActorContext(TENANT_A, "suw-01", ActorRole.SENIOR_UNDERWRITER);
    private static final ActorContext VIEWER =
            new ActorContext(TENANT_A, "viewer-01", ActorRole.VIEWER);
    private static final ActorContext UNDERWRITER_OF_OTHER_TENANT =
            new ActorContext(TENANT_B, "uw-99", ActorRole.UNDERWRITER);

    private static final SubmissionId EXPECTED_ID =
            new SubmissionId(FixedSubmissionIdGenerator.SUBMISSION_UUID);

    private static final BigDecimal VALID_LIMIT = new BigDecimal("1200.00");

    private InMemorySubmissionCommandRepository repository;
    private InMemorySubmissionEventPublisher eventPublisher;
    private SubmissionCommandFacade facade;

    @BeforeEach
    void setUp() {
        repository = new InMemorySubmissionCommandRepository();
        eventPublisher = new InMemorySubmissionEventPublisher();
        facade = new SubmissionCommandFacade(repository, eventPublisher,
                new FixedSubmissionIdGenerator(), Clock.fixed(REGISTERED_AT, ZoneOffset.UTC));
    }

    private Result<SubmissionId, RegisterSubmissionError> registerAs(ActorContext actor,
                                                                     String externalRef,
                                                                     BigDecimal insuredLimit) {
        return ActorContext.callWith(actor,
                () -> facade.register(new RegisterSubmissionCommand(externalRef, insuredLimit)));
    }

    private static Result<SubmissionId, RegisterSubmissionError> accepted() {
        return Result.success(EXPECTED_ID);
    }

    private static Result<SubmissionId, RegisterSubmissionError> refused(
            RegisterSubmissionError reason) {
        return Result.failure(reason);
    }

    // =================================================================== BR-01

    @Nested
    @DisplayName("A caller must be identified first (BR-01)")
    class CallerIdentity {

        @Test
        @DisplayName("registration is refused when no caller is known")
        void unidentified_caller_is_refused() {
            // Given no actor bound at all — exactly what a fail-closed resolver produces
            // When
            var result = facade.register(new RegisterSubmissionCommand("ABC-1", VALID_LIMIT));

            // Then
            assertThat(result).isEqualTo(refused(new RegisterSubmissionError.CallerNotIdentified()));
        }

        @Test
        @DisplayName("an unidentified caller records nothing and announces nothing")
        void unidentified_caller_changes_nothing() {
            facade.register(new RegisterSubmissionCommand("ABC-1", VALID_LIMIT));

            assertThat(repository.storedSubmissions()).isEmpty();
            assertThat(eventPublisher.publishedEvents()).isEmpty();
        }
    }

    // ============================================================ BR-02, BR-03

    @Nested
    @DisplayName("Only an underwriter or senior underwriter may register (BR-02, BR-03)")
    class Role {

        @Test
        @DisplayName("an underwriter may register")
        void underwriter_may_register() {
            var result = registerAs(UNDERWRITER, "ABC-1", VALID_LIMIT);

            assertThat(result).isEqualTo(accepted());
        }

        @Test
        @DisplayName("a senior underwriter may register, and is treated identically: "
                + "no authority limit applies in this feature")
        void senior_underwriter_may_register() {
            var result = registerAs(SENIOR_UNDERWRITER, "ABC-1", new BigDecimal("999999999.99"));

            assertThat(result).isEqualTo(accepted());
        }

        @Test
        @DisplayName("a viewer is refused, and the reason names the role rather than the caller")
        void viewer_may_not_register() {
            var result = registerAs(VIEWER, "ABC-1", VALID_LIMIT);

            assertThat(result).isEqualTo(
                    refused(new RegisterSubmissionError.RoleMayNotRegister(ActorRole.VIEWER)));
        }

        @Test
        @DisplayName("a refused registration records nothing and announces nothing")
        void refusal_records_nothing() {
            registerAs(VIEWER, "ABC-1", VALID_LIMIT);

            assertThat(repository.storedSubmissions()).isEmpty();
            assertThat(eventPublisher.publishedEvents()).isEmpty();
        }
    }

    // ============================================================ BR-04, BR-07

    @Nested
    @DisplayName("A submission reference must be supplied (BR-04, BR-07)")
    class ReferencePresence {

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {""})
        @DisplayName("an absent reference, and one containing no characters at all, are refused")
        void absent_or_empty_reference_is_refused(String externalRef) {
            var result = registerAs(UNDERWRITER, externalRef, VALID_LIMIT);

            assertThat(result).isEqualTo(refused(new RegisterSubmissionError.ReferenceMissing()));
        }

        @ParameterizedTest
        @ValueSource(strings = {" ", "   ", "\t", "\n", " ABC-1", "ABC-1 "})
        @DisplayName("a reference made only of blank space is VALID: BR-04 refuses a reference with "
                + "no characters at all, and BR-07 applies no trimming because none is confirmed")
        void whitespace_only_reference_is_accepted(String externalRef) {
            // This is the counter-intuitive one. isEmpty(), never isBlank(): a reference of "   "
            // contains three characters, so BR-04 does not refuse it, and BR-07 states plainly
            // that references differing in surrounding blank space are DIFFERENT references.
            var result = registerAs(UNDERWRITER, externalRef, VALID_LIMIT);

            assertThat(result).isEqualTo(accepted());
            assertThat(repository.storedSubmission(TENANT_A, externalRef))
                    .hasValueSatisfying(stored ->
                            assertThat(stored.externalRef()).isEqualTo(externalRef));
        }

        @Test
        @DisplayName("references differing only in letter case or surrounding blank space are "
                + "four different references, all four of which register (BR-07)")
        void references_are_compared_exactly_as_supplied() {
            var results = Stream.of(
                    registerAs(UNDERWRITER, "ABC-1", VALID_LIMIT),
                    registerAs(UNDERWRITER, "abc-1", VALID_LIMIT),
                    registerAs(UNDERWRITER, " ABC-1", VALID_LIMIT),
                    registerAs(UNDERWRITER, "ABC-1 ", VALID_LIMIT)).toList();

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
        void reference_of_exactly_the_maximum_is_accepted() {
            var result = registerAs(UNDERWRITER, "A".repeat(64), VALID_LIMIT);

            assertThat(result).isEqualTo(accepted());
        }

        @Test
        @DisplayName("a reference of 65 characters is refused, and the reason carries both lengths")
        void reference_one_over_the_maximum_is_refused() {
            var result = registerAs(UNDERWRITER, "A".repeat(65), VALID_LIMIT);

            assertThat(result).isEqualTo(
                    refused(new RegisterSubmissionError.ReferenceTooLong(64, 65)));
        }

        @Test
        @DisplayName("length is counted in characters, so 64 supplementary characters are accepted "
                + "even though they occupy 128 UTF-16 code units")
        void length_is_counted_in_code_points_not_code_units() {
            // No character-set restriction is confirmed, so a reference may hold supplementary
            // characters. String.length() would call this 128 and refuse it; the business counts
            // characters, which is also what PostgreSQL VARCHAR(64) counts.
            var sixtyFourEmoji = "😀".repeat(64);
            assertThat(sixtyFourEmoji.length()).isEqualTo(128);

            var result = registerAs(UNDERWRITER, sixtyFourEmoji, VALID_LIMIT);

            assertThat(result).isEqualTo(accepted());
        }

        @Test
        @DisplayName("65 supplementary characters are refused, with an actual length of 65")
        void sixty_five_code_points_are_refused() {
            var result = registerAs(UNDERWRITER, "😀".repeat(65), VALID_LIMIT);

            assertThat(result).isEqualTo(
                    refused(new RegisterSubmissionError.ReferenceTooLong(64, 65)));
        }
    }

    // ============================================= BR-10, BR-11, BR-12, BR-13

    @Nested
    @DisplayName("The insured limit (BR-10 to BR-14)")
    class InsuredLimit {

        @Test
        @DisplayName("an absent insured limit is refused")
        void absent_insured_limit_is_refused() {
            var result = registerAs(UNDERWRITER, "ABC-1", null);

            assertThat(result).isEqualTo(
                    refused(new RegisterSubmissionError.InsuredLimitMissing()));
        }

        @ParameterizedTest
        @ValueSource(strings = {"0.01", "1200.00", "0.10", "1000000.00",
                                "99999999999999999999999999999999.00"})
        @DisplayName("a strictly positive amount with exactly two decimal places is accepted, "
                + "with no upper bound (BR-11, BR-12, BR-13)")
        void positive_two_decimal_amounts_are_accepted(String insuredLimit) {
            var result = registerAs(UNDERWRITER, "ABC-1", new BigDecimal(insuredLimit));

            assertThat(result).isEqualTo(accepted());
        }

        @ParameterizedTest
        @ValueSource(strings = {"0.00", "-0.01", "-1200.00"})
        @DisplayName("zero and negative amounts are refused, and the reason carries no amount "
                + "because BR-22 forbids showing it")
        void non_positive_amounts_are_refused(String insuredLimit) {
            var result = registerAs(UNDERWRITER, "ABC-1", new BigDecimal(insuredLimit));

            assertThat(result).isEqualTo(
                    refused(new RegisterSubmissionError.InsuredLimitNotPositive()));
        }

        @Test
        @DisplayName("BigDecimal.ZERO is refused as not positive rather than as imprecise: "
                + "the sign is checked before the number of decimal places")
        void zero_is_not_positive_before_it_is_imprecise() {
            // BigDecimal.ZERO has scale 0, so both BR-11 and BR-12 are violated. The order the
            // rules are checked in decides which reason the caller is told, and it is this one.
            var result = registerAs(UNDERWRITER, "ABC-1", BigDecimal.ZERO);

            assertThat(result).isEqualTo(
                    refused(new RegisterSubmissionError.InsuredLimitNotPositive()));
        }

        @ParameterizedTest
        @CsvSource({"1200, 0", "1200.5, 1", "1200.005, 3", "1200.0000, 4", "0.001, 3"})
        @DisplayName("an amount with any number of decimal places other than two is refused, and "
                + "the reason carries only the two scales — never the amount")
        void wrong_precision_is_refused(String insuredLimit, int actualScale) {
            var result = registerAs(UNDERWRITER, "ABC-1", new BigDecimal(insuredLimit));

            assertThat(result).isEqualTo(refused(
                    new RegisterSubmissionError.InsuredLimitPrecisionInvalid(2, actualScale)));
        }

        @Test
        @DisplayName("1200 and 1200.00 are not the same amount: trailing zeros are never stripped "
                + "and the scale is never normalised")
        void trailing_zeros_are_never_stripped() {
            // stripTrailingZeros() or setScale() anywhere in the pipeline would make BR-12
            // unenforceable, because 1200 and 1200.00 would become indistinguishable.
            assertThat(registerAs(UNDERWRITER, "ACCEPTED", new BigDecimal("1200.00")))
                    .isEqualTo(accepted());
            assertThat(registerAs(UNDERWRITER, "REFUSED", new BigDecimal("1200")))
                    .isEqualTo(refused(
                            new RegisterSubmissionError.InsuredLimitPrecisionInvalid(2, 0)));
        }

        @Test
        @DisplayName("the amount is stored exactly as supplied, scale included")
        void amount_is_stored_exactly_as_supplied() {
            registerAs(UNDERWRITER, "ABC-1", new BigDecimal("1200.00"));

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
        void duplicate_in_same_organisation_is_refused() {
            registerAs(UNDERWRITER, "ABC-1", VALID_LIMIT);

            var second = registerAs(SENIOR_UNDERWRITER, "ABC-1", new BigDecimal("77.00"));

            assertThat(second).isEqualTo(
                    refused(new RegisterSubmissionError.ReferenceAlreadyUsed("ABC-1")));
        }

        @Test
        @DisplayName("the earlier submission is left unchanged by the refused duplicate")
        void earlier_submission_is_unchanged() {
            registerAs(UNDERWRITER, "ABC-1", new BigDecimal("1200.00"));

            registerAs(SENIOR_UNDERWRITER, "ABC-1", new BigDecimal("77.00"));

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
        void same_reference_in_another_organisation_is_accepted() {
            repository.givenAlreadyRegistered(TENANT_B, "ABC-1");

            var result = registerAs(UNDERWRITER, "ABC-1", VALID_LIMIT);

            assertThat(result).isEqualTo(accepted());
        }

        @Test
        @DisplayName("two organisations may each hold the identical reference at the same time")
        void both_organisations_may_hold_the_same_reference() {
            var first = registerAs(UNDERWRITER, "ABC-1", VALID_LIMIT);
            var second = registerAs(UNDERWRITER_OF_OTHER_TENANT, "ABC-1", VALID_LIMIT);

            assertThat(first).isEqualTo(accepted());
            assertThat(second).isEqualTo(accepted());
            assertThat(repository.storedCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("when two registrations race for the same reference, exactly one is recorded "
                + "and the other is refused as already used (BR-09)")
        void racing_registrations_record_exactly_one() {
            // The race must be settled by the store, not by a read-then-write pre-check: a
            // pre-check is exactly the race BR-09 describes, and both attempts would see "free".
            // The outcome below is therefore the same whichever way the two threads interleave.
            List<Result<SubmissionId, RegisterSubmissionError>> outcomes;
            try (var pool = Executors.newFixedThreadPool(2)) {
                var first = CompletableFuture.supplyAsync(
                        () -> registerAs(UNDERWRITER, "RACE-1", VALID_LIMIT), pool);
                var second = CompletableFuture.supplyAsync(
                        () -> registerAs(UNDERWRITER, "RACE-1", VALID_LIMIT), pool);
                outcomes = Stream.of(first.join(), second.join()).toList();
            }

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
        void successful_registration_records_the_submission() {
            registerAs(UNDERWRITER, "ABC-1", VALID_LIMIT);

            assertThat(repository.storedSubmissions()).containsExactly(
                    new SubmissionSnapshot(FixedSubmissionIdGenerator.SUBMISSION_UUID,
                            TENANT_A, "ABC-1", VALID_LIMIT, "uw-01", REGISTERED_AT, null));
        }

        @Test
        @DisplayName("the registration moment comes from the clock, never from the caller")
        void registration_moment_comes_from_the_clock() {
            registerAs(UNDERWRITER, "ABC-1", VALID_LIMIT);

            assertThat(repository.storedSubmission(TENANT_A, "ABC-1"))
                    .hasValueSatisfying(stored ->
                            assertThat(stored.registeredAt()).isEqualTo(REGISTERED_AT));
        }

        @Test
        @DisplayName("a brand-new submission carries no version yet, which is what tells the "
                + "persistence adapter it has never been stored")
        void new_submission_carries_no_version() {
            registerAs(UNDERWRITER, "ABC-1", VALID_LIMIT);

            assertThat(repository.storedSubmission(TENANT_A, "ABC-1"))
                    .hasValueSatisfying(stored -> assertThat(stored.version()).isNull());
        }

        @Test
        @DisplayName("the fact is announced exactly once, carrying everything a downstream system "
                + "needs including the amount")
        void successful_registration_announces_the_fact() {
            registerAs(UNDERWRITER, "ABC-1", VALID_LIMIT);

            assertThat(eventPublisher.publishedEvents()).containsExactly(
                    new SubmissionDomainEvent.SubmissionRegistered(
                            FixedSubmissionIdGenerator.EVENT_UUID, EXPECTED_ID, TENANT_A,
                            "ABC-1", VALID_LIMIT, "uw-01", REGISTERED_AT));
        }

        @Test
        @DisplayName("the submission is recorded before the fact is announced, so nothing is "
                + "announced about a row that does not exist")
        void nothing_is_announced_before_the_row_exists() {
            repository.givenAlreadyRegistered(TENANT_A, "ABC-1");

            var result = registerAs(UNDERWRITER, "ABC-1", VALID_LIMIT);

            assertThat(result).isEqualTo(
                    refused(new RegisterSubmissionError.ReferenceAlreadyUsed("ABC-1")));
            assertThat(eventPublisher.publishedEvents()).isEmpty();
        }

        @ParameterizedTest
        @EnumSource(value = ActorRole.class, names = {"UNDERWRITER", "SENIOR_UNDERWRITER"})
        @DisplayName("the registering caller recorded is the identified caller, for either "
                + "permitted role")
        void registering_caller_is_the_identified_caller(ActorRole role) {
            var actor = new ActorContext(TENANT_A, "actor-" + role, role);

            ActorContext.callWith(actor,
                    () -> facade.register(new RegisterSubmissionCommand("ABC-1", VALID_LIMIT)));

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
        @DisplayName("registration has exactly the eight refusal reasons the specification lists, "
                + "and no ninth")
        void exactly_eight_refusal_reasons_exist() {
            var variantNames = Arrays.stream(RegisterSubmissionError.class.getPermittedSubclasses())
                    .map(Class::getSimpleName)
                    .sorted()
                    .toList();

            assertThat(variantNames).containsExactly(
                    "CallerNotIdentified",
                    "InsuredLimitMissing",
                    "InsuredLimitNotPositive",
                    "InsuredLimitPrecisionInvalid",
                    "ReferenceAlreadyUsed",
                    "ReferenceMissing",
                    "ReferenceTooLong",
                    "RoleMayNotRegister");
        }

        @Test
        @DisplayName("NO refusal reason carries the insured limit: an error record is logged and "
                + "serialised, so a value inside one is a value published")
        void no_refusal_reason_carries_the_insured_limit() {
            // BR-22 forbids the amount in any log, not partially and not rounded, explicitly
            // including the refusals that are ABOUT the amount. A scale is a property of the
            // notation and is allowed; the magnitude is not.
            var carriedTypes = Arrays.stream(
                            RegisterSubmissionError.class.getPermittedSubclasses())
                    .flatMap(variant -> Arrays.stream(variant.getRecordComponents()))
                    .map(RecordComponent::getType)
                    .toList();

            assertThat(carriedTypes).doesNotContain(BigDecimal.class, Number.class);
        }

        @Test
        @DisplayName("every refusal reason is a record, so it compares by value")
        void every_refusal_reason_is_a_record() {
            assertThat(RegisterSubmissionError.class.getPermittedSubclasses())
                    .isNotEmpty()
                    .allMatch(Class::isRecord);
        }
    }
}
