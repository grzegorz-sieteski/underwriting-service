package com.gs5.underwriting.submission.adapters.access;

import com.gs5.underwriting.shared.kernel.ActorRole;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A test double for the {@link AccessPolicy} seam. It admits exactly the roles a test hands it.
 *
 * <p>This is the only place a role set appears in these tests, and it is supplied per test. No role
 * list belongs to production code yet, so none is pinned here. The tenant method is not exercised:
 * no claim source exists to call it.
 */
final class FixedAccessPolicy implements AccessPolicy {

    private final Set<ActorRole> admitted;

    FixedAccessPolicy(Set<ActorRole> admitted) {
        this.admitted = Set.copyOf(admitted);
    }

    @Override
    public boolean mayRegister(ActorRole role) {
        return admitted.contains(role);
    }

    @Override
    public boolean mayApprove(ActorRole role) {
        return admitted.contains(role);
    }

    @Override
    public Optional<String> tenantIdFrom(Map<String, String> identityClaims) {
        return Optional.empty();
    }
}
