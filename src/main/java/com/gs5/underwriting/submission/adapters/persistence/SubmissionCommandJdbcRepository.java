package com.gs5.underwriting.submission.adapters.persistence;

import module java.base;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The write statement, and only the write statement.
 *
 * <p>Extends {@link Repository} rather than {@code CrudRepository}: nothing in this feature
 * updates or deletes a submission, and a repository that offers {@code deleteAll()} will
 * eventually be used to delete.
 */
interface SubmissionCommandJdbcRepository extends Repository<SubmissionJdbcEntity, UUID> {

    /**
     * Inserts the row unless this organisation already used the number, and reports which
     * happened as a row count: {@code 1} inserted, {@code 0} the number was already used.
     *
     * <p>{@code ON CONFLICT ... DO NOTHING} plus a row-count check is the right mechanism for
     * BR-09 for three reasons, and it is the same idiom {@code inbox_events} already uses in V1:
     *
     * <ol>
     *   <li><strong>It settles the race in the database.</strong> A {@code SELECT}-then-INSERT
     *       pre-check is exactly the race BR-09 describes; two concurrent registrations would both
     *       see "free" and both insert.</li>
     *   <li><strong>It cannot poison a transaction.</strong> Catching a
     *       {@code DuplicateKeyException} inside a transaction leaves PostgreSQL aborted
     *       ("current transaction is aborted, commands ignored until end of transaction block"),
     *       so every later statement <em>and the commit itself</em> fail. The conditional insert
     *       returns a row count and leaves the session usable.</li>
     *   <li><strong>It needs no exception at all,</strong> which keeps the adapter inside the
     *       zero-throw rule without a {@code catch} that has to decide which
     *       {@code DataAccessException} means what.</li>
     * </ol>
     *
     * <p>{@code version} is written as {@code 0} explicitly: the column is {@code NOT NULL} with
     * no {@code DEFAULT}, deliberately, so that a mapper which forgot to carry it fails loudly.
     */
    @Modifying
    @Query("""
            INSERT INTO submissions
                (id, tenant_id, submission_number, insured_limit, registered_by, registered_at, version)
            VALUES
                (:id, :tenantId, :submissionNumber, :insuredLimit, :registeredBy, :registeredAt, 0)
            ON CONFLICT (tenant_id, submission_number) DO NOTHING
            """)
    int insertIfReferenceFree(@Param("id") UUID id,
                              @Param("tenantId") UUID tenantId,
                              @Param("submissionNumber") String submissionNumber,
                              @Param("insuredLimit") BigDecimal insuredLimit,
                              @Param("registeredBy") String registeredBy,
                              @Param("registeredAt") Instant registeredAt);

    /**
     * Loads one row of this organisation, as the row — not as a projection.
     *
     * <p>No command in this feature loads a submission: this is the read-back half of the snapshot
     * round trip the design requires of the persistence adapter, which is what proves
     * {@code version} survives both directions of the mapper. It is filtered by tenant like every
     * other statement in this module (BR-17).
     */
    @Query("""
            SELECT id, tenant_id, submission_number, insured_limit, registered_by, registered_at, version
            FROM submissions
            WHERE tenant_id = :tenantId
              AND submission_number = :submissionNumber
            """)
    Optional<SubmissionJdbcEntity> findOwnByNumber(@Param("tenantId") UUID tenantId,
                                                  @Param("submissionNumber") String submissionNumber);

    /**
     * The same row, read back by its internal identity. Used only by the snapshot round-trip
     * checks, never by an operation and never exposed: the identity does not leave the service
     * (BR-47).
     */
    @Query("""
            SELECT id, tenant_id, submission_number, insured_limit, registered_by, registered_at, version
            FROM submissions
            WHERE tenant_id = :tenantId
              AND id = :id
            """)
    Optional<SubmissionJdbcEntity> findOwn(@Param("tenantId") UUID tenantId, @Param("id") UUID id);
}
