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
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Adversarial probing of the submission write side, added by {@code @qa-explorer}.
 *
 * <p>Nothing here repeats the happy-path suite. Every test is an edge the developer's own tests
 * do not reach: scientific notation (whose {@code BigDecimal} scale is <em>negative</em> and slips
 * past a naive "has a decimal point" check), a scale-2 amount written in a form that looks wrong,
 * an amount with hundreds of significant digits under BR-13's "no upper bound", control
 * characters and a NUL byte in a reference that no confirmed rule forbids, 64 code points made of
 * combining marks, the role gate driven off {@code ActorRole.values()} so a fourth role cannot be
 * added silently, and the BR-09 race widened to 64 virtual threads and across two organisations.
 */
class SubmissionRegistrationAdversarialTest {

    private static final UUID TENANT_A = UUID.fromString("0a000000-0000-0000-0000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("0b000000-0000-0000-0000-00000000000b");

    private static final Instant REGISTERED_AT = Instant.parse("2026-11-01T10:15:30Z");

    private static final BigDecimal VALID_LIMIT = new BigDecimal("1200.00");

    private static final SubmissionId EXPECTED_ID =
            new SubmissionId(FixedSubmissionIdGenerator.SUBMISSION_UUID);

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

    private static ActorContext underwriterOf(UUID tenantId) {
        return new ActorContext(tenantId, "uw-" + tenantId, ActorRole.UNDERWRITER);
    }

    private Result<SubmissionId, RegisterSubmissionError> registerAs(ActorContext caller,
                                                                      String externalRef,
                                                                      BigDecimal insuredLimit) {
        return ActorContext.callWith(caller,
                () -> facade.register(new RegisterSubmissionCommand(externalRef, insuredLimit)));
    }

    private Result<SubmissionId, RegisterSubmissionError> register(String externalRef,
                                                                    String insuredLimit) {
        return registerAs(underwriterOf(TENANT_A), externalRef, new BigDecimal(insuredLimit));
    }

    // ================================================================ amount notation

    @Nested
    @DisplayName("Amount scale, at and past every notational edge (BR-11, BR-12, BR-13)")
    class AmountNotation {

        @ParameterizedTest(name = "[{index}] literal={0}, expectedScale={1}")
        @CsvSource({
            "1.2E+3, -2",
            "1.2E+1, 0",
            "12E+2, -2",
            "1E+10, -10",
            "1200E+0, 0",
        })
        @DisplayName("scientific notation is refused on the SCALE IT ACTUALLY HAS, which is "
                + "NEGATIVE: a check written as 'reject if scale > 2' would accept every one of "
                + "these")
        void shouldRefuseRegistrationWhenScientificNotationHasNegativeScale(String literal,
                                                                           int expectedScale) {
            // Given & When & Then
            assertThat(new BigDecimal(literal).scale()).isEqualTo(expectedScale);
            assertThat(register("SCI-" + literal, literal)).isEqualTo(
                    Result.failure(new RegisterSubmissionError.InsuredLimitPrecisionInvalid(
                            2, expectedScale)));
        }

        @ParameterizedTest(name = "[{index}] literal={0}")
        @ValueSource(strings = {"120000E-2", "1.20000E+3", "0.0120E+2"})
        @DisplayName("an exponent form whose resulting scale IS 2 is accepted, because BR-12 is a "
                + "rule about the scale as supplied and not about how the caller spelled it")
        void shouldAcceptRegistrationWhenExponentFormProducesScaleTwo(String literal) {
            // Given & When & Then
            assertThat(new BigDecimal(literal).scale()).isEqualTo(2);
            assertThat(register("SCI-OK-" + literal, literal)).isEqualTo(Result.success(
                    EXPECTED_ID));
        }

        @Test
        @DisplayName("an amount with 400 significant digits and scale 2 is accepted: BR-13 says "
                + "there is no upper bound and none may be introduced")
        void shouldAcceptRegistrationWhenAmountHas400DigitsWithoutUpperBound() {
            // Given
            var enormous = "9".repeat(400) + ".00";

            // When
            var result = register("HUGE-1", enormous);

            // Then
            assertThat(result).isEqualTo(Result.success(EXPECTED_ID));
            assertThat(repository.storedSubmission(TENANT_A, "HUGE-1"))
                    .get()
                    .satisfies(stored ->
                            assertThat(stored.insuredLimit()).isEqualTo(new BigDecimal(enormous)));
        }

        @Test
        @DisplayName("an amount with an absurd exponent is refused cheaply, on its scale, without "
                + "ever being expanded into digits")
        void shouldRefuseRegistrationWhenExponentIsAbsurdWithoutExpandingDigits() {
            // Given & When
            var result = register("EXP-1", "1E+2000000000");

            // Then
            assertThat(result).isEqualTo(
                    Result.failure(new RegisterSubmissionError.InsuredLimitPrecisionInvalid(
                            2, -2_000_000_000)));
        }

        @ParameterizedTest(name = "[{index}] literal={0}")
        @ValueSource(strings = {"-0.00", "0.00", "-0.01", "-99999999999999999999.00"})
        @DisplayName("zero in any spelling, and every negative amount, is refused as not positive "
                + "and never as imprecise")
        void shouldRefuseRegistrationWhenAmountIsZeroOrNegativeInAnySpelling(String literal) {
            // Given & When
            var result = register("NP-" + literal, literal);

            // Then
            assertThat(result).isEqualTo(
                    Result.failure(new RegisterSubmissionError.InsuredLimitNotPositive()));
        }

        @Test
        @DisplayName("the smallest accepted amount is 0.01, and the amount one step below it is "
                + "refused on its scale rather than on its sign")
        void shouldAcceptSmallestValidAmountAndRefuseBelowOnScale() {
            // Given & When & Then
            assertThat(register("MIN-OK", "0.01")).isEqualTo(Result.success(EXPECTED_ID));
            assertThat(register("MIN-UNDER", "0.001")).isEqualTo(
                    Result.failure(new RegisterSubmissionError.InsuredLimitPrecisionInvalid(2, 3)));
        }
    }

    // ================================================================ reference boundary

    @Nested
    @DisplayName("The 64-code-point reference limit, exactly at and one past (BR-05)")
    class ReferenceLength {

        @Test
        @DisplayName("64 code points occupying 128 UTF-16 chars are accepted: the limit is counted "
                + "in code points, so String.length() would wrongly report 128")
        void shouldAcceptRegistrationWhenReferenceHas64CodePointsIn128Chars() {
            // Given
            var reference = "😀".repeat(64);

            // When & Then
            assertThat(reference.length()).isEqualTo(128);
            assertThat(reference.codePointCount(0, reference.length())).isEqualTo(64);
            assertThat(registerAs(underwriterOf(TENANT_A), reference, VALID_LIMIT))
                    .isEqualTo(Result.success(EXPECTED_ID));
        }

        @Test
        @DisplayName("65 code points occupying 130 UTF-16 chars are refused, and the length "
                + "reported to the caller is 65 and not 130")
        void shouldRefuseRegistrationWhenReferenceHas65CodePoints() {
            // Given
            var reference = "😀".repeat(65);

            // When
            var result = registerAs(underwriterOf(TENANT_A), reference, VALID_LIMIT);

            // Then
            assertThat(result).isEqualTo(Result.failure(
                    new RegisterSubmissionError.ReferenceTooLong(64, 65)));
        }

        @Test
        @DisplayName("64 code points made of base letters plus COMBINING marks are accepted, "
                + "although they render as only 32 visible characters")
        void shouldAcceptRegistrationWhenCombiningMarksTotal64CodePoints() {
            // Given
            var reference = "é".repeat(32);

            // When & Then
            assertThat(reference.codePointCount(0, reference.length())).isEqualTo(64);
            assertThat(registerAs(underwriterOf(TENANT_A), reference, VALID_LIMIT))
                    .isEqualTo(Result.success(EXPECTED_ID));
        }

        @Test
        @DisplayName("33 base-plus-combining pairs is 66 code points and is refused, reported as "
                + "66 rather than as the 33 graphemes the caller sees")
        void shouldRefuseRegistrationWhenCombiningMarksTotal66CodePoints() {
            // Given
            var reference = "é".repeat(33);

            // When
            var result = registerAs(underwriterOf(TENANT_A), reference, VALID_LIMIT);

            // Then
            assertThat(result).isEqualTo(Result.failure(
                    new RegisterSubmissionError.ReferenceTooLong(64, 66)));
        }

        @Test
        @DisplayName("an unpaired surrogate counts as one code point and does not crash the count")
        void shouldCountUnpairedSurrogateAsCodePointWithoutCrashing() {
            // Given
            var reference = "A\uD83DB";

            // When
            var result = registerAs(underwriterOf(TENANT_A), reference, VALID_LIMIT);

            // Then
            assertThat(result).isEqualTo(Result.success(EXPECTED_ID));
        }
    }

    @Nested
    @DisplayName("What a reference may contain — control characters are the ONE restriction "
            + "(BR-04, BR-07, BR-29)")
    class ReferenceCharacterSet {

        @ParameterizedTest(name = "[{index}] reference=\"{0}\"")
        @ValueSource(strings = {" ", "    ", "        "})
        @DisplayName("SPACES alone are a VALID reference: BR-04 refuses a reference with no "
                + "characters, and BR-29 did not touch SPACE because it is not a control character")
        void shouldAcceptRegistrationWhenReferenceConsistsOnlyOfSpaces(String reference) {
            // Given & When & Then
            assertThat(registerAs(underwriterOf(TENANT_A), reference, VALID_LIMIT))
                    .isEqualTo(Result.success(EXPECTED_ID));
        }

        @ParameterizedTest(name = "[{index}] codePoint={0}, index={1}")
        @CsvSource({"9, 0", "10, 0", "13, 0"})
        @DisplayName("a reference of a lone TAB, NEWLINE or CARRIAGE RETURN is now REFUSED, "
                + "reversing what this class previously pinned as valid (BR-29)")
        void shouldRefuseRegistrationWhenReferenceIsLoneControlCharacter(int codePoint, int index) {
            // Given & When & Then
            assertThat(registerAs(underwriterOf(TENANT_A), Character.toString(codePoint),
                    VALID_LIMIT))
                    .isEqualTo(Result.<SubmissionId, RegisterSubmissionError>failure(
                            new RegisterSubmissionError.ReferenceContainsControlCharacter(
                                     codePoint, index)));
        }

        @Test
        @DisplayName("FINDING QA-02, now CLOSED — a reference containing U+0000 is REFUSED by the "
                + "domain, so the input that used to reach a VARCHAR that cannot store it never "
                + "leaves the business core at all")
        void shouldRefuseRegistrationWhenReferenceContainsNulByte() {
            // Given
            var reference = "ABC\u0000DEF";

            // When
            var result = registerAs(underwriterOf(TENANT_A), reference, VALID_LIMIT);

            // Then
            assertThat(result).isEqualTo(Result.<SubmissionId, RegisterSubmissionError>failure(
                    new RegisterSubmissionError.ReferenceContainsControlCharacter(
                            0x0000, 3)));
            assertThat(repository.storedSubmission(TENANT_A, reference)).isEmpty();
            assertThat(repository.storedCount()).isZero();
        }

        @ParameterizedTest(name = "[{index}] reference={0}")
        @ValueSource(strings = {
            "'; DROP TABLE submissions; --",
            "' OR 1=1 --",
            "<script>alert(1)</script>",
            "{{7*7}}",
            "../../etc/passwd",
            "%00",
            "‮exe.txt",
        })
        @DisplayName("an injection-shaped reference is stored verbatim and matches only itself: "
                + "BR-07 forbids normalisation, and every statement binds parameters")
        void shouldStoreInjectionShapedReferencesVerbatimWithoutEvaluation(String reference) {
            // Given & When & Then
            assertThat(registerAs(underwriterOf(TENANT_A), reference, VALID_LIMIT))
                    .isEqualTo(Result.success(EXPECTED_ID));
            assertThat(repository.storedSubmission(TENANT_A, reference))
                    .get()
                    .satisfies(stored -> assertThat(stored.externalRef()).isEqualTo(reference));
        }

        @Test
        @DisplayName("references that differ only by Unicode normal form are DIFFERENT references, "
                + "because BR-07 applies no normalisation of any kind")
        void shouldTreatUnicodeNormalFormsAsDistinctReferencesWithoutFolding() {
            // Given & When
            var first = registerAs(underwriterOf(TENANT_A), "café", VALID_LIMIT);
            var second = registerAs(underwriterOf(TENANT_A), "café", VALID_LIMIT);

            // Then
            assertThat(first.isSuccess()).isTrue();
            assertThat(second.isSuccess()).isTrue();
            assertThat(repository.storedCount()).isEqualTo(2);
        }
    }

    // ================================================================ roles

    @Nested
    @DisplayName("The role gate, driven off the enum rather than off three hand-written cases "
            + "(BR-02, BR-03)")
    class RoleGate {

        @ParameterizedTest(name = "[{index}] role={0}")
        @EnumSource(ActorRole.class)
        @DisplayName("exactly the deciding roles may register, and a role added to the enum after "
                + "this was written must be classified explicitly rather than inherit a default")
        void shouldPermitOnlyDecidingRolesToRegisterWhenExhaustivelyChecked(ActorRole role) {
            // Given
            var result = registerAs(new ActorContext(TENANT_A, "actor-01", role),
                    "ROLE-" + role, VALID_LIMIT);

            // When
            var mayRegister = switch (role) {
                case UNDERWRITER, SENIOR_UNDERWRITER -> true;
                case VIEWER -> false;
            };

            // Then
            if (mayRegister) {
                assertThat(result).isEqualTo(Result.success(EXPECTED_ID));
            } else {
                assertThat(result).isEqualTo(Result.failure(
                        new RegisterSubmissionError.RoleMayNotRegister(role)));
            }
        }

        @Test
        @DisplayName("the feature has exactly the three roles the specification names, and no "
                + "fourth: a fourth would be an invented business concept")
        void shouldContainExactlyThreeRolesInEnum() {
            // Given & When & Then
            assertThat(ActorRole.values()).containsExactly(
                    ActorRole.VIEWER, ActorRole.UNDERWRITER, ActorRole.SENIOR_UNDERWRITER);
        }

        @Test
        @DisplayName("a refused role records nothing and announces nothing, so the refusal is not "
                + "an existence oracle either")
        void shouldRecordNothingAndAnnounceNothingWhenRoleRefused() {
            // Given & When
            registerAs(new ActorContext(TENANT_A, "viewer-01", ActorRole.VIEWER), "V-1",
                    VALID_LIMIT);

            // Then
            assertThat(repository.storedCount()).isZero();
            assertThat(eventPublisher.publishedEvents()).isEmpty();
        }

        @Test
        @DisplayName("the role gate is checked BEFORE any input validation, so a viewer sending "
                + "rubbish learns about their role and nothing about the input rules")
        void shouldCheckRoleGateBeforeInputValidationWhenInvalidPayloadSent() {
            // Given & When
            var result = registerAs(new ActorContext(TENANT_A, "viewer-01", ActorRole.VIEWER),
                    null, null);

            // Then
            assertThat(result).isEqualTo(Result.failure(
                    new RegisterSubmissionError.RoleMayNotRegister(ActorRole.VIEWER)));
        }
    }

    // ================================================================ concurrency

    @Nested
    @DisplayName("The BR-09 race, widened well past two threads")
    class Races {

        @Test
        @DisplayName("64 registrations racing for ONE reference in ONE organisation record exactly "
                + "one row and announce exactly one fact; the other 63 are refused as already used")
        void shouldRecordExactlyOneRowWhen64RegistrationsRaceForSingleReference() {
            // Given
            var caller = underwriterOf(TENANT_A);
            List<Result<SubmissionId, RegisterSubmissionError>> results;

            // When
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                results = IntStream.range(0, 64)
                        .mapToObj(_ -> CompletableFuture.supplyAsync(
                                () -> registerAs(caller, "RACE-64", VALID_LIMIT), pool))
                        .toList()
                        .stream()
                        .map(CompletableFuture::join)
                        .toList();
            }

            // Then
            assertThat(results.stream().filter(Result::isSuccess)).hasSize(1);
            assertThat(results.stream().filter(Result::isFailure)).hasSize(63);
            assertThat(results).allSatisfy(result -> assertThat(result).isIn(
                    Result.success(EXPECTED_ID),
                    Result.failure(new RegisterSubmissionError.ReferenceAlreadyUsed("RACE-64"))));
            assertThat(repository.storedCount()).isEqualTo(1);
            assertThat(eventPublisher.publishedEvents()).hasSize(1);
        }

        @Test
        @DisplayName("the SAME reference racing across TWO organisations records one row EACH: "
                + "uniqueness never crosses an organisation, not even under contention (BR-08)")
        void shouldRecordOneRowPerOrganizationWhenSameReferenceRacesAcrossTwoOrganizations() {
            // Given & When
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var tasks = new ArrayList<CompletableFuture<Result<SubmissionId,
                        RegisterSubmissionError>>>();
                IntStream.range(0, 32).forEach(n -> tasks.add(CompletableFuture.supplyAsync(
                        () -> registerAs(underwriterOf(n % 2 == 0 ? TENANT_A : TENANT_B),
                                "SHARED-REF", VALID_LIMIT),
                        pool)));

                var successes = tasks.stream().map(CompletableFuture::join)
                        .filter(Result::isSuccess)
                        .count();

                // Then
                assertThat(successes).isEqualTo(2L);
            }

            assertThat(repository.storedSubmission(TENANT_A, "SHARED-REF")).isPresent();
            assertThat(repository.storedSubmission(TENANT_B, "SHARED-REF")).isPresent();
            assertThat(repository.storedCount()).isEqualTo(2);
            assertThat(eventPublisher.publishedEvents()).hasSize(2);
        }

