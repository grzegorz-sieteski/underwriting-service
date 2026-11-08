package com.gs5.underwriting.submission.adapters.persistence;

import module java.base;

import org.jspecify.annotations.Nullable;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The two read statements, mapped straight into the projection record. Both read
 * {@code submission_current} (V4), the one database definition of a submission's latest state
 * (BR-45, BR-46), and never the fact tables: a second way of deriving the state could drift from
 * the first.
 *
 * <p>Extends {@link Repository}, not {@code CrudRepository}: a read repository that offers
 * {@code save()} will eventually be used to save.
 *
 * <p>Four details here are easy to get wrong and all four are deliberate:
 *
 * <ul>
 *   <li><strong>Every nullable parameter is wrapped in {@code CAST(... AS type)}.</strong>
 *       PostgreSQL cannot infer the type of a bare parameter used in {@code IS NULL} and fails the
 *       statement with "could not determine data type of parameter". The cast is not cosmetic.</li>
 *   <li><strong>No internal id is selected</strong> (BR-47, review finding R-07). The view does
 *       not even have one, so the read path cannot carry it by accident.</li>
 *   <li><strong>Nothing is concatenated.</strong> Every value is a bound parameter — including
 *       {@code limit} and {@code offset}.</li>
 *   <li><strong>{@code ORDER BY registered_at DESC, submission_number DESC},</strong> not
 *       {@code registered_at} alone. {@code registered_at} is not unique: {@code TIMESTAMPTZ} stores microseconds and
 *       two submissions can share a value. With ties, {@code LIMIT}/{@code OFFSET} paging over a
 *       non-total order lets PostgreSQL return a tied row on two pages or on neither, which breaks
 *       BR-28. The number is unique within a tenant (BR-06), so the order is total; the tie-break
 *       only decides an order the business never specified.</li>
 * </ul>
 *
 * <p>The tenant predicate is {@code tenant_id = :tenantId} with no {@code OR}, in both statements.
 * Another organisation's submission is not returned and not counted, and nothing distinguishes
 * "belongs to someone else" from "does not exist" (BR-17).
 */
interface SubmissionQueryJdbcRepository extends Repository<SubmissionJdbcEntity, UUID> {

    @Query("""
            SELECT submission_number,
                   insured_limit,
                   registered_by,
                   registered_at,
                   approved
            FROM submission_current
            WHERE tenant_id = :tenantId
              AND (CAST(:submissionNumber AS VARCHAR) IS NULL
                   OR submission_number = CAST(:submissionNumber AS VARCHAR))
              AND (CAST(:insuredLimitFrom AS NUMERIC) IS NULL
                   OR insured_limit >= CAST(:insuredLimitFrom AS NUMERIC))
              AND (CAST(:insuredLimitTo AS NUMERIC) IS NULL
                   OR insured_limit <= CAST(:insuredLimitTo AS NUMERIC))
              AND (CAST(:registeredFrom AS TIMESTAMPTZ) IS NULL
                   OR registered_at >= CAST(:registeredFrom AS TIMESTAMPTZ))
              AND (CAST(:registeredTo AS TIMESTAMPTZ) IS NULL
                   OR registered_at <= CAST(:registeredTo AS TIMESTAMPTZ))
            ORDER BY registered_at DESC, submission_number DESC
            LIMIT :limit OFFSET :offset
            """)
    List<SubmissionSummaryJdbcProjection> findPage(
            @Param("tenantId") UUID tenantId,
            @Param("submissionNumber") @Nullable String submissionNumber,
            @Param("insuredLimitFrom") @Nullable BigDecimal insuredLimitFrom,
            @Param("insuredLimitTo") @Nullable BigDecimal insuredLimitTo,
            @Param("registeredFrom") @Nullable Instant registeredFrom,
            @Param("registeredTo") @Nullable Instant registeredTo,
            @Param("limit") int limit,
            @Param("offset") long offset);

    /**
     * Counts the <strong>whole</strong> matching set and ignores which page was asked for (BR-26),
     * which is why it shares its predicate with {@link #findPage} rather than duplicating it in a
     * second shape that could drift.
     */
    @Query("""
            SELECT COUNT(*)
            FROM submission_current
            WHERE tenant_id = :tenantId
              AND (CAST(:submissionNumber AS VARCHAR) IS NULL
                   OR submission_number = CAST(:submissionNumber AS VARCHAR))
              AND (CAST(:insuredLimitFrom AS NUMERIC) IS NULL
                   OR insured_limit >= CAST(:insuredLimitFrom AS NUMERIC))
              AND (CAST(:insuredLimitTo AS NUMERIC) IS NULL
                   OR insured_limit <= CAST(:insuredLimitTo AS NUMERIC))
              AND (CAST(:registeredFrom AS TIMESTAMPTZ) IS NULL
                   OR registered_at >= CAST(:registeredFrom AS TIMESTAMPTZ))
              AND (CAST(:registeredTo AS TIMESTAMPTZ) IS NULL
                   OR registered_at <= CAST(:registeredTo AS TIMESTAMPTZ))
            """)
    long countMatching(
            @Param("tenantId") UUID tenantId,
            @Param("submissionNumber") @Nullable String submissionNumber,
            @Param("insuredLimitFrom") @Nullable BigDecimal insuredLimitFrom,
            @Param("insuredLimitTo") @Nullable BigDecimal insuredLimitTo,
            @Param("registeredFrom") @Nullable Instant registeredFrom,
            @Param("registeredTo") @Nullable Instant registeredTo);
}
