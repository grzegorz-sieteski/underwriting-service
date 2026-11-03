package com.gs5.underwriting.submission.adapters.access;

import com.gs5.underwriting.shared.kernel.ActorRole;

import java.util.Map;
import java.util.Optional;

/**
 * <strong>TODO — undecided, do not implement by guessing.</strong> This is the single seam behind
 * which two things are not yet decided:
 *
 * <ol>
 *   <li><strong>Which roles may register.</strong> The list is configured here, in one place, and
 *       nowhere else. No role set is named in this interface or in any test of it.</li>
 *   <li><strong>Where the caller's tenant comes from</strong>, i.e. which identity claim carries it.
 *       No claim name is named in this interface or in any test of it.</li>
 * </ol>
 *
 * <p>Implementations <strong>fail closed</strong>: a role the implementation does not recognise is
 * refused, and a claim set that yields no tenant yields no caller.
 */
public interface AccessPolicy {

    /** TODO — the list of roles permitted to register is undecided. A role not listed is refused. */
    boolean mayRegister(ActorRole role);

    /**
     * TODO — the source of the tenant is undecided. Returns the raw tenant text, which
     * {@link AuthenticatedCaller#parse} then checks, or empty when the claims carry none.
     */
    Optional<String> tenantIdFrom(Map<String, String> identityClaims);
}