        @Test
        @DisplayName("each racing registration is announced at most once, so a refused loser never "
                + "reaches the publisher")
        void shouldNeverAnnounceEventWhenRegistrationLosesRace() {
            // Given
            var caller = underwriterOf(TENANT_A);

            // When
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                IntStream.range(0, 32)
                        .mapToObj(_ -> CompletableFuture.supplyAsync(
                                () -> registerAs(caller, "ANNOUNCE-ONCE", VALID_LIMIT), pool))
                        .toList()
                        .forEach(CompletableFuture::join);
            }

            // Then
            assertThat(eventPublisher.publishedEvents()).hasSize(1);
            assertThat(eventPublisher.publishedEvents())
                    .allSatisfy(event -> assertThat(event.tenantId()).isEqualTo(TENANT_A));
        }

        @Test
        @DisplayName("an actor bound in one virtual thread is never visible from another: a "
                + "ScopedValue binding does not leak across tasks the way a ThreadLocal would")
        void shouldNotLeakScopedValueBindingAcrossVirtualThreadTasks() {
            // Given & When
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                CompletableFuture.supplyAsync(
                        () -> registerAs(underwriterOf(TENANT_A), "BOUND-1", VALID_LIMIT), pool)
                        .join();

                var unbound = CompletableFuture.supplyAsync(
                        () -> facade.register(
                                new RegisterSubmissionCommand("UNBOUND-1", VALID_LIMIT)), pool)
                        .join();

                // Then
                assertThat(unbound).isEqualTo(Result.failure(
                        new RegisterSubmissionError.CallerNotIdentified()));
            }

            assertThat(repository.storedCount()).isEqualTo(1);
        }
    }

    // ================================================================ masking

    @Nested
    @DisplayName("Nothing on the write side discloses the amount or the actor when logged "
            + "(BR-22, OPEN-01)")
    class Masking {

        private static final String SECRET = "7654321.09";
        private static final String ACTOR = "uw-secret-identity";

        @Test
        @DisplayName("every one of the nine write-side refusal reasons, instantiated and printed, "
                + "discloses neither the amount nor the actor")
        void shouldNeverDiscloseAmountOrActorInAnyWriteSideRefusalReason() {
            // Given
            var everyVariant = List.<RegisterSubmissionError>of(
                    new RegisterSubmissionError.CallerNotIdentified(),
                    new RegisterSubmissionError.RoleMayNotRegister(ActorRole.VIEWER),
                    new RegisterSubmissionError.ReferenceMissing(),
                    new RegisterSubmissionError.ReferenceTooLong(64, 65),
                    new RegisterSubmissionError.ReferenceContainsControlCharacter(0x0000, 3),
                    new RegisterSubmissionError.InsuredLimitMissing(),
                    new RegisterSubmissionError.InsuredLimitNotPositive(),
                    new RegisterSubmissionError.InsuredLimitPrecisionInvalid(2, 3),
                    new RegisterSubmissionError.ReferenceAlreadyUsed("S-1"));

            // When & Then
            assertThat(everyVariant).hasSize(
                    RegisterSubmissionError.class.getPermittedSubclasses().length);
            assertThat(everyVariant).allSatisfy(variant ->
                    assertThat(variant.toString())
                            .doesNotContain(SECRET)
                            .doesNotContain(ACTOR));
        }

        @Test
        @DisplayName("the whole pipeline — command, snapshot and event — hides the amount and the "
                + "actor while still carrying both to their legitimate consumers")
        void shouldMaskAmountAndActorAcrossEntirePipelineWhileRetainingValues() {
            // Given
            var caller = new ActorContext(TENANT_A, ACTOR, ActorRole.UNDERWRITER);

            // When
            registerAs(caller, "MASK-1", new BigDecimal(SECRET));

            var command = new RegisterSubmissionCommand("MASK-1", new BigDecimal(SECRET));
            var snapshot = repository.storedSubmission(TENANT_A, "MASK-1").orElseThrow();
            var event = eventPublisher.publishedEvents().getFirst();

            // Then
            assertThat(List.of(command.toString(), snapshot.toString(), event.toString()))
                    .allSatisfy(printed -> assertThat(printed)
                            .doesNotContain(SECRET)
                            .doesNotContain(ACTOR)
                            .contains("MASK-1"));

            assertThat(snapshot.insuredLimit()).isEqualTo(new BigDecimal(SECRET));
            assertThat(snapshot.registeredBy()).isEqualTo(ACTOR);
        }

        @Test
        @DisplayName("the submission id discloses nothing because it carries nothing: it is a "
                + "UUID, and printing it is how audit correlates the row")
        void shouldExposeFullUuidWhenSubmissionIdPrinted() {
            // Given & When & Then
            assertThat(new SubmissionId(FixedSubmissionIdGenerator.SUBMISSION_UUID).toString())
                    .doesNotContain("***")
                    .contains(FixedSubmissionIdGenerator.SUBMISSION_UUID.toString());
        }
    }
}
