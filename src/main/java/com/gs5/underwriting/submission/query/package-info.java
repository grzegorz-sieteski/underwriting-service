/**
 * Submission read side: the query useCase, its query record, its single refusal reason, the
 * projection records it returns and the read port that fills them.
 *
 * <p>This package imports nothing from {@code ..submission.domain} — not {@code SubmissionId},
 * not {@code SubmissionSnapshot}. The read side carries raw {@code UUID}. That is enforced by
 * ArchUnit, and by the compiler for the aggregate, which is package-private in the other package.
 */
@NullMarked
package com.gs5.underwriting.submission.query;

import org.jspecify.annotations.NullMarked;
