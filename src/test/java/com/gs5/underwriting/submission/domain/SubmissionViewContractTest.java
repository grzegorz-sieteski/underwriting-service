package com.gs5.underwriting.submission.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A view carries what the store holds, as the store holds it: a {@code String} number and a
 * {@code BigDecimal} amount, not the value objects. A view is output read from our own store, not
 * client input, so parsing it again on the way out would need a branch for a failure the schema
 * already rules out.
 *
 * <p>{@code SubmissionId} is the exception that proves the line. Wrapping a {@code UUID} in it cannot
 * fail, so there is no branch to write, and it stays typed.
 *
 * <p>Asserted by reflection over the record components rather than by constructing a view, so this
 * test does not pin a constructor that the implementation is about to change.
 */
class SubmissionViewContractTest {

    private static Map<String, Class<?>> componentsOf(Class<? extends Record> view) {
        var components = new LinkedHashMap<String, Class<?>>();
        Arrays.stream(view.getRecordComponents())
                .forEach((RecordComponent component) ->
                        components.put(component.getName(), component.getType()));
        return components;
    }

    @Test
    @DisplayName("a summary row carries the submission number and the stored amount, caller and "
            + "moment, and no internal identity (BR-47)")
    void shouldCarryNumberAndStoredPrimitivesButNoIdentityWhenSummaryViewIsRead() {
        assertThat(componentsOf(SubmissionSummaryView.class)).containsExactly(
                Map.entry("submissionNumber", String.class),
                Map.entry("insuredLimit", BigDecimal.class),
                Map.entry("registeredBy", String.class),
                Map.entry("registeredAt", Instant.class),
                Map.entry("state", SubmissionState.class));
    }

    @Test
    @DisplayName("a summary row does not carry the reference or the amount as a value object, because "
            + "re-parsing stored output would need a failure branch the schema rules out")
    void shouldNotCarryValueObjectsWhenSummaryViewIsRead() {
        assertThat(componentsOf(SubmissionSummaryView.class).values())
                .doesNotContain(SubmissionNumber.class, InsuredLimit.class);
    }
}
