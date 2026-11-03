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
     */
    static Permission anyIdentifiedCaller() {
        return Permission::identified;
    }

    /**
     * Register: the caller must be identified and its role must be admitted by {@code policy}. A
     * role the policy does not admit is refused as {@link AccessRefusal.PermissionDenied}.
     *
     * <p>The current configured policy admits underwriters and senior underwriters. Keeping the
     * decision behind {@link AccessPolicy} lets it be changed without moving authorization into a
     * controller or the domain use case.
     */
    static Permission registration(AccessPolicy policy) {
        return caller -> identified(caller)
                .ensure(admitted -> policy.mayRegister(admitted.role()),
                        refused -> new AccessRefusal.PermissionDenied(refused.role()));
    }

    /** Approval: the caller must be identified and admitted by the approval policy. */
    static Permission approval(AccessPolicy policy) {
        return caller -> identified(caller)
                .ensure(admitted -> policy.mayApprove(admitted.role()),
                        refused -> new AccessRefusal.PermissionDenied(refused.role()));
    }

    /** BR-01, fail-closed: an absent caller is refused before any role is consulted. */
    private static Result<AuthenticatedCaller, AccessRefusal> identified(
            Optional<AuthenticatedCaller> caller) {
        return Result.fromOptional(caller, AccessRefusal.CallerNotIdentified::new);
    }
}
