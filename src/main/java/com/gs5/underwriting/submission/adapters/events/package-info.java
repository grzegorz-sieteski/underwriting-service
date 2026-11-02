/**
 * The adapter behind {@code SubmissionEventPublisher}.
 *
 * <p>Its own package, because the design made both the technology and the package a deferred
 * adapter decision. It is deliberately not called {@code messaging}: nothing here reaches a
 * broker, and a package name that promised one would misdescribe an in-process publisher.
 */
@NullMarked
package com.gs5.underwriting.submission.adapters.events;

import org.jspecify.annotations.NullMarked;
