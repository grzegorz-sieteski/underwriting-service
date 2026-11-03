package com.gs5.underwriting.submission.adapters.access;

import com.gs5.underwriting.shared.kernel.ActorContext;
import com.gs5.underwriting.shared.kernel.ActorRole;

import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * An identified caller: the organisation and identity from {@link ActorContext}, and the role.
 *
 * <p>The only type that carries a role into the adapter. It never reaches {@code domain/} or
 * {@code query/}, which is how a domain role check becomes a build failure.
 *
 * <p><strong>RED stub.</strong> {@link #parse} returns {@code null}; pinned by
 * {@code AuthenticatedCallerParseTest}.
 */
public final class AuthenticatedCaller {

    private AuthenticatedCaller() {
    }

    /**
     * Builds a caller from raw claims, or empty. The role is matched exactly by constant name over
     * {@link ActorRole#values()}: never {@code valueOf}, which throws, and never case-folded. An
     * unknown role makes the whole result empty. The tenant and actor checks are those of
     * {@link ActorContext#parse}.
     */
    @SuppressWarnings("NullAway")
    public static Optional<AuthenticatedCaller> parse(@Nullable String tenantId,
                                                      @Nullable String actorId,
                                                      @Nullable String role) {
        return null;
    }

    /** The organisation and identity. */
    @SuppressWarnings("NullAway")
    public ActorContext actor() {
        return null;
    }

    /** The role. */
    @SuppressWarnings("NullAway")
    public ActorRole role() {
        return null;
    }
}
