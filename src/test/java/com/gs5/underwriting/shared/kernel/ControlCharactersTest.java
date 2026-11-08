package com.gs5.underwriting.shared.kernel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** The single definition of category Cc, pinned at both edges of both ranges. */
class ControlCharactersTest {

    @ParameterizedTest(name = "SC-10: U+{0} is a control character and is found")
    @ValueSource(ints = {0, 9, 10, 13, 27, 31, 127, 133, 159})
    @DisplayName("SC-10: every character of category Cc is found, at both edges of both ranges")
    void shouldFindControlCharacterWhenTextContainsCategoryCc(int codePoint) {
        // Given
        var text = "A" + Character.toString(codePoint) + "B";

        // When
        var found = ControlCharacters.firstIn(text);

        // Then
        assertThat(found).isEqualTo(Optional.of(new ControlCharacters.Found(codePoint, 1)));
    }

    @ParameterizedTest(name = "SC-10: U+{0} is not a control character and nothing is found")
    @ValueSource(ints = {32, 126, 160, 8232})
    @DisplayName("SC-10: characters just outside both ranges, and the line separator, are not Cc")
    void shouldFindNothingWhenTextHasNoCategoryCc(int codePoint) {
        // Given
        var text = "A" + Character.toString(codePoint) + "B";

        // When
        var found = ControlCharacters.firstIn(text);

        // Then
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("SC-10: nothing is found in empty text")
    void shouldFindNothingWhenTextIsEmpty() {
        assertThat(ControlCharacters.firstIn("")).isEmpty();
    }

    @Test
    @DisplayName("SC-11: the index is counted in code points, so an emoji before a TAB shifts it by one")
    void shouldCountIndexInCodePointsWhenSupplementaryCharacterPrecedes() {
        // Given: one emoji is two UTF-16 units but one code point
        var text = "😀\tAB";

        // When
        var found = ControlCharacters.firstIn(text);

        // Then
        assertThat(found).isEqualTo(Optional.of(new ControlCharacters.Found(9, 1)));
    }

    @Test
    @DisplayName("SC-11: the first offender is the one reported when several are present")
    void shouldReportFirstOffenderWhenSeveralArePresent() {
        var found = ControlCharacters.firstIn("A\tB\n");

        assertThat(found).isEqualTo(Optional.of(new ControlCharacters.Found(9, 1)));
    }
}
