package com.gs5.underwriting.submission.domain;

import com.gs5.underwriting.shared.kernel.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link InsuredLimit#parse}, checked in the spec's order: presence (BR-10), strict positivity
 * (BR-11), then exactly two decimal places (BR-12). No upper bound (BR-13).
 *
 * <p>The sign is checked before the scale, so zero is refused as not positive even though it also
 * has the wrong scale.
 */
class InsuredLimitTest {

    private static InsuredLimit accepted(String literal) {
        var result = InsuredLimit.parse(new BigDecimal(literal));
        assertThat(result.isSuccess()).isTrue();
        return result.orElse(null);
    }

    private static Result<InsuredLimit, RegisterSubmissionError> refused(
            RegisterSubmissionError error) {
        return Result.failure(error);
    }

    @Test
    @DisplayName("an absent insured limit is refused as missing")
    void shouldRefuseAsMissingWhenAbsent() {
        assertThat(InsuredLimit.parse(null))
                .isEqualTo(refused(new RegisterSubmissionError.InsuredLimitMissing()));
    }

    @Test
    @DisplayName("BigDecimal.ZERO is refused as not positive, not as imprecise: the sign is judged "
            + "first")
    void shouldRefuseAsNotPositiveWhenZero() {
        assertThat(InsuredLimit.parse(BigDecimal.ZERO))
                .isEqualTo(refused(new RegisterSubmissionError.InsuredLimitNotPositive()));
    }

    @ParameterizedTest(name = "amount {0} is not positive")
    @ValueSource(strings = {"0.00", "-0.01", "-1200.00", "-99999999999999999999.00"})
    @DisplayName("zero in any spelling, and every negative amount, is refused as not positive, "
            + "carrying no amount (BR-22)")
    void shouldRefuseAsNotPositiveWhenZeroOrNegative(String literal) {
        assertThat(InsuredLimit.parse(new BigDecimal(literal)))
                .isEqualTo(refused(new RegisterSubmissionError.InsuredLimitNotPositive()));
    }

    @ParameterizedTest(name = "amount {0} is accepted")
    @ValueSource(strings = {"0.01", "1200.00", "0.10", "1000000.00"})
    @DisplayName("a strictly positive amount with exactly two decimal places is accepted")
    void shouldAcceptWhenPositiveWithExactlyTwoDecimalPlaces(String literal) {
        assertThat(accepted(literal).amount()).isEqualTo(new BigDecimal(literal));
    }

    @ParameterizedTest(name = "amount {0} has the wrong precision")
    @ValueSource(strings = {"1200", "1200.5", "1200.005", "0.001", "1200.0000"})
    @DisplayName("an amount without exactly two decimal places is refused, naming the two scales "
            + "and never the amount")
    void shouldRefuseAsPrecisionInvalidWhenScaleIsNotTwo(String literal) {
        var expectedScale = new BigDecimal(literal).scale();

        assertThat(InsuredLimit.parse(new BigDecimal(literal)))
                .isEqualTo(refused(
                        new RegisterSubmissionError.InsuredLimitPrecisionInvalid(2, expectedScale)));
    }

    @Test
    @DisplayName("an exponent form has a negative scale and is refused on that scale, so an exponent "
            + "cannot be used to build an enormous number cheaply")
    void shouldRefuseAsPrecisionInvalidWhenExponentFormHasNegativeScale() {
        assertThat(InsuredLimit.parse(new BigDecimal("1E+2")))
                .isEqualTo(refused(new RegisterSubmissionError.InsuredLimitPrecisionInvalid(2, -2)));
    }

    @Test
    @DisplayName("1200 and 1200.00 are different: trailing zeros are never stripped, so the spec "
            + "accepts one and refuses the other")
    void shouldNotStripTrailingZerosWhenJudgingPrecision() {
        assertThat(accepted("1200.00").amount().scale()).isEqualTo(2);
        assertThat(InsuredLimit.parse(new BigDecimal("1200")))
                .isEqualTo(refused(new RegisterSubmissionError.InsuredLimitPrecisionInvalid(2, 0)));
    }

    @Test
    @DisplayName("an amount of 400 digits with scale 2 is accepted: BR-13 has no upper bound")
    void shouldAcceptWhenAmountHasNoUpperBound() {
        var enormous = "9".repeat(400) + ".00";

        assertThat(accepted(enormous).amount()).isEqualTo(new BigDecimal(enormous));
    }

    @Test
    @DisplayName("the smallest accepted amount is 0.01, and the amount one step below it is refused")
    void shouldAcceptSmallestAmountAndRefuseOneStepBelow() {
        assertThat(accepted("0.01").amount()).isEqualTo(new BigDecimal("0.01"));
        assertThat(InsuredLimit.parse(new BigDecimal("0.00")))
                .isEqualTo(refused(new RegisterSubmissionError.InsuredLimitNotPositive()));
    }

    @Test
    @DisplayName("an accepted limit does not print its amount, so it is safe to log by composition")
    void shouldNotPrintAmountWhenLimitIsPrinted() {
        var limit = accepted("1200.00");

        assertThat(limit.toString()).doesNotContain("1200");
    }

    @Test
    @DisplayName("two limits of the same amount are equal, so the value object compares by value")
    void shouldBeEqualWhenAmountIsEqual() {
        assertThat(accepted("1200.00")).isEqualTo(accepted("1200.00"));
    }

    @Test
    @DisplayName("two limits of different amounts are not equal")
    void shouldNotBeEqualWhenAmountDiffers() {
        assertThat(accepted("1200.00")).isNotEqualTo(accepted("1200.01"));
    }
}
