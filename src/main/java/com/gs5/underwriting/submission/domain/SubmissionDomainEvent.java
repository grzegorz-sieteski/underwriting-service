package com.gs5.underwriting.submission.domain;

import module java.base;

import com.gs5.underwriting.shared.kernel.LogSafeText;

/**
 * The business facts this module announces. One today, because the specification names one.
 *
 * <p>Searching announces nothing — a read is not a business fact — and the event is never raised
 * for a refused registration.
 */
public sealed interface SubmissionDomainEvent {

    /** Stable identity of this announcement, so an eventual recipient can recognise a repeat. */
    UUID eventId();

    SubmissionId aggregateId();

    UUID tenantId();

    Instant occurredAt();

    /**
     * BR-15 — a submission has been registered.
     *
     * <p>The insured limit travels inside the event because downstream systems need the amount;
     * this is not a log, and BR-22 is a rule about logs. Any recipient inherits the obligation.
     */
    record SubmissionRegistered(UUID eventId,
                                SubmissionId aggregateId,
                                UUID tenantId,
                                String externalRef,
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
                    + ", aggregateId=" + aggregateId
                    + ", tenantId=" + tenantId
                    + ", externalRef=" + LogSafeText.escapeControlCharacters(externalRef)
                    + ", insuredLimit=" + MASKED
                    + ", registeredBy=" + MASKED
                    + ", occurredAt=" + occurredAt + "]";
        }
    }

    /** A submission was approved by a caller permitted to decide. */
    record SubmissionApproved(UUID eventId,
                             SubmissionId aggregateId,
                             UUID tenantId,
                             String approvedBy,
                             Instant occurredAt) implements SubmissionDomainEvent {

        private static final String MASKED = "***";

        @Override
        public String toString() {
            return "SubmissionApproved[eventId=" + eventId
                    + ", aggregateId=" + aggregateId
                    + ", tenantId=" + tenantId
                    + ", approvedBy=" + MASKED
                    + ", occurredAt=" + occurredAt + "]";
        }
    }
}
