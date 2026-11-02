package com.example.service.submission.domain;

import module java.base;

import org.jspecify.annotations.Nullable;

/**
 * The persistence contract of a submission: what the aggregate hands an adapter, and what an
 * adapter hands back.
 *
 * <p>Public because it crosses the port boundary, unlike the aggregate itself.
 *
 * <p>{@code version} is {@code null} for an aggregate that has never been stored and non-null for
 * one rebuilt from a row. That null is the whole of Spring Data JDBC's "is this entity new?" test
 * for an aggregate whose id is domain-generated, so a mapper that substitutes {@code 0L} for it
 * turns every insert into an UPDATE affecting zero rows — silently.
 *
 * @param version optimistic lock, a plain {@code Long} with no annotation; {@code null} when new
 */
public record SubmissionSnapshot(UUID id,
                                 UUID tenantId,
                                 String externalRef,
                                 BigDecimal insuredLimit,
                                 String registeredBy,
                                 Instant registeredAt,
                                 @Nullable Long version) {

    private static final String MASKED = "***";

    /**
     * BR-22 masks the insured limit; the strict default applied to OPEN-01 masks the registering
     * caller. The reference is printed in full (BR-23), and so are the organisation and the
     * registration moment — the organisation is an isolation boundary, not a secret.
     */
    @Override
    public String toString() {
        return "SubmissionSnapshot[id=" + id
                + ", tenantId=" + tenantId
                + ", externalRef=" + externalRef
                + ", insuredLimit=" + MASKED
                + ", registeredBy=" + MASKED
                + ", registeredAt=" + registeredAt
                + ", version=" + version + "]";
    }
}
