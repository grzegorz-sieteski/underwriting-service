package com.gs5.underwriting.submission.domain;

import module java.base;

import com.gs5.underwriting.shared.kernel.ActorContext;
import org.jspecify.annotations.Nullable;

/**
 * The submission aggregate: <strong>package-private</strong>, with a private constructor and named
 * factories. Nothing outside this package can reach past {@link RegisterSubmissionUseCase}.
 *
 * <p>{@link #register} cannot fail. Every invariant about a submission's input now belongs to the
 * type that owns the value — {@link ExternalReference} and {@link InsuredLimit} — so nothing this
 * factory receives can be invalid. The specification gives a submission no state transitions, no
 * status and no rule relating one field to another, so there is nothing left for the aggregate to
 * protect beyond what its inputs' types already guarantee. The invariants did not disappear; they
 * became unrepresentable.
 *
 * <p>What the aggregate still owns: assigning identity, recording ownership by the caller's
 * organisation (BR-15), stamping the registration moment, producing the snapshot with its version,
 * and producing {@code SubmissionRegistered}. It is the right home for the first rule that does
 * span fields or depend on state.
 */
final class Submission {

    private final SubmissionId id;
    private final UUID tenantId;
    private final String externalRef;
    private final BigDecimal insuredLimit;
    private final String registeredBy;
    private final Instant registeredAt;
    private final @Nullable Long version;

    private Submission(SubmissionId id,
                       UUID tenantId,
                       String externalRef,
                       BigDecimal insuredLimit,
                       String registeredBy,
                       Instant registeredAt,
                       @Nullable Long version) {
        this.id = id;
        this.tenantId = tenantId;
        this.externalRef = externalRef;
        this.insuredLimit = insuredLimit;
        this.registeredBy = registeredBy;
        this.registeredAt = registeredAt;
        this.version = version;
    }

    /**
     * A brand-new submission for the caller's own organisation (BR-15). Total: the reference and
     * the amount arrive already parsed, so there is no refusal left to return.
     *
     * <p>{@code version} is {@code null}, which is what tells the persistence adapter this
     * aggregate has never been stored.
     */
    static Submission register(SubmissionId id,
                               ActorContext actor,
                               ExternalReference externalRef,
                               InsuredLimit insuredLimit,
                               Instant registeredAt) {
        return new Submission(id, actor.tenantId(), externalRef.value(), insuredLimit.amount(),
                actor.actorId(), registeredAt, null);
    }

    /**
     * Rebuilds state loaded from the database, version included.
     *
     * <p>The database constraints mirror the input value-object invariants, so a stored snapshot
     * is already valid and reconstitution is total. Re-parsing persistence output would add a
     * refusal branch for data the schema does not permit.
     */
    static Submission reconstitute(SubmissionSnapshot snapshot) {
        return new Submission(new SubmissionId(snapshot.id()), snapshot.tenantId(),
                snapshot.externalRef(), snapshot.insuredLimit(), snapshot.registeredBy(),
                snapshot.registeredAt(), snapshot.version());
    }

    /** What the aggregate hands a persistence adapter. The version stays exactly as held. */
    SubmissionSnapshot toSnapshot() {
        return new SubmissionSnapshot(id.value(), tenantId, externalRef, insuredLimit,
                registeredBy, registeredAt, version);
    }

    /** BR-15 — the fact that this submission was registered. */
    SubmissionDomainEvent.SubmissionRegistered registeredEvent(UUID eventId) {
        return new SubmissionDomainEvent.SubmissionRegistered(eventId, id, tenantId, externalRef,
                insuredLimit, registeredBy, registeredAt);
    }

    SubmissionId id() {
        return id;
    }
}
