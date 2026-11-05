package com.gs5.underwriting.submission.adapters.persistence;

import module java.base;

import org.jspecify.annotations.Nullable;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The two read statements, mapped straight into the projection record.
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
 *   <li><strong>{@code SELECT id AS submission_id}.</strong> Spring Data JDBC maps result columns
 *       to record components by name; a component called {@code submissionId} does not match a
 *       column called {@code id}. The alias is what makes the projection bind.</li>
 *   <li><strong>Nothing is concatenated.</strong> Every value is a bound parameter — including
 *       {@code limit} and {@code offset}.</li>
 *   <li><strong>{@code ORDER BY registered_at DESC, id DESC},</strong> not {@code registered_at}
 *       alone. {@code registered_at} is not unique: {@code TIMESTAMPTZ} stores microseconds and
 *       two submissions can share a value. With ties, {@code LIMIT}/{@code OFFSET} paging over a
 *       non-total order lets PostgreSQL return a tied row on two pages or on neither, which breaks
 *       BR-28. The tie-break only decides an order the business never specified.</li>
 * </ul>
 *
 * <p>The tenant predicate is {@code tenant_id = :tenantId} with no {@code OR}, in both statements.
 * Another organisation's submission is not returned and not counted, and nothing distinguishes
 * "belongs to someone else" from "does not exist" (BR-17).
 */
interface SubmissionQueryJdbcRepository extends Repository<SubmissionJdbcEntity, UUID> {

    @Query("""
            SELECT s.id AS submission_id,
                   s.submission_number,
                   s.insured_limit,
                   s.registered_by,
                   s.registered_at,
                   (a.submission_id IS NOT NULL) AS approved
            FROM submissions s
            LEFT JOIN submission_approvals a
                   ON a.submission_id = s.id
                  AND a.tenant_id     = s.tenant_id
            WHERE s.tenant_id = :tenantId
              AND (CAST(:submissionNumber AS VARCHAR) IS NULL
                   OR s.submission_number = CAST(:submissionNumber AS VARCHAR))
              AND (CAST(:insuredLimitFrom AS NUMERIC) IS NULL
                   OR s.insured_limit >= CAST(:insuredLimitFrom AS NUMERIC))
              AND (CAST(:insuredLimitTo AS NUMERIC) IS NULL
                   OR s.insured_limit <= CAST(:insuredLimitTo AS NUMERIC))
              AND (CAST(:registeredFrom AS TIMESTAMPTZ) IS NULL
                   OR s.registered_at >= CAST(:registeredFrom AS TIMESTAMPTZ))
              AND (CAST(:registeredTo AS TIMESTAMPTZ) IS NULL
                   OR s.registered_at <= CAST(:registeredTo AS TIMESTAMPTZ))
            ORDER BY s.registered_at DESC, s.id DESC
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
            FROM submissions
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
