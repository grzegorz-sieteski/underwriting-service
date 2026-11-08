package com.gs5.underwriting.submission.domain;

import com.gs5.underwriting.shared.kernel.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SubmissionNumber#parse}, checked in the spec's order: presence (BR-04), length (BR-05),
 * then the allowed characters (BR-29, revision of 2026-11-08: ASCII letters, digits, hyphen).
 * Pure, with no use case and no fake.
 *
 * <p>An accepted value is unwrapped through {@link #accepted}, which asserts success first. Without
 * that guard, comparing two {@code null} skeleton results would pass vacuously.
 */
class SubmissionNumberTest {

    private static SubmissionNumber accepted(String text) {
        var result = SubmissionNumber.parse(text);
        assertThat(result.isSuccess()).isTrue();
        return result.orElse(null);
    }

    private static Result<SubmissionNumber, SubmissionNumberError> refused(
            SubmissionNumberError error) {
        return Result.failure(error);
    }

    @ParameterizedTest(name = "SC-03: number {0} is missing")
    @NullSource
    @ValueSource(strings = {""})
    @DisplayName("SC-03: an absent number, or one with no characters at all, is refused as missing")
    void shouldRefuseAsMissingWhenAbsentOrEmpty(String text) {
        assertThat(SubmissionNumber.parse(text))
                .isEqualTo(refused(new SubmissionNumberError.Missing()));
    }

    @ParameterizedTest(name = "SC-04: number {0} uses only allowed characters and is accepted as given")
    @ValueSource(strings = {"ABC-1", "abc", "0123456789", "Q-2026-001", "-", "a-B-9", "Z"})
    @DisplayName("SC-04: ASCII letters, digits and the hyphen are accepted, exactly as supplied")
    void shouldAcceptWhenOnlyAllowedCharacters(String text) {
        assertThat(accepted(text).value()).isEqualTo(text);
    }

    @Test
    @DisplayName("SC-07: a number of exactly 64 characters is accepted")
    void shouldAcceptWhenExactly64Characters() {
        assertThat(accepted("A".repeat(64)).value()).isEqualTo("A".repeat(64));
    }

    @Test
    @DisplayName("SC-08: a number of 65 characters is refused as too long, reporting both numbers")
    void shouldRefuseAsTooLongWhen65Characters() {
        assertThat(SubmissionNumber.parse("A".repeat(65)))
                .isEqualTo(refused(new SubmissionNumberError.TooLong(64, 65)));
    }

    @Test
    @DisplayName("SC-12: length is judged before content: an over-long number with a space is too long")
    void shouldRefuseAsTooLongWhenLengthAndContentBothFail() {
        assertThat(SubmissionNumber.parse("A".repeat(64) + " "))
                .isEqualTo(refused(new SubmissionNumberError.TooLong(64, 65)));
    }

    @ParameterizedTest(name = "SC-09: U+{1} at index {2} in {0} is not allowed")
    @CsvSource(delimiter = '|', quoteCharacter = '\'', value = {
            "'REF;1'  | 003B | 3",
            "'A/B'    | 002F | 1",
            "'A B'    | 0020 | 1",
            "'   '    | 0020 | 0",
            "' ABC'   | 0020 | 0",
            "'A.1'    | 002E | 1",
            "'..'     | 002E | 0",
            "'A%2F'   | 0025 | 1",
            "'A\\B'   | 005C | 1",
            "'A_B'    | 005F | 1",
            "'A+B'    | 002B | 1",
            "'ŁÓDŹ'   | 0141 | 0",
            "'AÉ'     | 00C9 | 1",
            "'A B' | 00A0 | 1"
    })
    @DisplayName("SC-09: any character other than an ASCII letter, digit or hyphen is refused, and "
            + "the refusal names the first such character and its position")
    void shouldRefuseWhenCharacterIsNotAllowed(String text, String hexCodePoint, int index) {
        assertThat(SubmissionNumber.parse(text))
                .isEqualTo(refused(new SubmissionNumberError.CharacterNotAllowed(
                        Integer.parseInt(hexCodePoint, 16), index)));
    }

    @ParameterizedTest(name = "SC-09: control character U+{1} at index {2} is not allowed")
    @CsvSource(delimiter = '|', value = {
            "0000 | 3",
            "0009 | 3",
            "000A | 3",
            "007F | 3",
            "0085 | 3",
            "009F | 3"
    })
    @DisplayName("SC-09: control characters are refused as characters that are not allowed, NUL "
            + "included, which is the input that used to reach the driver and fail as a 500")
    void shouldRefuseWhenControlCharacter(String hexCodePoint, int index) {
        var codePoint = Integer.parseInt(hexCodePoint, 16);
        assertThat(SubmissionNumber.parse("ABC" + Character.toString(codePoint) + "DEF"))
                .isEqualTo(refused(new SubmissionNumberError.CharacterNotAllowed(codePoint, index)));
    }

    @Test
    @DisplayName("SC-11: an emoji is refused, reported by its code point, not by a UTF-16 half")
    void shouldReportWholeCodePointWhenSupplementaryCharacter() {
        assertThat(SubmissionNumber.parse("AB😀"))
                .isEqualTo(refused(new SubmissionNumberError.CharacterNotAllowed(0x1F600, 2)));
    }

    @Test
    @DisplayName("SC-11: only the first character that is not allowed is reported")
    void shouldReportFirstCharacterNotAllowed() {
        assertThat(SubmissionNumber.parse("AB;C/D"))
                .isEqualTo(refused(new SubmissionNumberError.CharacterNotAllowed(0x003B, 2)));
    }

    @Test
    @DisplayName("SC-05: two numbers with the same text are equal, so the value object compares by value")
    void shouldBeEqualWhenTextIsEqual() {
        assertThat(accepted("AB-1")).isEqualTo(accepted("AB-1"));
    }

    @Test
    @DisplayName("SC-05: numbers differing only in case are different values (BR-07)")
    void shouldNotBeEqualWhenCaseDiffers() {
        assertThat(accepted("AB-1")).isNotEqualTo(accepted("ab-1"));
    }
}
