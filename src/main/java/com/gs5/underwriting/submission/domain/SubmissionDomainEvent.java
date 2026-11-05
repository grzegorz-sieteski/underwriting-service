package com.gs5.underwriting.submission.domain;

import module java.base;

import com.gs5.underwriting.shared.kernel.LogSafeText;

/**
 * The business facts this module announces. Every event names its submission by the submission
 * number, the only identifier that leaves this service (BR-47). The internal identity is never
 * carried, so a recipient of an event cannot learn it.
 *
 * <p>Searching announces nothing, because a read is not a business fact, and the event is never
 * raised for a refused operation.
 */
public sealed interface SubmissionDomainEvent {

    /** Stable identity of this announcement, so an eventual recipient can recognise a repeat. */
    UUID eventId();

    /** The submission this fact is about, by its validated submission number (BR-47). */
    SubmissionNumber submissionNumber();

    UUID tenantId();

    Instant occurredAt();

    /**
     * BR-15 — a submission has been registered.
     *
     * <p>The insured limit travels inside the event because downstream systems need the amount;
     * this is not a log, and BR-22 is a rule about logs. Any recipient inherits the obligation.
     */
    record SubmissionRegistered(UUID eventId,
                                SubmissionNumber submissionNumber,
                                UUID tenantId,
                                BigDecimal insuredLimit,
                                String registeredBy,
                                Instant occurredAt) implements SubmissionDomainEvent {

        private static final String MASKED = "***";

        /**
         * BR-22 and OPEN-01: the amount and the registering caller are masked when this event is
         * written to a log, while the record still carries both for a downstream recipient. A
         * serialiser works through the accessors, not through {@code toString()}.
         */
        @Override
        public String toString() {
            return "SubmissionRegistered[eventId=" + eventId
                    + ", submissionNumber=" + LogSafeText.escapeControlCharacters(
                            submissionNumber.value())
                    + ", tenantId=" + tenantId
                    + ", insuredLimit=" + MASKED
                    + ", registeredBy=" + MASKED
                    + ", occurredAt=" + occurredAt + "]";
        }
    }

    /** A submission was approved by a caller permitted to decide (BR-40). */
    record SubmissionApproved(UUID eventId,
                              SubmissionNumber submissionNumber,
                              UUID tenantId,
                              String approvedBy,
                              Instant occurredAt) implements SubmissionDomainEvent {

        private static final String MASKED = "***";

        @Override
        public String toString() {
            return "SubmissionApproved[eventId=" + eventId
                    + ", submissionNumber=" + LogSafeText.escapeControlCharacters(
                            submissionNumber.value())
                    + ", tenantId=" + tenantId
                    + ", approvedBy=" + MASKED
                    + ", occurredAt=" + occurredAt + "]";
        }
    }
}
