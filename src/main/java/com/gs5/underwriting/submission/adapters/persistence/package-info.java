/**
 * Submission persistence: plain Spring Data JDBC with native SQL, no ORM anywhere.
 *
 * <p>Every type here is package-private. The write adapter and the read adapter satisfy ports
 * declared in {@code ..submission.domain} and {@code ..submission.query}, which is the only thing
 * the rest of the module knows about this package.
 */
@NullMarked
package com.gs5.underwriting.submission.adapters.persistence;

import org.jspecify.annotations.NullMarked;
