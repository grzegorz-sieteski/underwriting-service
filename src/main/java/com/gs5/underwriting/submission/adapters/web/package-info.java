/**
 * Submission web edge: two controllers, the request and response bodies, and the fail-closed
 * actor resolver.
 *
 * <p>Every type here is package-private. Nothing in this package decides a business rule: the
 * controllers bind the caller, call a useCase, and fold the {@code Result} into a response with an
 * exhaustive {@code switch} and no {@code default} branch.
 */
@NullMarked
package com.gs5.underwriting.submission.adapters.web;

import org.jspecify.annotations.NullMarked;
