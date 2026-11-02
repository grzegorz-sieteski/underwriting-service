package com.gs5.underwriting.submission.adapters.persistence;

import module java.base;

import com.gs5.underwriting.submission.domain.SubmissionSnapshot;

/**
 * Translates between the domain's persistence contract and the JDBC row.
 *
 * <p><strong>{@code version} is carried in both directions, untouched.</strong> Dropping it from
 * either direction is the single most common bug in this stack, and it only surfaces under
 * concurrency — which is to say in production and not in a unit test:
 *
 * <ul>
 *   <li>{@link #toEntity} passes {@code snapshot.version()} straight through. It does
 *       <strong>not</strong> substitute {@code 0L} for null, because null is what tells Spring
 *       Data JDBC the aggregate has never been stored.</li>
 *   <li>{@link #toSnapshot} passes {@code entity.version()} straight back. It does
 *       <strong>not</strong> drop it.</li>
 * </ul>
 */
final class SubmissionPersistenceMapper {

    private SubmissionPersistenceMapper() {
    }

    static SubmissionJdbcEntity toEntity(SubmissionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new SubmissionJdbcEntity(snapshot.id(), snapshot.tenantId(), snapshot.externalRef(),
                snapshot.insuredLimit(), snapshot.registeredBy(), snapshot.registeredAt(),
                snapshot.version());
    }

    static SubmissionSnapshot toSnapshot(SubmissionJdbcEntity entity) {
        Objects.requireNonNull(entity, "entity");
        return new SubmissionSnapshot(entity.id(), entity.tenantId(), entity.externalRef(),
                entity.insuredLimit(), entity.registeredBy(), entity.registeredAt(),
                entity.version());
    }
}
