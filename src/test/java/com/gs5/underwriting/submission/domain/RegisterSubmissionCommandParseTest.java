package com.gs5.underwriting.submission.domain;

import com.gs5.underwriting.shared.kernel.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RegisterSubmissionCommand#parse}: the register side's mapping of the number's own
 * {@link SubmissionNumberError} onto the three {@link RegisterSubmissionError} number refusals,
 * and the specification's order of judgement — number first, amount second.
 *
 * <p>Only refusals and success are asserted, never a success value's components: the command's
 * components change from raw values to value objects in the implementation, and a test that read
 * them would pin the old shape.
 */
class RegisterSubmissionCommandParseTest {

    private static final BigDecimal VALID_LIMIT = new BigDecimal("1200.00");

    private static Result<RegisterSubmissionCommand, RegisterSubmissionError> refused(
            RegisterSubmissionError error) {
        return Result.failure(error);
    }

    @ParameterizedTest(name = "SC-03: reference {0} maps Missing onto NumberMissing")
    @NullSource
    @ValueSource(strings = {""})
    @DisplayName("SC-03: a missing reference is refused as reference missing (BR-04)")
    void shouldMapMissingWhenReferenceIsAbsentOrEmpty(String reference) {
        assertThat(RegisterSubmissionCommand.parse(reference, VALID_LIMIT))
                .isEqualTo(refused(new RegisterSubmissionError.NumberMissing()));
    }

    @Test
    @DisplayName("SC-08: a too-long reference is refused as reference too long, keeping both numbers (BR-05)")
    void shouldMapTooLongWhenReferenceExceedsMaximum() {
        assertThat(RegisterSubmissionCommand.parse("A".repeat(65), VALID_LIMIT))
                .isEqualTo(refused(new RegisterSubmissionError.NumberTooLong(64, 65)));
    }

    @Test
    @DisplayName("SC-09: a number with a character that is not allowed is refused, keeping the code point "
            + "and its position (BR-29)")
    void shouldMapControlCharacterWhenReferenceContainsOne() {
        assertThat(RegisterSubmissionCommand.parse("AB;CD", VALID_LIMIT))
                .isEqualTo(refused(
                        new RegisterSubmissionError.NumberContainsCharacterNotAllowed(0x003B, 2)));
    }

    @Test
    @DisplayName("SC-13: the reference is judged before the amount: a bad reference with an absent amount is "
            + "refused for the reference")
    void shouldRefuseReferenceBeforeAmountWhenBothAreInvalid() {
        assertThat(RegisterSubmissionCommand.parse("", null))
                .isEqualTo(refused(new RegisterSubmissionError.NumberMissing()));
    }

    @Test
    @DisplayName("SC-13: a character that is not allowed in the number beats a zero amount")
    void shouldRefuseControlCharacterBeforeNonPositiveAmount() {
        assertThat(RegisterSubmissionCommand.parse("AB;CD", BigDecimal.ZERO))
                .isEqualTo(refused(
                        new RegisterSubmissionError.NumberContainsCharacterNotAllowed(0x003B, 2)));
    }

    @Test
    @DisplayName("SC-14: with a valid reference, an absent amount is refused as insured limit missing (BR-10)")
    void shouldRefuseAmountWhenReferenceIsValidAndAmountIsAbsent() {
        assertThat(RegisterSubmissionCommand.parse("AB-1", null))
                .isEqualTo(refused(new RegisterSubmissionError.InsuredLimitMissing()));
    }

    @Test
    @DisplayName("SC-17: with a valid reference, a wrong precision is refused naming the scales only (BR-12)")
    void shouldRefusePrecisionWhenReferenceIsValidAndScaleIsWrong() {
        assertThat(RegisterSubmissionCommand.parse("AB-1", new BigDecimal("1200")))
                .isEqualTo(refused(new RegisterSubmissionError.InsuredLimitPrecisionInvalid(2, 0)));
    }

    @Test
    @DisplayName("SC-01: a valid reference with a valid amount parses to a command")
    void shouldYieldCommandWhenBothInputsAreValid() {
        var parsed = RegisterSubmissionCommand.parse("AB-1", VALID_LIMIT);

        assertThat(parsed.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("SC-04: a number of spaces is refused for registration: a space is not allowed (BR-29)")
    void shouldRefuseWhenNumberIsOnlySpaces() {
        assertThat(RegisterSubmissionCommand.parse("   ", VALID_LIMIT))
                .isEqualTo(refused(
                        new RegisterSubmissionError.NumberContainsCharacterNotAllowed(0x0020, 0)));
    }

    @Test
    @DisplayName("SC-23: every reference error has a register mapping: the reference has exactly three "
            + "failure kinds")
    void shouldHaveExactlyThreeReferenceErrorKinds() {
        var kinds = Arrays.stream(SubmissionNumberError.class.getPermittedSubclasses())
                .map(Class::getSimpleName)
                .sorted()
                .toList();

        assertThat(kinds).containsExactly("CharacterNotAllowed", "Missing", "TooLong");
    }
}
