package com.gs5.underwriting.submission.adapters.access;

import com.gs5.underwriting.shared.kernel.ActorRole;

import java.util.Map;
import java.util.Optional;

/**
 * The access policy keeps operation permissions and identity-claim extraction at the edge:
 *
 * <ol>
 *   <li><strong>Which roles may register or approve.</strong> Both operations currently allow
 *       underwriters and senior underwriters.</li>
 *   <li><strong>Where the caller's tenant comes from</strong>, i.e. which identity claim carries it.
 *       No identity source or claim name has been configured yet.</li>
 * </ol>
 *
 * <p>Implementations <strong>fail closed</strong>: a role the implementation does not recognise is
 * refused, and a claim set that yields no tenant yields no caller.
 */
public interface AccessPolicy {

    /** A role outside the configured registration policy is refused. */
    boolean mayRegister(ActorRole role);

    /** A role outside the configured approval policy is refused. */
    boolean mayApprove(ActorRole role);

    /**
     * The source of the tenant is not configured. Returns the raw tenant text for parsing, or
     * empty when the claims carry none.
     */
    Optional<String> tenantIdFrom(Map<String, String> identityClaims);
}
