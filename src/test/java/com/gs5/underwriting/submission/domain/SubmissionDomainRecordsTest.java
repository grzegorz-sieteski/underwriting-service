package com.gs5.underwriting.submission.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the write side's records may and may not disclose when they are written to a log.
 *
 * <p>These are the contracts behind BR-22, BR-23 and the strict default applied to the
 * registering caller's identity (OPEN-01). They are tested through {@code toString()} because that
 * is what a log statement calls — and only through {@code toString()}, because a serialiser works
 * through record accessors and the API response and the event payload must still carry the real
 * values. BR-22 is a rule about logs.
 */
class SubmissionDomainRecordsTest {

    /**
     * An id containing no decimal digit at all, deliberately, and never
     * {@code UUID.randomUUID()}.
     *
     * <p>These tests assert that a printed record {@code doesNotContain("1234")}. A random UUID
     * prints 32 hex characters, so it contains "1234" by chance in roughly one run in 2,300 — a
     * test that fails a few times a year for no reason, which is how a suite stops being trusted.
     * A hex id drawn only from a-f cannot collide with a digit substring at all.
     */
    private static final UUID DIGIT_FREE_ID =
            UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    private static final UUID TENANT = UUID.fromString("0a000000-0000-0000-0000-00000000000a");
    private static final BigDecimal AMOUNT = new BigDecimal("1234.56");
    private static final Instant MOMENT = Instant.parse("2026-11-01T10:15:30Z");

    @Nested
    @DisplayName("The insured limit never appears in a log (BR-22)")
    class AmountIsMasked {

        @Test
        @DisplayName("the register command does not disclose the amount, "
                + "not in full and not partially")
        void shouldMaskAmountWhenRegisterSubmissionCommandPrinted() {
            // Given
            var command = SubmissionCommandFixtures.parsedCommand("ABC-1", AMOUNT);

            // When
            var printed = command.toString();

            // Then
            assertThat(printed).doesNotContain("1234.56", "1234", "234.56", "1235");
        }

        @Test
        @DisplayName("the persistence snapshot does not disclose the amount")
        void shouldMaskAmountWhenSubmissionSnapshotPrinted() {
            // Given
            var snapshot = new SubmissionSnapshot(DIGIT_FREE_ID, TENANT, "ABC-1", AMOUNT,
                    "uw-01", MOMENT, null);

            // When
            var printed = snapshot.toString();

            // Then
            assertThat(printed).doesNotContain("1234.56", "1234");
        }

        @Test
        @DisplayName("the registered event does not disclose the amount when logged, although it "
                + "carries it for downstream systems")
        void shouldMaskAmountInToStringWhileRetainingValueInAccessorWhenEventCreated() {
            // Given
            var event = new SubmissionDomainEvent.SubmissionRegistered(DIGIT_FREE_ID,
                    SubmissionNumber.parse("ABC-1").toOptional().orElseThrow(), TENANT, AMOUNT, "uw-01", MOMENT);

            // When
            var printed = event.toString();

            // Then
            assertThat(printed).doesNotContain("1234.56", "1234");
            assertThat(event.insuredLimit()).isEqualTo(AMOUNT);
        }
    }

    @Nested
    @DisplayName("The reference is readable, the registering caller is not")
    class ReferenceAndCaller {

        @Test
        @DisplayName("the reference is written in full and readable, because audit needs it to "
                + "correlate submissions (BR-23)")
        void shouldPreserveSubmissionNumberInToStringWhenAuditReadableRequired() {
            // Given
            var command = SubmissionCommandFixtures.parsedCommand("ABC-1", AMOUNT);
            var snapshot = new SubmissionSnapshot(DIGIT_FREE_ID, TENANT, "ABC-1", AMOUNT,
                    "uw-01", MOMENT, null);

            // When & Then
            assertThat(command.toString()).contains("ABC-1");
            assertThat(snapshot.toString()).contains("ABC-1");
        }

        @Test
        @DisplayName("the registering caller's identity is masked, which is the strict default "
                + "applied while the business has not been asked (OPEN-01)")
        void shouldMaskRegisteringCallerIdentityWhenPrinted() {
            // Given
            var snapshot = new SubmissionSnapshot(DIGIT_FREE_ID, TENANT, "ABC-1", AMOUNT,
                    "uw-01", MOMENT, null);
            var event = new SubmissionDomainEvent.SubmissionRegistered(DIGIT_FREE_ID,
                    SubmissionNumber.parse("ABC-1").toOptional().orElseThrow(), TENANT, AMOUNT, "uw-01", MOMENT);

            // When & Then
            assertThat(snapshot.toString()).doesNotContain("uw-01");
            assertThat(event.toString()).doesNotContain("uw-01");
        }

        @Test
        @DisplayName("the organisation and the registration moment are printed: the organisation "
                + "is an isolation boundary, not a secret")
        void shouldPrintTenantAndMomentWhenSnapshotPrinted() {
            // Given
            var snapshot = new SubmissionSnapshot(DIGIT_FREE_ID, TENANT, "ABC-1", AMOUNT,
                    "uw-01", MOMENT, null);

            // When & Then
            assertThat(snapshot.toString()).contains(TENANT.toString(), MOMENT.toString());
        }
    }
}
