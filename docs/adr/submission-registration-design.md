# Design: submission registration

Mode: **`@architect` design mode.** Produced from `docs/specs/submission-registration-spec.md`
(BR-01..BR-28) following `.github/skills/spec-to-plan/SKILL.md`. No production or test code was
written; every signature and every SQL statement below lives in this document only.

Every business number here traces to the spec. The one number the spec deliberately delegates —
the maximum page size — is recorded in [§7](#7-engineering-decision-maximum-page-size) as an
engineering decision with its reasoning. Nothing else in this document invents a business rule.

---

## 1. Scope

Covers, and only covers, the two operations the spec puts in scope:

- **register a submission** — one command, **nine** refusal reasons (BR-01..BR-15, BR-29)
- **search submissions** — one query, one refusal reason (BR-16..BR-28)

Module: **`submission`** — one business capability, nameable without "and" because registration
and search are the write and read side of the same capability, not two capabilities.

```
com.gs5.underwriting.submission
├── domain/      WRITE side.  Pure Java. Aggregate package-private.
├── query/       READ side.   Projections only. Cannot import domain/.
└── adapters/
    ├── web/
    ├── persistence/
    ├── <the publishing adapter — deferred, see §12>
    └── config/
```

There is **no `application` package and no `*Service` class.** The use case *is* the application
boundary; a layer between a controller and a use case has nothing to do but delegate, and
Checkstyle `noServiceSuffixDeclaration` plus ArchUnit `no_procedural_service_layer` fail the
build on the alternative.

`domain/` and `query/` import only `java.*`, `com.gs5.underwriting.shared.kernel.*` and
`org.jspecify.annotations.*`. Each package carries a `package-info.java` with `@NullMarked`.

---

## 2. File inventory

```
submission/
├── domain/
│   ├── package-info.java                 @NullMarked
│   ├── SubmissionId.java                 public record
│   ├── RegisterSubmissionCommand.java    public record, standalone
│   ├── RegisterSubmissionError.java      public sealed interface — 9 variants
│   ├── RegisterSubmissionUseCase.java      public final class — execute(...)
│   ├── SubmissionCommandRepository.java  public interface — outbound port
│   ├── SubmissionEventPublisher.java     public interface — outbound port
│   ├── SubmissionIdGenerator.java        public interface — outbound port
│   ├── SubmissionSnapshot.java           public record — persistence contract
│   ├── SubmissionDomainEvent.java        public sealed interface
│   └── Submission.java                   PACKAGE-PRIVATE aggregate
├── query/
│   ├── package-info.java                 @NullMarked
│   ├── SearchSubmissionsQuery.java       public record (clamps its own page request)
│   ├── SearchSubmissionsError.java       public sealed interface — 1 variant
│   ├── SearchSubmissionsUseCase.java        public final class — ask(...)
│   ├── SubmissionSearchCriteria.java     public record — tenant + five filters
│   ├── SubmissionSummaryView.java        public record — one result row
│   ├── SubmissionPageView.java           public record — page + total count
│   └── SubmissionQueryRepository.java    public interface — read port, returns views
└── adapters/
    ├── web/          SubmissionCommandController, SubmissionQueryController,
    │                 RegisterSubmissionRequest, ActorContextResolver
    ├── persistence/  SubmissionJdbcEntity, SubmissionCommandJdbcRepository,
    │                 SubmissionQueryJdbcRepository, SubmissionCommandSqlAdapter,
    │                 SubmissionQuerySqlAdapter, SubmissionPersistenceMapper   (package-private)
    ├── ?             the SubmissionEventPublisher adapter. Its technology AND its package
    │                 are a deferred adapter decision — see §12. This design does not name
    │                 a transport, a topic, a message key, a wire contract or a relay.
    └── config/       SubmissionConfiguration
```

---

## 3. Commands (write side)

| Command record | UseCase method | Return type | Event on success |
|---|---|---|---|
| `RegisterSubmissionCommand` | `register` | `Result<SubmissionId, RegisterSubmissionError>` | `SubmissionDomainEvent.SubmissionRegistered` |

### 3.1 The command record

```java
public record RegisterSubmissionCommand(@Nullable String externalRef,
                                        @Nullable BigDecimal insuredLimit) {

    public static final int MAX_EXTERNAL_REF_LENGTH = 64;   // BR-05
    public static final int REQUIRED_INSURED_LIMIT_SCALE = 2; // BR-12
    // BR-29 needs no constant: Unicode general category Cc is the definition, see §3.1.

    @Override
    public String toString() {
        return "RegisterSubmissionCommand[externalRef=" + externalRef + ", insuredLimit=***]";
    }
}
```

| Field | Required | Constraint | Refused as |
|---|---|---|---|
| `externalRef` | yes | present and **not empty** (BR-04); at most 64 characters, measured in **code points** (BR-05); contains **no character of Unicode general category Cc** (BR-29); compared byte-exactly, never trimmed or case-folded (BR-07) | `ReferenceMissing`, `ReferenceTooLong`, `ReferenceContainsControlCharacter` |
| `insuredLimit` | yes | present (BR-10); `signum() > 0` (BR-11); `scale() == 2` exactly (BR-12); **no upper bound** (BR-13); euro by definition, no currency field anywhere (BR-14) | `InsuredLimitMissing`, `InsuredLimitNotPositive`, `InsuredLimitPrecisionInvalid` |

Both components are `@Nullable` **on purpose**, and the record has **no compact constructor
running `Objects.requireNonNull`**. Absence of either input is a business refusal the spec names
(BR-04, BR-10); turning it into a programmer-error guard would convert a refusal reason into a
crash and make two of the nine variants unreachable.

Nothing else is accepted as input. Organisation, registering caller and registration moment come
from `ActorContext` and from the `Clock` (BR-15).

**`isEmpty()`, not `isBlank()`.** BR-04 refuses a reference that "contains no characters at all";
BR-07 states that two references differing in surrounding blank space are *different* references
and that no trimming is applied. A reference of `" "` is therefore valid and distinct. The
`hexagonal-cqrs-slice` skill's illustration uses `externalRef.isBlank()` and a variant named
`BlankExternalReference` — **that example contradicts this spec and must not be copied.**

**No control characters (BR-29), and `isEmpty()` is still not `isBlank()`.** These two rules are
easy to conflate and the spec says plainly that they must not be: "control character" is **narrower
than "whitespace"**. TAB (U+0009) and NEWLINE (U+000A) are both control characters and whitespace;
**SPACE (U+0020) is only whitespace and is not a control character.** So a reference of `"   "`
stays valid — present, non-empty, no control character, not normalised away — and BR-07's
no-trimming rule is untouched. `isBlank()` would refuse it and would therefore still be wrong after
BR-29, which is exactly the trap BR-29 makes tempting.

The test is on category, not on a character list:

```java
externalRef.codePoints()
        .filter(codePoint -> Character.getType(codePoint) == Character.CONTROL)
        .findFirst()                                     // OptionalInt, the first offender
```

`Character.getType(cp) == Character.CONTROL` **is** Unicode general category Cc, which is exactly
the spec's two ranges U+0000–U+001F and U+007F–U+009F — no wider, no narrower. Writing the ranges
out by hand would work identically and is the more literal transcription of the rule; the category
test is preferred because it cannot drift from the definition by a typo in a hex bound. Either way
the check is **not** a list of known-bad characters: the product owner deliberately chose the whole
category over the narrow NUL-only fix, precisely so that no second control character has to be
discovered later.

**Code points, not `String.length()`.** The spec restricts the reference's character set only
through BR-29, so a reference may still contain supplementary characters, where `String.length()`
(UTF-16 code units) and the character count diverge. The measure is
`externalRef.codePointCount(0, externalRef.length())`,
which is also exactly what PostgreSQL `VARCHAR(64)` counts — so a reference the domain accepts can
never overflow the column. Flagged in [§11](#11-open-questions).

### 3.2 `RegisterSubmissionError` — exactly nine variants

One sealed interface for **this one operation**. It is not shared with Search, and it gains no
variant the spec does not list.

```java
public sealed interface RegisterSubmissionError {

    /** BR-01 — no organisation, identity and role are available. */
    record CallerNotIdentified() implements RegisterSubmissionError { }

    /** BR-02 — the caller's role is VIEWER. */
    record RoleMayNotRegister(ActorRole role) implements RegisterSubmissionError { }

    /** BR-04 — no reference supplied, or it contains no characters. */
    record ReferenceMissing() implements RegisterSubmissionError { }

    /** BR-05 — reference longer than 64 characters. */
    record ReferenceTooLong(int maxLength, int actualLength) implements RegisterSubmissionError { }

    /** BR-29 — reference contains a character of Unicode general category Cc. */
    record ReferenceContainsControlCharacter(int codePoint) implements RegisterSubmissionError { }

    /** BR-10 — no insured limit supplied. */
    record InsuredLimitMissing() implements RegisterSubmissionError { }

    /** BR-11 — insured limit is zero or negative. */
    record InsuredLimitNotPositive() implements RegisterSubmissionError { }

    /** BR-12 — insured limit does not have exactly two decimal places. */
    record InsuredLimitPrecisionInvalid(int requiredScale, int actualScale)
            implements RegisterSubmissionError { }

    /** BR-06, BR-09 — the caller's own organisation already used this exact reference. */
    record ReferenceAlreadyUsed(String externalRef) implements RegisterSubmissionError { }
}
```

Spec refusal reason → variant, one to one, all nine, in the spec's own enumeration order:

| # | Spec refusal reason | Variant | Rule | Raised in |
|---|---|---|---|---|
| 1 | Caller not identified | `CallerNotIdentified` | BR-01 | use case |
| 2 | Role may not register | `RoleMayNotRegister` | BR-02 | aggregate factory |
| 3 | Reference missing | `ReferenceMissing` | BR-04 | aggregate factory |
| 4 | Reference too long | `ReferenceTooLong` | BR-05 | aggregate factory |
| 5 | Reference contains a control character | `ReferenceContainsControlCharacter` | **BR-29** | **aggregate factory** |
| 6 | Insured limit missing | `InsuredLimitMissing` | BR-10 | aggregate factory |
| 7 | Insured limit not positive | `InsuredLimitNotPositive` | BR-11 | aggregate factory |
| 8 | Insured limit precision invalid | `InsuredLimitPrecisionInvalid` | BR-12 | aggregate factory |
| 9 | Reference already used | `ReferenceAlreadyUsed` | BR-06, BR-09 | persistence adapter (unique index) |

**`ReferenceContainsControlCharacter` is raised in the aggregate factory**, with the other
reference-shape checks, and **not** in the persistence adapter. That matters, because the defect
that produced BR-29 was an adapter-level rejection: the storage refused what the domain had
accepted, so the caller got a 500 for an input the business had called valid. Moving the check into
the domain is the whole point of the rule — the refusal is now decided at the only layer that is
allowed to decide refusals, before anything is written, and it is reachable with no database at all.
`ReferenceAlreadyUsed` remains the single adapter-raised variant, because uniqueness genuinely
cannot be decided without the database (BR-09).

**No variant carries the insured limit.** BR-22 forbids the amount in any log, "not partially, not
rounded", explicitly including the refusals that are *about* the amount. An error record ends up in
a log line and in an RFC 7807 `ProblemDetail`, so the amount must not be inside it.
`InsuredLimitNotPositive` therefore carries nothing at all, and `InsuredLimitPrecisionInvalid`
carries only the two scales — a scale is a property of the notation, not a part of the magnitude,
and `requiredScale` is the constant `2`. `ReferenceAlreadyUsed` carries the reference, which BR-23
confirms is readable. No variant carries `actorId` (see [§10](#10-logging-and-masking)).

`RoleMayNotRegister` carries the role rather than the actor: the role is the fact that caused the
refusal, and it identifies nobody.

**`ReferenceContainsControlCharacter` carries the offending code point as an `int`, and never the
character itself.** An `int` such as `9` is not a sensitive value — the reference it came from is
loggable in full under BR-23 — and it is what a caller needs in order to fix the input, reported as
`U+0009`. The deliberate omission is the raw character: **writing an unescaped control character
into a log line is log injection.** A reference containing NEWLINE, written raw into a log, forges
what looks like an additional log entry; one containing a terminal escape sequence can manipulate
whoever reads the log. That hazard exists precisely for the references BR-29 refuses, so the
refusal path is the one place it is guaranteed to be reachable. Two consequences:

- **The refusal's log entry and `ProblemDetail` must escape the reference, not omit it.** BR-23
  wants the reference readable; `\u0009` is both readable and inert, and is more informative than
  an invisible character. Escaping every code point of category Cc satisfies BR-22 and BR-23
  together without either rule bending.
- This is flagged for `@sec-reviewer` in [§11](#11-open-questions).

### 3.3 Use case

```java
public final class RegisterSubmissionUseCase {

    private final SubmissionCommandRepository repository;
    private final SubmissionEventPublisher eventPublisher;
    private final SubmissionIdGenerator idGenerator;
    private final Clock clock;

    public RegisterSubmissionUseCase(SubmissionCommandRepository repository,
                                   SubmissionEventPublisher eventPublisher,
                                   SubmissionIdGenerator idGenerator,
                                   Clock clock) { /* requireNonNull each */ }

    public Result<SubmissionId, RegisterSubmissionError> execute(RegisterSubmissionCommand command);
}
```

The body is a railway pipeline, not a staircase:

```java
return Result.<ActorContext, RegisterSubmissionError>fromOptional(
                ActorContext.current(), RegisterSubmissionError.CallerNotIdentified::new)
        .flatMap(actor -> Submission.register(
                idGenerator.nextSubmissionId(), actor.tenantId(), actor.actorId(), actor.role(),
                command.externalRef(), command.insuredLimit(), clock.instant()))
        .flatMap(submission -> repository.insert(submission.toSnapshot())
                .<RegisterSubmissionError>mapError(error -> error)
                .peek(_ -> eventPublisher.publish(
                        submission.registeredEvent(idGenerator.nextEventId()))));
```

Four points about that pipeline:

- `Result.fromOptional(ActorContext.current(), ...)` is the only correct entry: `ActorContext.current()`
  returns `Optional` precisely so BR-01 is a value and not an unbound-`ScopedValue` throw.
- `.mapError(error -> error)` **widens** `Result<SubmissionId, ReferenceAlreadyUsed>` to
  `Result<SubmissionId, RegisterSubmissionError>`. It is an identity function that typechecks
  because `? extends F` is satisfied — **not** a cast. The port is deliberately typed on the single
  variant so an adapter cannot fabricate a role or precision refusal.
- **`peek` is what makes "no event for a refused registration" structural.** `Result.peek` runs its
  side effect on the success branch only, so a `ReferenceAlreadyUsed` from the insert cannot reach
  the publisher. The spec requires this ("The event is never raised for a refused registration"),
  and it is now a property of the combinator rather than of anyone's discipline. The insert comes
  first for the same reason: the BR-09 race is settled by the database, so nothing can be announced
  until the row exists.
- Only the methods already on `shared/kernel/Result.java` are used: `fromOptional`, `flatMap`,
  `mapError`, `peek`, plus `map` / `fold` elsewhere. No new combinator is needed, so none is
  invented at the call site.

Ordering, and what happens if publishing fails after the insert succeeded, is in
[§3.5](#35-the-port-split-and-the-trade-off-it-accepts).

No `@Transactional`, no `@Service`, no Spring import. Registration as a bean happens in
`adapters/config/`.

### 3.4 Outbound ports (all three in `domain/`, all speaking domain types only)

```java
public interface SubmissionCommandRepository {

    /**
     * Inserts a never-before-persisted submission. Returns the refusal when the caller's own
     * organisation already used this exact reference (BR-06, BR-09).
     */
    Result<SubmissionId, RegisterSubmissionError.ReferenceAlreadyUsed> insert(
            SubmissionSnapshot snapshot);
}

public interface SubmissionEventPublisher {

    /** Announces a business fact. How, and to whom, is an adapter decision — see §12. */
    void publish(SubmissionDomainEvent event);
}

public interface SubmissionIdGenerator {

    SubmissionId nextSubmissionId();

    UUID nextEventId();
}
```

`SubmissionEventPublisher` taking the domain event is **the whole of this module's knowledge about
publishing.** There is no transport, no topic, no message key, no wire contract and no relay
anywhere in `domain/`, `query/` or this document's schema. `publish` returns `void` and not a
`Result`, because the spec lists no refusal reason for announcing a fact: a `Result` there would
force the use case to invent a tenth error variant for something the business never described as an
outcome.

`SubmissionIdGenerator` exists so the write side stays a pure function of its inputs: the aggregate
never calls `UUID.randomUUID()`, so a test can pin both ids and assert them. It is also the only
source of randomness in `domain/`.

### 3.5 The port split, and the trade-off it accepts

**Decision: two separate ports — `SubmissionCommandRepository.insert(snapshot)` and
`SubmissionEventPublisher.publish(event)`.** An earlier revision of this design folded both into a
single port method, `insert(SubmissionSnapshot, SubmissionRegistered)`. That shape is withdrawn, and
not merely edited: its entire justification was specific to one adapter choice, so with that choice
removed the justification is gone and keeping the shape would be habit.

**Why the combined port existed, and why that reason no longer holds.** It made the state row and
the outbox row atomic in one adapter method, which is a transaction. Its premise was that a
particular adapter — an outbox table plus a relay — had already been chosen, and that its atomicity
requirement should therefore be visible in the domain's port vocabulary. That is the premature
binding inverted: the domain's port shape was being determined by the needs of one unchosen
adapter. With publishing deferred, several of the candidate adapters have no atomicity requirement
at all, and a port shaped around the strictest one would force every other candidate to implement
a signature it does not need.

**What the split costs, stated plainly rather than implied.** `register` now has two sequential
side effects, and the second can fail after the first has succeeded. If `publish` fails:

- **The submission is registered.** The row is committed, it is findable by a search from the same
  organisation, and BR-15 is satisfied. The announcement is lost.
- **The use case does not return a refusal.** There is no "could not announce" refusal reason in the
  spec, and the list is exhaustive by construction, so inventing a tenth variant to describe an
  infrastructure failure would contradict the specification. The adapter's failure surfaces as what
  it is — an infrastructure exception propagating out of `register` to the web edge, which answers
  `500`. Nothing is caught in `domain/`; the zero-throw rule bans creating exceptions, not
  letting an adapter's own propagate.
- **So the caller can be told "failed" about a submission that exists.** That is the real cost, and
  it is a lost event plus a misleading answer, invisible until someone reconciles.
- **This is close to the pattern BR-29 was written to stop, and the difference is worth stating.**
  The spec now says a caller must never receive an unexplained failure for an input it calls valid,
  and that if one does, the refusal catalogue is wrong. The NUL finding was exactly that: a property
  **of the input** made the operation impossible for every attempt, deterministically, so it was a
  missing refusal reason. A publishing failure is not a property of the input — no reference and no
  amount makes it more or less likely, every input is still valid, and the state change did happen.
  It is a transient infrastructure failure, which is the one thing a 500 legitimately means. The
  distinction is the test to apply the next time this question comes up: **if the same input always
  fails, it is a missing refusal reason; if the same input usually succeeds, it is an outage.** Flagged
  in [§11](#11-open-questions) so it is reviewed rather than assumed.
- **The one mitigation that comes free is worth naming:** the reference is a *caller-supplied*
  natural key, unique per organisation (BR-06). A caller who retries after an ambiguous failure gets
  `ReferenceAlreadyUsed`, not a second submission. The operation is therefore idempotent on the
  caller's own key by construction — no double registration is possible — and the caller can
  confirm the outcome by searching for the reference. BR-09 and BR-06 do this work; no design
  element was added for it.

**Accepted:** at-most-once announcement, with exactly-once state and a safe retry. **Rejected:**
binding the domain's port vocabulary to one adapter's atomicity requirement in order to buy
exactly-once announcement before anyone has decided whether this module needs it.

**And the split does not foreclose fixing it.** If the module later needs the event to be atomic
with the state change, that is satisfied *behind* the publisher port by an adapter that writes the
fact to a table in the same transaction as the insert — at which point the open question is where
the transaction boundary lives, which is an adapter-and-wiring question with the use case unchanged.
Recorded as part of the deferred decision in [§12](#12-events-and-the-publishing-port) rather than
pre-empted here.

One consequence to carry into stage 3: the two ports get two independent in-memory fakes (a
`ConcurrentHashMap` for the repository, a `CopyOnWriteArrayList` for the publisher), which is
*easier* to test than the combined port was — a fake publisher that fails is now the natural way to
exercise the paragraph above.

---

## 4. Aggregate

```java
record SubmissionSnapshot(UUID id, UUID tenantId, String externalRef, BigDecimal insuredLimit,
                          String registeredBy, Instant registeredAt, @Nullable Long version)
```
(public — it is the persistence contract; see [§10](#10-logging-and-masking) for its `toString()`)

```java
final class Submission {              // PACKAGE-PRIVATE — not public, no public constructor

    private final SubmissionId id;
    private final UUID tenantId;
    private final String externalRef;
    private final BigDecimal insuredLimit;
    private final String registeredBy;     // ActorContext.actorId
    private final Instant registeredAt;
    private final @Nullable Long version;  // plain Long, no annotation, null when new

    private Submission(/* all of the above */) { }

    /** New submission. Every invariant is checked here and nowhere else. */
    static Result<Submission, RegisterSubmissionError> register(
            SubmissionId id, UUID tenantId, String actorId, ActorRole role,
            @Nullable String externalRef, @Nullable BigDecimal insuredLimit, Instant registeredAt);

    /** Rebuilds state loaded from the database. Carries version; re-validates nothing. */
    static Submission reconstitute(SubmissionSnapshot snapshot);

    SubmissionSnapshot toSnapshot();                                  // version stays as held

    SubmissionDomainEvent.SubmissionRegistered registeredEvent(UUID eventId);
}
```

Invariants, in the order `register` checks them, each producing exactly one variant:

| Order | Invariant | Rule | Violation produces |
|---|---|---|---|
| 1 | `role.canDecide()` — role is `UNDERWRITER` or `SENIOR_UNDERWRITER` | BR-02, BR-03 | `RoleMayNotRegister(role)` |
| 2 | `externalRef != null && !externalRef.isEmpty()` | BR-04 | `ReferenceMissing()` |
| 3 | `externalRef.codePointCount(0, externalRef.length()) <= 64` | BR-05 | `ReferenceTooLong(64, actual)` |
| 4 | no code point of `externalRef` has `Character.getType(cp) == Character.CONTROL` | **BR-29** | `ReferenceContainsControlCharacter(cp)` |
| 5 | `insuredLimit != null` | BR-10 | `InsuredLimitMissing()` |
| 6 | `insuredLimit.signum() > 0` | BR-11 | `InsuredLimitNotPositive()` |
| 7 | `insuredLimit.scale() == 2` | BR-12 | `InsuredLimitPrecisionInvalid(2, actual)` |

**The order is pinned, not incidental.** An input can violate two rules at once, and which refusal
the caller is told about is then decided by this sequence — so it is part of the contract and tests
assert it. Why each position:

- **Role stays first (1).** A Viewer learns that they may not register before learning anything
  about their input. Existing tests assert this, and it is also right on principle: telling an
  unauthorised caller which of their field values is malformed leaks the validator's behaviour to
  someone who is not allowed to use the operation at all. BR-29 does not disturb it.
- **Presence before shape (2 before 3, 4).** Nothing can be said about the characters of an absent
  or empty reference. Note that `""` trivially contains no control character, so a control-character
  check placed earlier would *pass* on an empty reference and the real problem would be reported
  later and less clearly.
- **Length before content (3 before 4) — this is where BR-29 goes, and it is deliberate.** Three
  reasons: it matches the spec's own enumeration order, in which "reference too long" is reason 4
  and "reference contains a control character" is reason 5, so the design and the catalogue the
  tests are written from agree; a 10 MB reference ending in a TAB is refused for length without
  scanning it, which bounds the control-character scan to at most 64 code points; and length is the
  coarser, more obvious defect, so reporting it first is what a submitting party would expect. A
  reference that is both too long and contains a control character is therefore refused as
  `ReferenceTooLong`.
- **Reference fully validated before the amount is examined (4 before 5).** The reference is the
  identifier the caller will quote back; it is reported first in full.
- **Positivity before precision (6 before 7), unchanged and still load-bearing.** `BigDecimal.ZERO`
  has `scale() == 0`, so it violates both BR-11 and BR-12. This order makes it
  `InsuredLimitNotPositive`, the more fundamental fact, and the spec's own worked examples list
  `0.00` under BR-11. Swapping these two silently changes the answer for an input the spec names.
- **Uniqueness last of all**, in the adapter ([§9.3](#93-command-adapter)), after the aggregate has
  accepted everything: it is the only refusal costing a database round trip, and under BR-29 it is
  no longer reachable by a reference that the storage could not have held anyway.

`ActorRole.canDecide()` already exists in `shared/kernel` and returns true for exactly the two
roles BR-02/BR-03 permit. BR-03 is satisfied by *not* distinguishing them: no authority limit
exists in this feature.

`scale()` is read **as supplied**. No `stripTrailingZeros()`, no `setScale()`, anywhere in the
pipeline — either would make BR-12 unenforceable (`1200` and `1200.00` would become
indistinguishable, and the spec accepts one and refuses the other).

There are **no state transitions and no status field.** The spec puts every lifecycle state beyond
"registered" out of scope and says registration and search need none. A `status` column added "for
later" is an invented business concept; it is not here.

`register` sets `version = null`. That null is what tells the persistence adapter this aggregate
has never been stored — see [§9.2](#92-the-version-trap--read-this-before-writing-the-mapper).

`reconstitute` has **no production caller in this feature**, because a submission is read-only once
registered and no command loads one. It is specified because it is half of the snapshot contract:
the round-trip integration test loads the inserted row back through the mapper and
`reconstitute`s it, which is what proves `version` survives both directions. It gains a production
caller with the first command that changes an existing submission — a future feature.

Other aggregates would be referenced by typed id only. None exists here.

---

## 5. Queries (read side)

| Query record | UseCase method | Return type | View records |
|---|---|---|---|
| `SearchSubmissionsQuery` | `search` | `Result<SubmissionPageView, SearchSubmissionsError>` | `SubmissionPageView`, `SubmissionSummaryView` |

`query/` imports nothing from `domain/` — not `SubmissionId`, not `SubmissionSnapshot`. The read
side carries raw `UUID`. This is compiler-enforced for the aggregate (package-private) and
ArchUnit-enforced for the rest.

### 5.1 The query record — and where the page request is normalised

```java
public record SearchSubmissionsQuery(@Nullable String externalRef,
                                     @Nullable BigDecimal insuredLimitFrom,
                                     @Nullable BigDecimal insuredLimitTo,
                                     @Nullable Instant registeredFrom,
                                     @Nullable Instant registeredTo,
                                     int page,
                                     int size) {

    public static final int MIN_PAGE_SIZE = 1;
    public static final int MAX_PAGE_SIZE = 100;   // engineering decision, §7

    public SearchSubmissionsQuery {
        page = Math.max(0, page);
        size = Math.clamp(size, MIN_PAGE_SIZE, MAX_PAGE_SIZE);
    }

    @Override
    public String toString() {
        return "SearchSubmissionsQuery[externalRef=" + externalRef
                + ", insuredLimitFrom=***, insuredLimitTo=***"
                + ", registeredFrom=" + registeredFrom + ", registeredTo=" + registeredTo
                + ", page=" + page + ", size=" + size + "]";
    }
}
```

All five filters are optional (BR-19) and combine with AND (BR-18). They are `@Nullable` rather
than `Optional` components because absence has to survive all the way into a bound SQL parameter
(`:param IS NULL OR ...`), and `Optional` cannot be bound to `@Param`. `Optional` stays where the
style guide needs it: return types and lookups (`ActorContext.current()`, `Result.fromOptional`).

Filter bounds are **not** re-validated against BR-11 or BR-12 — the spec says plainly that those
are rules about what may be *registered*. A filter bound may be negative, zero, or carry any scale.

**Normalising the page request inside the compact constructor is deliberate.** The record cannot
hold an out-of-range page request, so the use case, the adapter and the response body cannot
disagree about what was applied, and `size` echoed back to the caller is by construction the size
actually used. Page numbering is **0-based** (`offset = page * size`): the first page is `page=0`,
**confirmed by the product owner** after this design proposed it, so it is settled and no longer an
open question.

### 5.2 `SearchSubmissionsError` — exactly one variant

```java
public sealed interface SearchSubmissionsError {

    /** BR-01 — no organisation, identity and role are available. */
    record CallerNotIdentified() implements SearchSubmissionsError { }
}
```

**One variant, deliberately, and it is not merged with `RegisterSubmissionError`.** The spec states
the single-reason list is complete and not an oversight: once the caller is known, every search
succeeds. A role never refuses a search (BR-16), matching nothing is a success (BR-20), a
contradictory range is a success (BR-21), and a page past the end is a success (BR-27). The two
hierarchies stay separate so a caller searching never has to handle `InsuredLimitPrecisionInvalid`.

Note that `CallerNotIdentified` appears in both hierarchies as two distinct records. That is the
point of per-operation errors, not duplication to be factored out into a shared base.

### 5.3 Views

```java
public record SubmissionSummaryView(UUID submissionId, String externalRef, BigDecimal insuredLimit,
                                    String registeredBy, Instant registeredAt) {
    @Override
    public String toString() {   // BR-22 amount, OPEN-01 actorId
        return "SubmissionSummaryView[submissionId=" + submissionId
                + ", externalRef=" + externalRef
                + ", insuredLimit=***, registeredBy=***"
                + ", registeredAt=" + registeredAt + "]";
    }
}

public record SubmissionPageView(List<SubmissionSummaryView> submissions,
                                 long totalMatchCount,
                                 int page,
                                 int size) {
    public SubmissionPageView {
        submissions = List.copyOf(submissions);
    }
}
```

`SubmissionSummaryView` carries the four fields the spec lists — reference, insured limit,
registering caller, registration moment — plus `submissionId`. **The id is confirmed by the product
owner**, who accepted the reasoning: search is the only way to discover a submission's identity, and
future operations need it to name a submission. It is not sensitive. This is settled, not an open
question.

`totalMatchCount` is `long` and counts the **whole** matching set, ignoring which page was asked
for (BR-26) — reported with every page including an empty one. `page` and `size` echo what was
actually applied, which is what makes clamping safe (see [§7](#7-engineering-decision-maximum-page-size)).

### 5.4 Read port and criteria

```java
public record SubmissionSearchCriteria(UUID tenantId,
                                       @Nullable String externalRef,
                                       @Nullable BigDecimal insuredLimitFrom,
                                       @Nullable BigDecimal insuredLimitTo,
                                       @Nullable Instant registeredFrom,
                                       @Nullable Instant registeredTo) { }

public interface SubmissionQueryRepository {

    List<SubmissionSummaryView> findPage(SubmissionSearchCriteria criteria, int limit, long offset);

    long countMatching(SubmissionSearchCriteria criteria);
}
```

**`tenantId` lives inside the criteria and only the use case can build one.** It is not a filter the
caller supplies and not something an adapter may forget: BR-17 is a business rule, so the tenant
predicate is part of the port's vocabulary. Two methods rather than one because BR-26 needs the
count of the whole matching set while the page is bounded by `LIMIT`; they share one criteria value
so the two predicates cannot drift apart.

`offset` is `long`: `page * size` with `page` near `Integer.MAX_VALUE` overflows an `int` and would
silently produce a negative offset. The use case computes `(long) query.page() * query.size()`.

### 5.5 Query use case

```java
public final class SearchSubmissionsUseCase {

    private final SubmissionQueryRepository repository;

    public SearchSubmissionsUseCase(SubmissionQueryRepository repository) { /* requireNonNull */ }

    public Result<SubmissionPageView, SearchSubmissionsError> ask(SearchSubmissionsQuery query);
}
```

```java
return Result.<ActorContext, SearchSubmissionsError>fromOptional(
                ActorContext.current(), SearchSubmissionsError.CallerNotIdentified::new)
        .map(actor -> {
            var criteria = new SubmissionSearchCriteria(actor.tenantId(), query.externalRef(),
                    query.insuredLimitFrom(), query.insuredLimitTo(),
                    query.registeredFrom(), query.registeredTo());
            return new SubmissionPageView(
                    repository.findPage(criteria, query.size(), (long) query.page() * query.size()),
                    repository.countMatching(criteria),
                    query.page(), query.size());
        });
```

The use case **never reads `actor.role()`** — BR-16: role neither narrows nor widens a search.

**BR-20, BR-21 and BR-27 need no code branch at all.** A predicate of `>= 5000 AND <= 1000` matches
no row, so the page is empty and the count is zero; an `OFFSET` past the end returns no rows while
the count stays true. Each of the three is a test assertion, not a special case — adding a
short-circuit would be a second implementation of the same rule.

---

## 6. SQL shape

Both statements are native SQL in `@Query("""...""")`, mapped straight into the projection record.
The read repository extends `Repository`, not `CrudRepository`: a read repository that offers
`save()` will eventually be used to save.

```java
interface SubmissionQueryJdbcRepository extends Repository<SubmissionJdbcEntity, UUID> {

    @Query("""
            SELECT id AS submission_id,
                   external_ref,
                   insured_limit,
                   registered_by,
                   registered_at
            FROM submissions
            WHERE tenant_id = :tenantId
              AND (CAST(:externalRef AS VARCHAR) IS NULL
                   OR external_ref = CAST(:externalRef AS VARCHAR))
              AND (CAST(:insuredLimitFrom AS NUMERIC) IS NULL
                   OR insured_limit >= CAST(:insuredLimitFrom AS NUMERIC))
              AND (CAST(:insuredLimitTo AS NUMERIC) IS NULL
                   OR insured_limit <= CAST(:insuredLimitTo AS NUMERIC))
              AND (CAST(:registeredFrom AS TIMESTAMPTZ) IS NULL
                   OR registered_at >= CAST(:registeredFrom AS TIMESTAMPTZ))
              AND (CAST(:registeredTo AS TIMESTAMPTZ) IS NULL
                   OR registered_at <= CAST(:registeredTo AS TIMESTAMPTZ))
            ORDER BY registered_at DESC, id DESC
            LIMIT :limit OFFSET :offset
            """)
    List<SubmissionSummaryView> findPage(@Param("tenantId") UUID tenantId,
                                         @Param("externalRef") @Nullable String externalRef,
                                         @Param("insuredLimitFrom") @Nullable BigDecimal insuredLimitFrom,
                                         @Param("insuredLimitTo") @Nullable BigDecimal insuredLimitTo,
                                         @Param("registeredFrom") @Nullable Instant registeredFrom,
                                         @Param("registeredTo") @Nullable Instant registeredTo,
                                         @Param("limit") int limit,
                                         @Param("offset") long offset);

    @Query("""
            SELECT COUNT(*)
            FROM submissions
            WHERE tenant_id = :tenantId
              AND (CAST(:externalRef AS VARCHAR) IS NULL
                   OR external_ref = CAST(:externalRef AS VARCHAR))
              AND (CAST(:insuredLimitFrom AS NUMERIC) IS NULL
                   OR insured_limit >= CAST(:insuredLimitFrom AS NUMERIC))
              AND (CAST(:insuredLimitTo AS NUMERIC) IS NULL
                   OR insured_limit <= CAST(:insuredLimitTo AS NUMERIC))
              AND (CAST(:registeredFrom AS TIMESTAMPTZ) IS NULL
                   OR registered_at >= CAST(:registeredFrom AS TIMESTAMPTZ))
              AND (CAST(:registeredTo AS TIMESTAMPTZ) IS NULL
                   OR registered_at <= CAST(:registeredTo AS TIMESTAMPTZ))
            """)
    long countMatching(/* the same six parameters */);
}
```

Four details that are easy to get wrong here:

- **Every nullable parameter is wrapped in `CAST(... AS type)`.** PostgreSQL cannot infer the type
  of a bare parameter used in `IS NULL`, and fails the statement with "could not determine data
  type of parameter". The cast is not cosmetic.
- **`SELECT id AS submission_id`.** Spring Data JDBC maps result columns to record components by
  name; a component called `submissionId` does not match a column called `id`. The alias is what
  makes the projection bind.
- **Nothing is concatenated.** Every value is a bound parameter — including `limit` and `offset`.
- **`ORDER BY registered_at DESC, id DESC`,** not `registered_at DESC` alone. See
  [§8.2](#82-indexes-which-index-serves-which-filter).

The tenant predicate is `tenant_id = :tenantId` with no `OR`, in both statements. A submission of
another organisation is not returned, not counted, and nothing anywhere distinguishes "belongs to
someone else" from "does not exist" (BR-17). There is no `Forbidden` anywhere in this design.

---

## 7. Engineering decision: maximum page size

**`MAX_PAGE_SIZE = 100`.**

This is the one number the business considered and consciously delegated (spec, Scope; state board,
"Engineering decision"). It is a resource-protection parameter, so it is decided here.

**Why 100:**

- **Response and memory cost.** One `SubmissionSummaryView` is at most ~200 bytes of payload
  (a UUID, up to 64 characters of reference, an unbounded but realistically short numeric, an actor
  id, a timestamp). 100 rows is tens of kilobytes — one comfortable HTTP response, and a bounded
  allocation per concurrent request, which matters on virtual threads where the request count can
  be very high.
- **Database cost.** With the ordering index in [§8.2](#82-indexes-which-index-serves-which-filter),
  a page is `LIMIT 100` over an index scan that stops early. The `COUNT(*)` that BR-26 requires is
  the expensive half of a search and does not shrink with the page size, so pushing the page size
  higher buys little and costs linearly.
- **Consistency with what this service already does.** `application.yml` already sets
  `app.outbox.relay.batch-size: 100`. One batch idiom in one service is worth more than a
  marginally better-tuned second number.
- **Paging stays usable.** A tenant with 10 000 submissions is 100 pages — enough that no caller
  needs to fetch an unbounded set, few enough that nobody is forced into thousands of round trips.

**What happens when a caller asks for more than the maximum: the request is clamped, not refused.**

`size` is clamped into `[1, 100]` and `page` to `>= 0`, in the compact constructor of
`SearchSubmissionsQuery`, and the applied `page` and `size` are **echoed in `SubmissionPageView`**.

- **This adds no second refusal reason to Search.** The spec's one-reason list is exhaustive and
  explicitly deliberate; `PageSizeTooLarge` was considered and **rejected** because it would
  contradict that list, and the spec is the source of truth. A caller asking for 500 rows gets a
  successful 100-row page, not a 400.
- **Echoing the applied size is a correctness condition, not a nicety.** If a caller asked for
  `size=500`, received 100 rows, and then asked for `page=1, size=500`, naive paging would skip
  rows 100–499 and break BR-28. Because the response states `size=100`, a caller paging on the
  echoed value reaches every matching submission exactly once.
- **Degenerate requests also clamp rather than refuse.** `size <= 0` clamps up to 1 and a negative
  `page` to 0. Search has exactly one refusal reason, so every malformed page request must still
  produce a success; clamping into the valid range is the only option that keeps BR-28 intact.
- **Transport default.** When the HTTP query parameters are absent, the web layer binds `page=0`
  — the first page, since 0-based numbering is confirmed —
  and `size=100`, i.e. the maximum. "Not stated" means "as much as is allowed", which keeps this
  design to **one** page-size number instead of inventing a second default that the business never
  saw.

A malformed page parameter (`size=abc`) is rejected by Spring's type conversion before the use case
is reached, as a transport-level 400. That is not a business refusal and does not belong in
`SearchSubmissionsError`; it is noted in [§11](#11-open-questions).

---

## 8. Schema

### 8.1 `V2__create_submissions.sql`

`V1__init_outbox_and_inbox.sql` already provides `outbox_events` and `inbox_events`; this migration
adds one table.

```sql
-- Submissions. One row per registered submission; immutable once written, because the spec
-- puts amendment, withdrawal and every lifecycle state beyond "registered" out of scope.
-- There is deliberately no status column: inventing one would invent a business concept.

CREATE TABLE submissions
(
    id            UUID PRIMARY KEY,
    tenant_id     UUID                   NOT NULL,
    -- COLLATE "C": BR-07 compares references exactly as supplied, including letter case and
    -- surrounding blank space. A database created with a case-insensitive ICU collation would
    -- otherwise make both this column's equality test and the unique index below case-folding,
    -- silently breaking BR-06 and BR-07. VARCHAR(64) counts characters, which is exactly the
    -- code-point measure the domain applies for BR-05, so an accepted reference always fits.
    -- NOTE: neither the length nor this collation ever constrained the reference's CHARACTER
    -- SET. The type itself refuses U+0000 outright, below SQL. See §8.3.
    external_ref  VARCHAR(64) COLLATE "C" NOT NULL,
    -- Unconstrained NUMERIC on purpose. BR-13 states the insured limit has no upper bound and
    -- that none may be introduced without a business decision: NUMERIC(19, 2) would quietly
    -- impose one. Scale is enforced by the CHECK below rather than by the column type, because
    -- NUMERIC(p, 2) would ROUND 1200.005 to 1200.01 instead of letting BR-12 refuse it.
    insured_limit NUMERIC                NOT NULL,
    registered_by VARCHAR(255)           NOT NULL,
    registered_at TIMESTAMPTZ            NOT NULL,
    -- Optimistic lock. NOT NULL with no DEFAULT, exactly as in V1: Spring Data JDBC always
    -- supplies the value, and a DEFAULT would mask a mapper that forgot to carry it.
    version       BIGINT                 NOT NULL,

    -- BR-06 + BR-08: unique PER TENANT, so the same reference in another organisation is fine.
    -- This is also what settles the BR-09 race: the loser's insert conflicts.
    CONSTRAINT uq_submissions_tenant_external_ref UNIQUE (tenant_id, external_ref),

    -- Defence in depth for the domain invariants. Each one mirrors a business rule that is
    -- already refused in the aggregate; a row that violates one of them is a bug, not a refusal.
    CONSTRAINT ck_submissions_external_ref_not_empty CHECK (length(external_ref) > 0),          -- BR-04
    -- BR-29. Mirrors the domain check, and cannot fully mirror it: see §8.3. The escape syntax
    -- must be verified against PostgreSQL when the migration is written.
    CONSTRAINT ck_submissions_external_ref_no_control_chars
        CHECK (external_ref !~ '[\x01-\x1F\x7F-\x9F]'),                                         -- BR-29
    CONSTRAINT ck_submissions_insured_limit_positive CHECK (insured_limit > 0),                 -- BR-11
    CONSTRAINT ck_submissions_insured_limit_scale CHECK (scale(insured_limit) = 2)              -- BR-12
);

-- Search workhorse. Covers the tenant predicate that EVERY search carries (BR-17), the
-- registration-moment range, and the newest-first order (BR-24) with no sort step; the trailing
-- id makes that order total, which is what BR-28 needs.
CREATE INDEX idx_submissions_tenant_registered_at
    ON submissions (tenant_id, registered_at DESC, id DESC);

-- Insured limit range filter within a tenant.
CREATE INDEX idx_submissions_tenant_insured_limit
    ON submissions (tenant_id, insured_limit);

-- No index for the reference filter: uq_submissions_tenant_external_ref already builds a
-- btree on (tenant_id, external_ref), which serves both the uniqueness check and the
-- exact-reference search. A second index on the same columns would only cost write throughput.
```

`scale(numeric)` requires PostgreSQL 13 or newer; the project's Testcontainers image is
`postgres:17-alpine`.

### 8.2 Indexes: which index serves which filter

| Search input | Index used | Why |
|---|---|---|
| tenant (always present, BR-17) | `idx_submissions_tenant_registered_at` — leading column | Every search carries it, so it leads the workhorse index |
| `registeredFrom` / `registeredTo` range | `idx_submissions_tenant_registered_at` — second column | A range on the second column of a composite btree after an equality on the first is an index range scan |
| ordering `registered_at DESC, id DESC` (BR-24, BR-28) | `idx_submissions_tenant_registered_at` | The index is declared in exactly the ORDER BY direction, so the planner returns rows pre-ordered: **no sort node**, and the scan stops after `LIMIT + OFFSET` rows |
| `externalRef` exact match | `uq_submissions_tenant_external_ref` (the unique constraint's own btree) | Equality on both columns of the index; BR-06 guarantees at most one row, so this is a single index lookup |
| `insuredLimitFrom` / `insuredLimitTo` range | `idx_submissions_tenant_insured_limit` | Equality on tenant then a range on the amount |
| `COUNT(*)` for BR-26 | whichever of the above is most selective | The count must touch every matching row; that is inherent in reporting the whole matching set's size and cannot be indexed away |

**Why the trailing `id DESC` matters.** `registered_at` is not unique: `TIMESTAMPTZ` stores
microseconds, two submissions can share a value, and under load they will. With ties, `LIMIT`/`OFFSET`
paging over a non-total order lets PostgreSQL return a tied row on two different pages or on
neither — which breaks BR-28 ("no submission is skipped, none appears twice"). Adding `id` as a
tie-break makes the order total and deterministic. This is a technical necessity, not a new
business rule: BR-24's "newest first" is preserved exactly, and the tie-break only decides an order
the business never specified. Worth telling the product owner that "newest first" alone is not a
total order — see [§11](#11-open-questions).

**Which index wins when both a date range and an amount range are supplied** is the planner's
choice, and either is correct: with a selective amount range it filters on
`idx_submissions_tenant_insured_limit` and sorts a small result; with a selective date range it
scans the ordering index and filters. Only the first index can satisfy the ORDER BY without a sort,
so the common case — a tenant paging through recent submissions — never sorts.

**Known limit: `OFFSET` degrades on deep pages.** `OFFSET 100000` makes PostgreSQL walk and discard
100 000 index entries. Keyset ("seek") paging would fix it, but the spec's page request is
explicitly "which page is wanted", i.e. a page number, so `OFFSET` is what the business asked for.
Recorded as a known characteristic rather than designed around.

### 8.3 What the schema never constrained: the reference's character set

This section exists because the first revision of this design reasoned carefully about
`external_ref` — its length, its collation, its emptiness — and none of that reasoning touched the
thing that actually broke.

**`VARCHAR(64)` and `CHECK (length(external_ref) > 0)` were never what would stop a NUL byte.**
They constrain how *many* characters the column holds and that it holds at least one; they say
nothing about *which* characters. The thing that refused U+0000 was **the PostgreSQL text type
itself, enforced by the driver** — a `text`/`varchar` value cannot contain U+0000 at all, because
the wire protocol and the server's internal string representation are NUL-terminated. That is not a
constraint anybody declared and not a constraint anybody can relax. It sits **below the level where
business refusals are decided**, so when it fired it could only come out as an infrastructure
failure: a 500, for an input the business had called valid, with no refusal reason to name.

Two consequences for this design:

- **The new `ck_submissions_external_ref_no_control_chars` CHECK is defence in depth and cannot be
  complete.** It covers U+0001–U+001F and U+007F–U+009F. It **cannot cover U+0000**, the very
  character that caused the finding, because such a value is rejected before any CHECK is evaluated
  — there is no way to express it as a row the constraint could reject. The schema can therefore
  mirror BR-29 for every control character except the one that started this. The real enforcement
  of BR-29 is in the aggregate ([§4](#4-aggregate)); the CHECK is a backstop against a future write
  path that bypasses the domain, which is exactly what a backstop is for.
- **Defence in depth below a business rule is not a substitute for the rule.** Had the CHECK existed
  from the start without BR-29, the caller would still have received a 500 — a constraint violation
  is an infrastructure failure too. A character restriction only becomes a *refusal* when the
  domain owns it.

#### The general lesson, recorded because it was expensive

**A business rule that grants unrestricted input is only as true as the storage beneath it.**
"No restriction is imposed, because none is confirmed" reads like a safe, humble default — it
commits to nothing and invents nothing. It is in fact a strong claim: it promises that *every* value
can be registered. That promise is only as good as the narrowest constraint anywhere under it, and
nobody had checked what that was.

How it escaped every gate up to that point, which is the part worth remembering:

- **Design review did not catch it.** This document reasoned about the character set and still
  missed it, because it reasoned about the *declared* schema and the unstated constraint was in the
  type's implementation.
- **142 unit tests did not catch it.** They could not: the in-memory fake backing the port stores a
  `String` in a `ConcurrentHashMap`, and a Java `String` holds U+0000 perfectly happily. The fake
  was *more permissive than the real adapter*, so a passing test was evidence of nothing in the
  exact place it was needed. This is the failure mode of test doubles, not an oversight in the tests.
- **Adversarial testing against the real constraint caught it,** and only that.

Carried forward as a rule of thumb for this repository: **a rule of the form "no restriction"
deserves an explicit check against what the adapter can actually accept, and a test that runs
against the real adapter, before it is written down as confirmed.** Concretely, for stage 3: a fake
must not accept input the real store would reject for anything a rule claims is unrestricted — and
where that cannot be guaranteed, the claim needs an integration test, not a unit test. Noted in
[§11](#11-open-questions) as a standing item rather than as a one-off post-mortem.

---

## 9. Persistence adapter

### 9.1 Entity and write path

```java
@Table("submissions")
record SubmissionJdbcEntity(
        @Id UUID id,
        @Column("tenant_id") UUID tenantId,
        @Column("external_ref") String externalRef,
        @Column("insured_limit") BigDecimal insuredLimit,
        @Column("registered_by") String registeredBy,
        @Column("registered_at") Instant registeredAt,
        @Version @Nullable Long version) { }
```

```java
interface SubmissionCommandJdbcRepository extends Repository<SubmissionJdbcEntity, UUID> {

    @Modifying
    @Query("""
            INSERT INTO submissions
                (id, tenant_id, external_ref, insured_limit, registered_by, registered_at, version)
            VALUES
                (:id, :tenantId, :externalRef, :insuredLimit, :registeredBy, :registeredAt, 0)
            ON CONFLICT (tenant_id, external_ref) DO NOTHING
            """)
    int insertIfReferenceFree(/* one @Param per column */);
}
```

`ON CONFLICT ... DO NOTHING` plus a row-count check — `1` means inserted, `0` means the
organisation already used the reference — is the same idiom `inbox_events` already uses in V1, and
it is the right mechanism for BR-09 for three reasons:

1. **It settles the race in the database.** A `SELECT`-then-`INSERT` pre-check is exactly the race
   BR-09 describes; two concurrent registrations would both see "free" and both insert.
2. **It cannot poison a transaction.** Catching a `DuplicateKeyException` inside a transaction
   leaves PostgreSQL in an aborted state ("current transaction is aborted, commands ignored until
   end of transaction block"), so any statement after it *and* the commit itself fail. That costs
   nothing today, because this adapter runs one statement and opens no transaction
   ([§9.3](#93-command-adapter)) — but it would become a live defect the moment anything else joins
   the same transaction, which is exactly what the deferred publishing decision might do. The
   conditional insert is immune either way: it returns a row count and leaves the session usable.
3. **It needs no exception at all,** which keeps the adapter inside the zero-throw rule without a
   `catch` that has to decide which `DataAccessException` means what.

### 9.2 The `version` trap — read this before writing the mapper

**`SubmissionJdbcEntity` MUST carry `@Version @Nullable Long version`, and
`SubmissionPersistenceMapper` MUST carry it in both directions.**

The submission id is a **UUID generated in the domain** (`SubmissionIdGenerator`), so the id is
never null and Spring Data JDBC's "is this entity new?" test cannot use it. Without a `@Version`
property, `save()` on a brand-new aggregate issues an **UPDATE that affects zero rows**: no
exception, no row, and the caller is told it succeeded. With `@Version @Nullable Long version`,
`null` means insert and non-null means update under an optimistic lock.

Dropping `version` from either direction of the mapper is the single most common bug in this stack
and it only surfaces under concurrency, which is to say in production and not in a unit test.
Concretely:

- `SubmissionSnapshot.version()` is `null` for an aggregate from `Submission.register(...)` and
  non-null for one from `reconstitute(...)`.
- `toEntity(snapshot)` passes `snapshot.version()` straight through — it does **not** substitute
  `0L` for null.
- `toSnapshot(entity)` passes `entity.version()` straight back — it does **not** drop it.
- The register path writes `version = 0` explicitly in the conditional insert above. Whether
  `save()` on a fresh entity writes `0` or `1` must be **verified against Spring Data JDBC at
  stage 4** rather than asserted here; it does not affect this feature, because the conditional
  insert is the only thing that creates a `submissions` row and nothing updates one. The
  integration test asserts the inserted row's `version` is **not null**.
- A required integration test: save a brand-new aggregate against a real PostgreSQL container,
  assert the row exists and `version` is set — then read it back through the mapper, `reconstitute`
  it, and assert the version survived.

The same trap applies to any entity whose id is domain-generated, which is every aggregate root in
this codebase.

### 9.3 Command adapter

```java
@Component
class SubmissionCommandSqlAdapter implements SubmissionCommandRepository {

    @Override
    public Result<SubmissionId, RegisterSubmissionError.ReferenceAlreadyUsed> insert(
            SubmissionSnapshot snapshot) {
        // insertIfReferenceFree(...) -> 0 means duplicate: Result.failure(ReferenceAlreadyUsed)
        //                              1 means inserted:  Result.success(new SubmissionId(id))
    }
}
```

**No `@Transactional`, because there is nothing for it to span.** The adapter issues one
statement, and a single statement is atomic by itself. This is a direct consequence of the port
split in [§3.5](#35-the-port-split-and-the-trade-off-it-accepts): the earlier combined port wrote
two rows and therefore needed a transaction, and with publishing behind its own port this method
has one row to write. An `@Transactional` added here would be decoration.

**If a later publishing adapter needs the fact to be atomic with the state change**, a transaction
has to span both port calls, and two things that are easy to get wrong become live:

- `@Transactional` is **silently ignored on a non-public method** by proxy-based AOP, so the
  boundary would fail invisibly rather than loudly. Any method carrying it must be `public` — the
  port methods already are; the **classes** stay package-private, which CGLIB proxies without
  trouble.
- The boundary cannot go in `domain/` and should not go on a controller. Where it goes is part of
  the deferred decision in [§12](#12-events-and-the-publishing-port), not something to improvise.

Integration tests required of this adapter: a brand-new aggregate inserts and the row carries a
non-null `version`; a duplicate `(tenant_id, external_ref)` leaves the earlier row untouched and
inserts nothing; the same reference under a different `tenant_id` inserts normally (BR-08).

### 9.4 Query adapter

`SubmissionQuerySqlAdapter implements SubmissionQueryRepository` destructures the
`SubmissionSearchCriteria` into the bound parameters of the two statements in [§6](#6-sql-shape) and
returns the projection records untouched. It never loads `SubmissionJdbcEntity` and never sees the
aggregate (it cannot: `Submission` is package-private in another package).

---

## 10. Logging and masking

| Field | May it be logged? | Rule | How this design enforces it |
|---|---|---|---|
| `insuredLimit` | **Never** | BR-22 | masked `***` in the `toString()` of `RegisterSubmissionCommand`, `SubmissionSnapshot`, `SubmissionSummaryView`, `SubmissionRegistered`; no error variant carries the amount; no `ProblemDetail` echoes it |
| `externalRef` | Yes, in full — **with category-Cc code points escaped as `\uXXXX`** | BR-23, BR-29 | printed in every `toString()`; carried by `ReferenceAlreadyUsed`. Escaping is not redaction: the value stays complete and readable, and an unescaped control character in a log line is log injection ([§3.2](#32-registersubmissionerror--exactly-nine-variants)) |
| `tenantId` | Not classified | spec §6 | printed; it is the isolation boundary, not a secret |
| `actorId` / `registeredBy` | **Not logged** | OPEN-01 | masked `***` in the `toString()` of `SubmissionSnapshot`, `SubmissionSummaryView` and `SubmissionRegistered`; no error variant carries it |
| `registeredAt` | Not classified | spec §6 | printed |

**`actorId` carries an open business item and the strict default has been applied.** The business
has never been asked whether the registering caller's identity may appear in a log (spec §6;
state board OPEN-01). The orchestrator applied the strict side — **do not log it** — so every
record in this design that holds it masks it in `toString()`, and no `*Error` variant carries it.
This is an engineering default standing in for a missing business decision, not a decision:
`@sec-reviewer` must confirm it at stage 6, and if the business later permits it, the masks are the
single place to change.

Masking lives in `toString()` only. A serialiser works through record accessors, not `toString()`,
so the API response and whatever the publishing adapter emits still carry the real values — which is
required: BR-22 is a rule about **logs**, and the spec states explicitly that the insured limit
travels inside `SubmissionRegistered` because downstream systems need the amount. Whichever
publishing adapter is chosen inherits the obligation not to log the amount, as the spec says.

**The amount must also stay out of the `ProblemDetail` for the three refusals that are about it**
(BR-10, BR-11, BR-12). The reason may be named — "insuredLimit must have exactly two decimal
places" — and the value may not be shown, not even rounded.

**Filter bounds are masked too.** `SearchSubmissionsQuery.toString()` masks `insuredLimitFrom` and
`insuredLimitTo`. BR-22 literally governs "an insured limit", and a filter bound is a comparison
value rather than a registered amount — but a logged search for
`insuredLimitFrom=1200.00, insuredLimitTo=1200.00` that returns one row discloses that row's
amount. Masking is the strict reading; flagged in [§11](#11-open-questions).

---

## 11. Open questions

Not an empty section, and none of these blocks stage 3.

1. **`actorId` in logs (OPEN-01, carried from the state board).** Never put to the business.
   Strict default applied: not logged, masked everywhere. `@sec-reviewer` must confirm at stage 6.
2. **How the caller is authenticated is undecided and nothing in the repository provides it.**
   `spring-boot-starter-security` is **not** in `pom.xml`, and the epistemic gate forbids adding a
   dependency to make a design work. What *is* decided, and confirmed by the product owner, is that
   `ActorContextResolver` is **fail-closed**: no identity source configured means no actor, means
   `CallerNotIdentified`, never an invented actor ([§13.1](#131-web-edge)). What remains open is the
   identity source itself — an **open dependency for stage 4 and for `@sec-reviewer`**. A
   header-trusting resolver is not authentication and must never ship outside a test profile.
3. **"At most 64 characters" — code points or UTF-16 code units?** BR-29 restricts the reference's
   character set only to the extent of excluding category Cc, so a reference may still contain
   supplementary characters, where the two measures differ. Code points chosen, because it matches
   PostgreSQL `VARCHAR(64)` and so cannot overflow the column. A reference of 64 emoji is accepted
   under this reading and would be refused under the other. BR-29 does not settle this and the same
   measure is now used by two rules, so getting it wrong misreports two refusals instead of one.
4. **Masking the insured-limit filter bounds.** An interpretation of BR-22, not a stated rule.
   Flagged rather than applied silently.
5. **"Newest first" is not a total order.** `registered_at` is not unique, so BR-24 alone cannot
   satisfy BR-28. A deterministic `id DESC` tie-break was added. Technical, but the product owner
   should know that submissions registered in the same microsecond have an order nobody specified.
6. **Transport-level failures sit outside the spec's refusal model.** `size=abc` or
   `insuredLimitFrom=xyz` is rejected by Spring's type conversion as a 400 before the use case runs.
   That is not a business refusal and deliberately does not become a second
   `SearchSubmissionsError` variant, but it is a response shape the spec does not describe.
7. **BR-13 leaves the amount field unbounded input.** "No maximum amount exists, and none may be
   introduced without a business decision" is respected (unconstrained `NUMERIC`), but PostgreSQL
   `numeric` accepts over 100 000 digits, so a hostile caller can post an enormous literal. The only
   mitigation available without a business decision is a transport-level HTTP request body size
   limit, which is not a business maximum. Raised for `@sec-reviewer`.
8. **How the event is published is deliberately undecided** —
   [§12.1](#121-deferred-adapter-decision-how-the-event-is-published) lists the candidates and
   chooses none. This is an open *adapter* decision for stage 4, not an open business question, and
   it is the only reason the announcement is at-most-once today
   ([§3.5](#35-the-port-split-and-the-trade-off-it-accepts)).
9. **A single `Clock` bean.** Declared in `SubmissionConfiguration` because no shared
   configuration package exists. The second module that needs one must move it to a shared
   configuration rather than declare a second.
10. **Logging a refused reference requires escaping, and that needs confirming against BR-23.**
    BR-23 says the reference may appear in a log "in full and readable". For a reference refused
    under BR-29 — the only references that can now contain a control character — writing it raw is
    **log injection**: a NEWLINE forges an extra log entry and an escape sequence can manipulate
    whoever reads the log. This design escapes every category-Cc code point as `\uXXXX`, which is
    both readable and inert, and argues that satisfies BR-23 rather than bending it. An
    interpretation, not a stated rule, and the only item on this list with a security consequence
    if it is got wrong. For `@sec-reviewer`.
11. **"No restriction" rules need an adapter check before they are confirmed.** The standing lesson
    from [§8.3](#83-what-the-schema-never-constrained-the-references-character-set), carried
    forward rather than left in a post-mortem: a rule that grants unrestricted input is a strong
    claim, it is only as true as the narrowest constraint beneath it, and a unit test against a
    permissive fake cannot verify it. Applies to BR-13's unbounded amount (item 7) today, and to
    the next such rule whenever it is written.
12. **A 500 for a valid input is now explicitly a specification defect, and one path can still
    produce one.** The spec's refusal-reason definition says a caller must never receive an
    unexplained failure for an input it calls valid. A publishing failure
    ([§3.5](#35-the-port-split-and-the-trade-off-it-accepts)) still answers `500`. The argument that
    this is legitimately an outage rather than a missing refusal reason — the same input usually
    succeeds, and the state change did happen — is made there and should be reviewed, not assumed.

### Settled since the first revision of this document

- **`submissionId` in the search result** — confirmed by the product owner ([§5.3](#53-views)).
- **0-based page numbering** — confirmed by the product owner ([§5.1](#51-the-query-record--and-where-the-page-request-is-normalised)).
- **Fail-closed `ActorContextResolver`, no Spring Security in this module** — confirmed
  ([§13.1](#131-web-edge)); the identity source behind it stays open, as item 2 above.
- **Publishing is a port, not a transport** — architectural correction accepted; it withdrew the
  combined port ([§3.5](#35-the-port-split-and-the-trade-off-it-accepts)) and removed every topic,
  message key, wire contract and relay from this design ([§12](#12-events-and-the-publishing-port)).
- **Control characters in a reference are refused (BR-29)** — decided by the product owner on
  2026-11-02 after exploratory testing, who was offered the narrow NUL-only fix and chose the whole
  of category Cc. This design's earlier "no character-set restriction exists" reasoning is
  **superseded**, and the reversal it carries (TAB, NEWLINE and NUL move from valid to refused) is
  deliberate. SPACE is untouched: it is not a control character, and a reference of spaces is still
  valid, still not trimmed.

### Contradiction found — reported, and since fixed

The spec is internally consistent; I found nothing in it that is wrong. The one conflict was with a
**repository skill**, not the spec: `.github/skills/hexagonal-cqrs-slice/SKILL.md` illustrated the
aggregate with `externalRef.isBlank()` and a variant named `BlankExternalReference`, and
`NonPositiveInsuredLimit(BigDecimal insuredLimit)` carrying the amount — the first would have
refused the whitespace-only reference that BR-04 plus BR-07 make *valid*, and the second violates
BR-22. **That skill has since been corrected** (`isEmpty()`, variant names matching the spec, and
two new checklist items: no sensitive value in an error record, and validation must match the
specification literally). The rule still stands in this design for anyone reading an older copy:
**`isEmpty()`, and no amount inside any error variant.**

BR-29 does **not** reopen that question, and this is the one place the two are easy to confuse.
`isBlank()` is still wrong: it refuses a reference of spaces, which the spec still calls valid.
BR-29 refuses category Cc, which excludes SPACE. A reader who takes BR-29 as licence to switch to
`isBlank()` reintroduces the original defect and breaks a rule the product owner has now confirmed
twice.

---

## 12. Events and the publishing port

| Event | Raised when | Carries | Announced through |
|---|---|---|---|
| `SubmissionDomainEvent.SubmissionRegistered` | a registration succeeds (BR-15) | organisation, submission identity, reference, insured limit, registering caller, registration moment | `SubmissionEventPublisher` — a port. What is behind it is not decided here |

```java
public sealed interface SubmissionDomainEvent {

    UUID eventId();

    SubmissionId aggregateId();

    UUID tenantId();

    Instant occurredAt();

    record SubmissionRegistered(UUID eventId,
                                SubmissionId aggregateId,
                                UUID tenantId,
                                String externalRef,
                                BigDecimal insuredLimit,
                                String registeredBy,
                                Instant occurredAt) implements SubmissionDomainEvent {

        @Override
        public String toString() {   // BR-22 amount, OPEN-01 actorId
            return "SubmissionRegistered[eventId=" + eventId + ", aggregateId=" + aggregateId
                    + ", tenantId=" + tenantId + ", externalRef=" + externalRef
                    + ", insuredLimit=***, registeredBy=***"
                    + ", occurredAt=" + occurredAt + "]";
        }
    }
}
```

The sealed interface has one variant today because the spec says one business fact occurs in this
feature. Searching announces nothing — a read is not a business fact — and the event is never raised
for a refused registration, which this design makes structural through `Result.peek`, whose side
effect runs on the success branch only (see [§3.3](#33-use-case)).

`eventId` is a property of the fact — a stable identity for "this announcement", which is what lets
any eventual recipient recognise a repeat. It is not a transport artefact, so it stays on the record.

### 12.1 Deferred adapter decision: how the event is published

**No decision is made here, and that is the design.** The domain publishes a business fact through
a port. What sits behind that port is an adapter decision, and this module can be built, tested and
reviewed without it: nothing in `domain/`, `query/`, the schema in [§8](#8-schema) or the two
use cases changes with the answer.

Stage 4 chooses **the simplest adapter that satisfies the port and is genuinely testable.** The
candidates, with no choice expressed between them:

| Candidate | What it buys | What it costs |
|---|---|---|
| In-memory structure (the port's test fake, promoted) | nothing to configure; the port is exercised end to end | the fact never leaves the process |
| Spring `ApplicationEventPublisher` | in-process listeners, no infrastructure, trivially testable | not atomic with the insert; nothing crosses a process boundary |
| A table written in the same transaction as the insert, plus a separate relay | the fact survives a crash; at-least-once delivery | needs a transaction spanning both port calls, a relay, and a decision about where the boundary lives ([§9.3](#93-command-adapter)) |
| Any broker client, direct | reaches other systems now | a dual write: the commit and the send are two systems, and a crash between them loses the fact with no trace |

Four constraints the choice must respect, all of which follow from decisions already made rather
than from any transport:

- **It must not log the insured limit** (BR-22, [§10](#10-logging-and-masking)), and it must not log
  `actorId` while OPEN-01 is open.
- **A domain record is not a wire format.** If the adapter crosses a process boundary it needs its
  own versioned contract type in its own package; serialising `SubmissionRegistered` directly would
  weld every recipient to this module's internals. If it does not cross one, it needs no contract at
  all — which is part of why this is deferred.
- **If the chosen mechanism orders or routes by key, the key is the aggregate id.** That is a
  property of the event (one submission's facts belong in one sequence), not a design choice being
  smuggled in: the `aggregateId` accessor on the sealed interface is all the adapter needs, and this
  document names no mechanism that would use it.
- **Whatever it writes to, check first that the store can hold every value the event carries** —
  the lesson of [§8.3](#83-what-the-schema-never-constrained-the-references-character-set), applied
  before rather than after. The event carries `externalRef`, and BR-29 now guarantees it is free of
  control characters, so a line-delimited or text-based format is safe from a reference that breaks
  a record boundary. That guarantee comes from the domain, not from the adapter, and an adapter must
  not quietly assume anything stronger than it.

`outbox_events` and `inbox_events` from `V1__init_outbox_and_inbox.sql` **stay where they are and
this module does not have to use them.** Their existence is not an argument for any of the rows
above. There is no relay in `src/main`, and this design does not require one.

---

## 13. Wiring

```java
@Configuration
class SubmissionConfiguration {

    @Bean
    Clock clock() { return Clock.systemUTC(); }

    @Bean
    SubmissionIdGenerator submissionIdGenerator();            // UUID.randomUUID() behind the port

    @Bean
    RegisterSubmissionUseCase registerSubmissionUseCase(SubmissionCommandRepository repository,
                                                    SubmissionEventPublisher eventPublisher,
                                                    SubmissionIdGenerator idGenerator,
                                                    Clock clock);

    @Bean
    SearchSubmissionsUseCase searchSubmissionsUseCase(SubmissionQueryRepository repository);
}
```

How it fits together:

```
SubmissionCommandController ──> RegisterSubmissionUseCase (domain, no Spring)
                                      │ port: SubmissionCommandRepository
                                      │          └─> SubmissionCommandSqlAdapter  @Component
                                      │                  └─> SubmissionCommandJdbcRepository
                                      │ port: SubmissionEventPublisher
                                      │          └─> ??? adapter DEFERRED — §12.1
                                      │ port: SubmissionIdGenerator
                                      ▼
                                 Result<SubmissionId, RegisterSubmissionError>

SubmissionQueryController ───> SearchSubmissionsUseCase (query, no Spring)
                                      │ port: SubmissionQueryRepository
                                      ▼
                       SubmissionQuerySqlAdapter  @Component
                                      └─> SubmissionQueryJdbcRepository  (native SQL -> views)
```

The two use cases are **plain classes constructed in the configuration**, not `@Component`-scanned:
that is what keeps `domain/` and `query/` free of Spring imports. The adapters are package-private
`@Component`s; Spring Data repository interfaces are package-private and discovered automatically.

The `SubmissionEventPublisher` bean is the one piece of wiring this design leaves open
([§12.1](#121-deferred-adapter-decision-how-the-event-is-published)). Whatever stage 4 picks, it is
a single bean satisfying a single-method port, so the choice touches this configuration class and
nothing else — which is the test of whether the deferral was real.

### 13.1 Web edge

```java
record RegisterSubmissionRequest(@Nullable String externalRef, @Nullable BigDecimal insuredLimit) { }
```

- **No Bean Validation annotations on the request record.** `spring-boot-starter-validation` is on
  the classpath, but `@NotNull` / `@Positive` / `@Digits` / `@Pattern` would intercept BR-04,
  BR-05, BR-29, BR-10, BR-11 and BR-12 and answer with a framework-shaped 400, bypassing the named
  refusal reasons and making **six of the nine** error variants unreachable. Validation of business
  inputs happens in the aggregate. BR-29 makes this more tempting, not less — a `@Pattern` is the
  obvious way to express "no control characters" and is exactly the wrong place for it: it would
  turn the refusal the product owner just asked for back into an unnamed framework error, which is
  the defect BR-29 exists to fix.
- **`insuredLimit` must be declared `BigDecimal`.** Any `double` or `float` in the request record
  destroys the scale and makes BR-12 unenforceable. Nothing in the pipeline normalises it.
- **Binding the actor, fail-closed.** `ActorContextResolver` returns `Optional<ActorContext>`; the
  controller runs the use case call inside `ActorContext.callWith(actor, () -> useCase.execute(command))`
  when present, and calls the use case **unbound** when absent, so that `CallerNotIdentified` is
  produced in the domain, in one place, for web and non-web callers alike.

  **The resolver is fail-closed, confirmed by the product owner.** With no identity source
  configured it returns `Optional.empty()` — it never invents, defaults or infers an actor, a
  tenant or a role. The request is then refused as `CallerNotIdentified` (`401`), which is the
  correct answer under BR-01 and the only safe one: a resolver that manufactures an actor when it
  cannot identify one turns BR-01 into decoration and makes every tenant boundary in BR-17
  meaningless. There is no Spring Security in this module, and none is added to make the design
  work.

  **A header-trusting resolver must never ship.** Reading `X-Tenant-Id` / `X-Actor-Id` /
  `X-Actor-Role` off the request and believing them is not authentication: any caller can set
  those headers and become any actor in any organisation, which is total cross-tenant compromise
  of BR-17 and total bypass of BR-02. If such a resolver exists at all it exists as a
  **test-profile-only** bean that cannot be active in any deployed profile, and `@sec-reviewer`
  must verify that at stage 6. Tests do not need it: they inject the actor directly through
  `ActorContext.callWith(actor, ...)`, which is what that helper is for.

  This is deliberately **not** a servlet filter wrapping `chain.doFilter` in
  `ActorContext.runWith`: `doFilter` declares `IOException` and `ServletException`, `runWith` takes
  a `Runnable`, and bridging the two means catching and re-raising a checked exception inside
  `src/main` — awkward at best, and a trap next to the Checkstyle `noThrowNew` gate. Binding the
  scope around the use case call only means no checked exception ever crosses the scope.

- **Folding the `Result`.** An exhaustive `switch` over the sealed error type with **no `default`
  branch**, so the compiler reports any future variant instead of a 500 appearing in production.
  BR-29 is the worked example of why that matters: adding a ninth variant to the sealed interface
  makes every `switch` over it fail to compile until the new refusal is given a status and a
  message. A `default` branch would have absorbed it silently and shipped the new refusal as
  whatever the fallback happened to be.

- **The reference is escaped on the way into any `ProblemDetail` or log line** — every code point
  of category Cc rendered as `\uXXXX`. See [§3.2](#32-registersubmissionerror--exactly-nine-variants):
  a raw control character in a log is log injection, and the BR-29 refusal path is where such a
  reference is guaranteed to appear.

| Outcome | HTTP |
|---|---|
| success | `201 Created`, `Location: /api/submissions/{id}`, body carries the id |
| `CallerNotIdentified` | `401` |
| `RoleMayNotRegister` | `403` |
| `ReferenceMissing`, `ReferenceTooLong`, `ReferenceContainsControlCharacter`, `InsuredLimitMissing`, `InsuredLimitNotPositive`, `InsuredLimitPrecisionInvalid` | `400` |
| `ReferenceAlreadyUsed` | `409` |

`GET /api/submissions?reference=&insuredLimitFrom=&insuredLimitTo=&registeredFrom=&registeredTo=&page=&size=`
returns `200` with `SubmissionPageView`, or `401` for `CallerNotIdentified` — the only refusal
Search has.

Every `ProblemDetail` carries a stable code and a user-safe message: no stack trace, no SQL
fragment, no internal id, **no insured limit** (BR-22), and nothing that reveals another
organisation's data. `ReferenceAlreadyUsed` is safe to return as `409` precisely because
uniqueness never crosses organisations (BR-08): the conflicting submission is always one of the
caller's own.

---

## 14. Not doing

Explicitly out of scope, so the next agent does not add it:

- **No status / lifecycle column or state machine.** The spec defines no state beyond "registered".
- **No decision, approval, referral or declinature** operation, and no event about one.
- **No amend, withdraw, cancel or delete** operation. A submission is immutable once registered,
  which is why `submissions` has no `UPDATE` path in this design.
- **No authority limit** anywhere. BR-03 treats `UNDERWRITER` and `SENIOR_UNDERWRITER` identically;
  `ActorRole.canDecide()` is the whole check.
- **No currency field** in any record, column, event or API payload. Every amount is euro by
  definition (BR-14).
- **No maximum insured limit** (BR-13) — hence unconstrained `NUMERIC`, not `NUMERIC(19, 2)`.
- **No character-set restriction on the reference beyond BR-29.** Control characters (category Cc)
  are refused and that is the **only** such restriction: no trimming, no case folding, no Unicode
  normalisation, no allow-list of scripts, no rejection of spaces, punctuation, symbols or emoji
  (BR-07, BR-29). Hence `COLLATE "C"`. A wider character rule needs a business decision, exactly as
  BR-29 got one.
- **No retention or archival** of submissions.
- **No `Forbidden` outcome and no cross-tenant lookup.** Another organisation's submission is
  invisible, never forbidden (BR-17).
- **No search audit event.** Whether a read should be reported to audit was never asked of the
  business.
- **No transport, topic, message key, wire contract or relay for the event.** The domain publishes
  through `SubmissionEventPublisher` and knows nothing else; the adapter behind it is a stage-4
  decision ([§12.1](#121-deferred-adapter-decision-how-the-event-is-published)). `outbox_events`
  and `inbox_events` exist in V1 and this module is **not** obliged to use them.
- **No second refusal reason for a failed announcement.** The spec's refusal list is exhaustive; an
  infrastructure failure is not a business outcome ([§3.5](#35-the-port-split-and-the-trade-off-it-accepts)).
- **No second refusal reason on Search.** Oversized page requests clamp; see
  [§7](#7-engineering-decision-maximum-page-size).
- **No keyset pagination.** The business asked for page numbers.
- **No JPA, Hibernate, QueryDSL or jOOQ**, no `com.fasterxml.jackson`, no `@Entity` on an
  aggregate, no `*Service` class, no `application` package, no `throw new` in `src/main`, and no
  JEP 507 primitive type patterns (preview is off).

---

Design written to `docs/adr/submission-registration-design.md`. Open a new chat and call
`@test-designer` to turn it into contracts and failing tests.
