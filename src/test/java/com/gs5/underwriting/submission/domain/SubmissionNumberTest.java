package com.gs5.underwriting.submission.domain;

import com.gs5.underwriting.shared.kernel.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExternalReference#parse}, checked in the spec's order: presence (BR-04), length (BR-05),
 * then category Cc (BR-29). Pure, with no use case and no fake.
 *
 * <p>An accepted value is unwrapped through {@link #accepted}, which asserts success first. Without
 * that guard, comparing two {@code null} stub results would pass vacuously.
 */
class ExternalReferenceTest {

    private static final String EMOJI = "😀";

    private static ExternalReference accepted(String text) {
        var result = ExternalReference.parse(text);
        assertThat(result.isSuccess()).isTrue();
        return result.orElse(null);
    }

    private static Result<ExternalReference, ExternalReferenceError> refused(
            ExternalReferenceError error) {
        return Result.failure(error);
    }

    @ParameterizedTest(name = "reference {0} is missing")
    @NullSource
    @ValueSource(strings = {""})
    @DisplayName("an absent reference, or one with no characters at all, is refused as missing")
    void shouldRefuseAsMissingWhenAbsentOrEmpty(String text) {
        assertThat(ExternalReference.parse(text))
                .isEqualTo(refused(new ExternalReferenceError.Missing()));
    }

    @ParameterizedTest(name = "reference of {0} characters is valid")
    @ValueSource(strings = {" ", "   ", "        "})
    @DisplayName("a reference made only of spaces is valid, because BR-04 refuses only an empty "
            + "reference and SPACE is not a control character")
    void shouldAcceptWhenReferenceIsOnlySpaces(String text) {
        assertThat(accepted(text).value()).isEqualTo(text);
    }

    @ParameterizedTest(name = "reference {0} is kept exactly as supplied")
    @ValueSource(strings = {" a b ", "  a  ", "a b", " ABC-1"})
    @DisplayName("surrounding and internal spaces are preserved exactly (BR-07)")
    void shouldPreserveSpacesExactlyWhenAccepted(String text) {
        assertThat(accepted(text).value()).isEqualTo(text);
    }

    @Test
    @DisplayName("a reference of exactly 64 characters is accepted")
    void shouldAcceptWhenExactly64CodePoints() {
        assertThat(accepted("A".repeat(64)).value()).isEqualTo("A".repeat(64));
    }

    @Test
    @DisplayName("64 emoji, which is 128 UTF-16 units, is accepted: the limit counts characters")
    void shouldAcceptWhenExactly64SupplementaryCharacters() {
        assertThat(accepted(EMOJI.repeat(64)).value()).isEqualTo(EMOJI.repeat(64));
    }

    @Test
    @DisplayName("a reference of 65 characters is refused as too long, reporting both numbers")
    void shouldRefuseAsTooLongWhen65CodePoints() {
        assertThat(ExternalReference.parse("A".repeat(65)))
                .isEqualTo(refused(new ExternalReferenceError.TooLong(64, 65)));
    }

    @Test
    @DisplayName("65 emoji is refused as 65 characters long, not as 130 code units")
    void shouldReportLengthInCodePointsWhenSupplementaryCharactersOverflow() {
        assertThat(ExternalReference.parse(EMOJI.repeat(65)))
                .isEqualTo(refused(new ExternalReferenceError.TooLong(64, 65)));
    }

    @Test
    @DisplayName("length is judged before content: an over-long reference with a TAB is too long")
    void shouldRefuseAsTooLongWhenLengthAndControlCharacterBothFail() {
        assertThat(ExternalReference.parse("A".repeat(64) + "\t"))
                .isEqualTo(refused(new ExternalReferenceError.TooLong(64, 65)));
    }

    @Test
    @DisplayName("a reference containing NUL is refused as a control character, which is the input "
            + "that used to reach the driver and fail as a 500")
    void shouldRefuseWhenReferenceContainsNul() {
        assertThat(ExternalReference.parse("ABC\u0000DEF"))
                .isEqualTo(refused(
                        new ExternalReferenceError.ControlCharacter(0x0000, 3)));
    }

    @Test
    @DisplayName("a TAB inside an ordinary reference is refused, and the reference is not stripped")
    void shouldRefuseWhenReferenceContainsTabInTheMiddle() {
        assertThat(ExternalReference.parse("AB\tCD"))
                .isEqualTo(refused(
                        new ExternalReferenceError.ControlCharacter(0x0009, 2)));
    }

    @Test
    @DisplayName("a reference consisting of a single NEWLINE is refused as a control character, not "
            + "as missing, because it is present")
    void shouldRefuseWhenReferenceIsOnlyNewline() {
        assertThat(ExternalReference.parse("\n"))
                .isEqualTo(refused(
                        new ExternalReferenceError.ControlCharacter(0x000A, 0)));
    }

    @Test
    @DisplayName("U+0085 from the upper range is refused, where whitespace tests would miss it")
    void shouldRefuseWhenReferenceContainsCharacterFromUpperRange() {
        assertThat(ExternalReference.parse("AB\u0085CD"))
                .isEqualTo(refused(
                        new ExternalReferenceError.ControlCharacter(0x0085, 2)));
    }

    @Test
    @DisplayName("U+009F, the last character of the upper range, is refused")
    void shouldRefuseWhenReferenceContainsLastOfUpperRange() {
        assertThat(ExternalReference.parse("A\u009F"))
                .isEqualTo(refused(
                        new ExternalReferenceError.ControlCharacter(0x009F, 1)));
    }

    @Test
    @DisplayName("NO-BREAK SPACE, just above the upper range, is accepted")
    void shouldAcceptWhenReferenceContainsNoBreakSpace() {
        assertThat(accepted("A B").value()).isEqualTo("A B");
    }

    @Test
    @DisplayName("the reported index counts code points, so an emoji before a TAB puts it at 1")
    void shouldReportCodePointIndexWhenSupplementaryCharacterPrecedes() {
        assertThat(ExternalReference.parse(EMOJI + "\tAB"))
                .isEqualTo(refused(
                        new ExternalReferenceError.ControlCharacter(0x0009, 1)));
    }

    @Test
    @DisplayName("two references with the same text are equal, so the value object compares by value")
    void shouldBeEqualWhenTextIsEqual() {
        assertThat(accepted("AB-1")).isEqualTo(accepted("AB-1"));
    }

    @Test
    @DisplayName("references differing only in case are different values (BR-07)")
    void shouldNotBeEqualWhenCaseDiffers() {
        assertThat(accepted("AB-1")).isNotEqualTo(accepted("ab-1"));
    }
}
