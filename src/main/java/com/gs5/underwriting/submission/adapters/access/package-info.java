/**
 * Who may call which operation, decided at the edge before any input is parsed.
 *
 * <p>Everything in this package is plain Java: no Spring, no framework annotation. The permission
 * rules belong here rather than in {@code domain/} because a permission is a property of the pairing
 * of a caller and an entry point, which the domain cannot know.
 */
@NullMarked
package com.gs5.underwriting.submission.adapters.access;

import org.jspecify.annotations.NullMarked;
