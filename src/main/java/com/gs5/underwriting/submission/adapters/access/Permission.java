package com.gs5.underwriting.submission.adapters.access;

import com.gs5.underwriting.shared.kernel.Result;

import java.util.Optional;

/**
 * Decides whether a caller may perform one operation, and refuses with the reason it names.
 *
 * <p>Not generic: every permission refuses with {@link AccessRefusal}, which both operations share.
 * Nothing in the type stops {@link #anyIdentifiedCaller()} from producing
 * {@link AccessRefusal.PermissionDenied}, so a test proves it never does (BR-16).
 *
 * <p>Permissions are values, declared once in configuration. The evaluation logic lives here, so no
 * controller or use case contains a role comparison.
 */
public interface Permission {

    /** The caller, if the permission admits it, or the refusal. An absent caller is never admitted. */
    Result<AuthenticatedCaller, AccessRefusal> check(Optional<AuthenticatedCaller> caller);

    /**
     * Search: any identified caller. No role is consulted (BR-16), so the only refusal it produces
     * is {@link AccessRefusal.CallerNotIdentified}.
     *
     * <p><strong>RED stub.</strong> Returns {@code null}; pinned by {@code PermissionMatrixTest}.
     */
    @SuppressWarnings("NullAway")
    static Permission anyIdentifiedCaller() {
        return null;
    }

    /**
     * Register: the caller must be identified and its role must be admitted by {@code policy}. A
     * role the policy does not admit is refused as {@link AccessRefusal.PermissionDenied}.
     *
     * <p>The design names this {@code rolesOnly(first, others...)}. It goes through the
     * {@link AccessPolicy} seam instead, because the list of roles that may register is not
     * decided and must not be written down anywhere yet.
     *
     * <p><strong>RED stub.</strong> Returns {@code null}; pinned by {@code PermissionMatrixTest}
     * and {@code OperationGateTest}.
     */
    @SuppressWarnings("NullAway")
    static Permission registration(AccessPolicy policy) {
        return null;
    }
}
