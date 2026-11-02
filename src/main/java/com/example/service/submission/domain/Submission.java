package com.example.service.submission.domain;

import module java.base;

import com.example.service.shared.kernel.ActorRole;
import com.example.service.shared.kernel.Result;
import org.jspecify.annotations.Nullable;

/**
 * The submission aggregate: <strong>package-private</strong>, with a private constructor and named
 * factories. Nothing outside this package can reach past {@link SubmissionCommandFacade}.
 *
 * <p>Every invariant the specification states about registering a submission is checked in
 * {@link #register} and nowhere else. There are deliberately <em>no state transitions and no
 * status field</em>: the specification puts every lifecycle state beyond "registered" out of
 * scope, and a {@code status} added "for later" would be an invented business concept.
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
     * A brand-new submission for the identified caller's own organisation (BR-15), or the first
     * invariant it violates.
     *
     * <p>The seven invariants are checked in the order the design fixes, and the order is
     * observable: {@code BigDecimal.ZERO} violates both BR-11 and BR-12, and the caller is told
     * {@link RegisterSubmissionError.InsuredLimitNotPositive} because the sign is checked first.
     * A reference that is both too long and contains a control character is told it is too long,
     * because BR-05 is checked before BR-29.
     *
     * <p>{@code version} is {@code null}, which is what tells the persistence adapter this
     * aggregate has never been stored.
     */
    static Result<Submission, RegisterSubmissionError> register(SubmissionId id,
                                                                UUID tenantId,
                                                                String actorId,
                                                                ActorRole role,
                                                                @Nullable String externalRef,
                                                                @Nullable BigDecimal insuredLimit,
                                                                Instant registeredAt) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(registeredAt, "registeredAt");

        return firstViolation(role, externalRef, insuredLimit)
                .<Result<Submission, RegisterSubmissionError>>map(Result::failure)
                .orElseGet(() -> Result.success(new Submission(id, tenantId,
                        Objects.requireNonNull(externalRef),
                        Objects.requireNonNull(insuredLimit),
                        actorId, registeredAt, null)));
    }

    /**
     * Rebuilds state loaded from the database, version included, and re-validates nothing: a row
     * that is already stored was validated when it was registered.
     *
     * <p>This has no production caller in this feature — a submission is immutable once
     * registered and no command loads one. It is half of the snapshot contract: the persistence
     * round trip loads the inserted row back through the mapper and reconstitutes it, which is
     * what proves {@code version} survives both directions.
     */
    static Submission reconstitute(SubmissionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
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
        Objects.requireNonNull(eventId, "eventId");
        return new SubmissionDomainEvent.SubmissionRegistered(eventId, id, tenantId, externalRef,
                insuredLimit, registeredBy, registeredAt);
    }

    SubmissionId id() {
        return id;
    }

    // ------------------------------------------------------------------ invariants

    /**
     * The first invariant violated, in the order the design fixes, or empty when all seven hold.
     *
     * <p>The stream is lazy and {@code findFirst} short-circuits, so a later check never runs
     * against input an earlier one already refused — which is what makes it safe for the length
     * and control-character checks to read a reference the presence check has already accepted.
     *
     * <p>Three things about this order are argued in the design and pinned by tests, so none of
     * them is free to move:
     *
     * <ul>
     *   <li><strong>Role first,</strong> so an unauthorised caller never learns which field of the
     *       input is malformed.</li>
     *   <li><strong>Presence before shape,</strong> because {@code ""} trivially contains no
     *       control character: a BR-29 check placed earlier would pass on an empty reference and
     *       then report the real defect later and less clearly.</li>
     *   <li><strong>Length before content,</strong> which matches the specification's refusal
     *       catalogue and bounds the control-character scan to at most 64 code points.</li>
     * </ul>
     */
    private static Optional<RegisterSubmissionError> firstViolation(ActorRole role,
                                                                    @Nullable String externalRef,
                                                                    @Nullable BigDecimal limit) {
        return Stream.<Supplier<Optional<RegisterSubmissionError>>>of(
                        () -> role.canDecide()
                                ? Optional.empty()
                                : Optional.of(new RegisterSubmissionError.RoleMayNotRegister(role)),
                        () -> externalRef != null && !externalRef.isEmpty()
                                ? Optional.empty()
                                : Optional.of(new RegisterSubmissionError.ReferenceMissing()),
                        () -> referenceLengthViolation(Objects.requireNonNull(externalRef)),
                        () -> controlCharacterViolation(Objects.requireNonNull(externalRef)),
                        () -> limit != null
                                ? Optional.empty()
                                : Optional.of(new RegisterSubmissionError.InsuredLimitMissing()),
                        () -> Objects.requireNonNull(limit).signum() > 0
                                ? Optional.empty()
                                : Optional.of(
                                        new RegisterSubmissionError.InsuredLimitNotPositive()),
                        () -> precisionViolation(Objects.requireNonNull(limit)))
                .map(Supplier::get)
                .flatMap(Optional::stream)
                .findFirst();
    }

    /**
     * BR-05 — measured in <strong>code points</strong>, never {@code String.length()}.
     *
     * <p>No character-set restriction is confirmed, so a reference may hold supplementary
     * characters, where UTF-16 code units and characters diverge: 64 emoji occupy 128 code units.
     * Code points are also exactly what PostgreSQL {@code VARCHAR(64)} counts, so a reference this
     * check accepts can never overflow the column.
     */
    private static Optional<RegisterSubmissionError> referenceLengthViolation(String externalRef) {
        var length = externalRef.codePointCount(0, externalRef.length());
        return length <= RegisterSubmissionCommand.MAX_EXTERNAL_REF_LENGTH
                ? Optional.empty()
                : Optional.of(new RegisterSubmissionError.ReferenceTooLong(
                        RegisterSubmissionCommand.MAX_EXTERNAL_REF_LENGTH, length));
    }

    /**
     * BR-29 — no character of Unicode general category {@code Cc}, anywhere in the reference.
     *
     * <p><strong>The category is the rule, not a list of known-bad characters.</strong> The product
     * owner was offered the narrow fix — refuse only NUL, the one character a {@code VARCHAR}
     * genuinely cannot hold — and chose the whole category instead, precisely so that no second
     * control character has to be discovered in production later.
     * {@link Character#getType(int)} {@code == } {@link Character#CONTROL} is exactly category
     * {@code Cc}, which is exactly the specification's two ranges U+0000–U+001F and
     * U+007F–U+009F.
     *
     * <p>{@code Character.isWhitespace} and a {@code \s} regex are both wrong here and would pass
     * this rule's happy cases while failing it: they disagree with the category over most of the
     * upper range — U+0085 NEXT LINE is the case that catches it — and they would drag in SPACE,
     * which is category {@code Zs} and stays valid, reversing a rule nobody asked to reverse.
     *
     * <p>The index reported is a <strong>code point</strong> index, counted the same way BR-05
     * counts length, so the two reference refusals measure positions alike. For a reference such
     * as an emoji followed by a TAB the two measures diverge — code point index 1, char index 2 —
     * and a caller told "2" would inspect the wrong character.
     */
    private static Optional<RegisterSubmissionError> controlCharacterViolation(String externalRef) {
        var codePoints = externalRef.codePoints().toArray();
        return IntStream.range(0, codePoints.length)
                .filter(index -> Character.getType(codePoints[index]) == Character.CONTROL)
                .<RegisterSubmissionError>mapToObj(index ->
                        new RegisterSubmissionError.ReferenceContainsControlCharacter(
                                codePoints[index], index))
                .findFirst();
    }

    /**
     * BR-12 — the scale is read <strong>as supplied</strong>. No {@code stripTrailingZeros()} and
     * no {@code setScale()} anywhere in the pipeline: either would make this rule unenforceable,
     * because {@code 1200} and {@code 1200.00} would become indistinguishable.
     */
    private static Optional<RegisterSubmissionError> precisionViolation(BigDecimal insuredLimit) {
        var scale = insuredLimit.scale();
        return scale == RegisterSubmissionCommand.REQUIRED_INSURED_LIMIT_SCALE
                ? Optional.empty()
                : Optional.of(new RegisterSubmissionError.InsuredLimitPrecisionInvalid(
                        RegisterSubmissionCommand.REQUIRED_INSURED_LIMIT_SCALE, scale));
    }
}
