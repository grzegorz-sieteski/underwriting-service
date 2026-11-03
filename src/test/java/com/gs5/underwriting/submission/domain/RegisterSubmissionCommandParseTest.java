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
 * {@link RegisterSubmissionCommand#parse}: the register side's mapping of the reference's own
 * {@link ExternalReferenceError} onto the three {@link RegisterSubmissionError} reference refusals,
 * and the specification's order of judgement — reference first, amount second.
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

    @ParameterizedTest(name = "reference {0} maps Missing onto ReferenceMissing")
    @NullSource
    @ValueSource(strings = {""})
    @DisplayName("a missing reference is refused as reference missing (BR-04)")
    void shouldMapMissingWhenReferenceIsAbsentOrEmpty(String reference) {
        assertThat(RegisterSubmissionCommand.parse(reference, VALID_LIMIT))
                .isEqualTo(refused(new RegisterSubmissionError.ReferenceMissing()));
    }

    @Test
    @DisplayName("a too-long reference is refused as reference too long, keeping both numbers (BR-05)")
    void shouldMapTooLongWhenReferenceExceedsMaximum() {
        assertThat(RegisterSubmissionCommand.parse("A".repeat(65), VALID_LIMIT))
                .isEqualTo(refused(new RegisterSubmissionError.ReferenceTooLong(64, 65)));
    }

    @Test
    @DisplayName("a reference with a control character is refused, keeping the code point and its "
            + "index (BR-29)")
    void shouldMapControlCharacterWhenReferenceContainsOne() {
        assertThat(RegisterSubmissionCommand.parse("AB\tCD", VALID_LIMIT))
                .isEqualTo(refused(
                        new RegisterSubmissionError.ReferenceContainsControlCharacter(0x0009, 2)));
    }

    @Test
    @DisplayName("the reference is judged before the amount: a bad reference with an absent amount is "
            + "refused for the reference")
    void shouldRefuseReferenceBeforeAmountWhenBothAreInvalid() {
        assertThat(RegisterSubmissionCommand.parse("", null))
                .isEqualTo(refused(new RegisterSubmissionError.ReferenceMissing()));
    }

    @Test
    @DisplayName("a control character in the reference beats a zero amount")
    void shouldRefuseControlCharacterBeforeNonPositiveAmount() {
        assertThat(RegisterSubmissionCommand.parse("AB\tCD", BigDecimal.ZERO))
                .isEqualTo(refused(
                        new RegisterSubmissionError.ReferenceContainsControlCharacter(0x0009, 2)));
    }

    @Test
    @DisplayName("with a valid reference, an absent amount is refused as insured limit missing (BR-10)")
    void shouldRefuseAmountWhenReferenceIsValidAndAmountIsAbsent() {
        assertThat(RegisterSubmissionCommand.parse("AB-1", null))
                .isEqualTo(refused(new RegisterSubmissionError.InsuredLimitMissing()));
    }

    @Test
    @DisplayName("with a valid reference, a wrong precision is refused naming the scales only (BR-12)")
    void shouldRefusePrecisionWhenReferenceIsValidAndScaleIsWrong() {
        assertThat(RegisterSubmissionCommand.parse("AB-1", new BigDecimal("1200")))
                .isEqualTo(refused(new RegisterSubmissionError.InsuredLimitPrecisionInvalid(2, 0)));
    }

    @Test
    @DisplayName("a valid reference with a valid amount parses to a command")
    void shouldYieldCommandWhenBothInputsAreValid() {
        var parsed = RegisterSubmissionCommand.parse("AB-1", VALID_LIMIT);

        assertThat(parsed.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("a reference of spaces is valid for registration too: BR-29 refuses control "
            + "characters, and SPACE is not one")
    void shouldYieldCommandWhenReferenceIsOnlySpaces() {
        assertThat(RegisterSubmissionCommand.parse("   ", VALID_LIMIT).isSuccess()).isTrue();
    }

    @Test
    @DisplayName("every reference error has a register mapping: the reference has exactly three "
            + "failure kinds")
    void shouldHaveExactlyThreeReferenceErrorKinds() {
        var kinds = Arrays.stream(ExternalReferenceError.class.getPermittedSubclasses())
                .map(Class::getSimpleName)
                .sorted()
                .toList();

        assertThat(kinds).containsExactly("ControlCharacter", "Missing", "TooLong");
    }
}
