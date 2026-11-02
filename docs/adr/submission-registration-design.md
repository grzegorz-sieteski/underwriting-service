# Design: submission registration

Mode: **`@architect` design mode.** Produced from `docs/specs/submission-registration-spec.md`
(BR-01..BR-30) following `.github/skills/spec-to-plan/SKILL.md`. No production or test code was
written; every signature and every SQL statement below lives in this document only.

Every business number here traces to the spec. The one number the spec deliberately delegates —
the maximum page size — is recorded in [§7](#7-engineering-decision-maximum-page-size) as an
engineering decision with its reasoning. Nothing else in this document invents a business rule.

> **Revision 3 — read this first.** Two product-owner decisions reshape the module. Everything
> below has been brought into line with them; the places that moved are named here so nobody has
> to diff.
>
> - **Parse, don't validate.** A use case only ever receives values that are valid by
>   construction: `ExternalReference` and `InsuredLimit` on the write side, `SubmissionFilter` and
>   `ReferenceCriterion` on the read side, `ActorContext` for the caller. Raw `String` and
>   `BigDecimal` enter `domain/` and `query/` only as the parameter of a method named `parse`.
>   See [§15](#15-value-objects).
> - **Authorisation is an adapter concern.** Who may call which use case is decided by one guard,
>   applied where the use cases are wired, in this order: identify the caller (401), check the
>   permission for that use case (403), parse the request, execute. The domain knows no roles,
>   produces no 401 or 403 and does not re-check. A rule that depends on data stays in the domain.
>   See [§16](#16-the-access-guard).
> - **The caller-facing contract is unchanged.** Registration is still refused for exactly nine
>   reasons and search for exactly one. They are now produced in two layers: registration as
>   2 + 7, search as 1 + 0. See [§3.2](#32-refusals-nine-as-the-caller-sees-them) and
>   [§5.2](#52-refusals-search-still-has-exactly-one).
> - **Two earlier designs were rejected** and are recorded so they are not reintroduced: parse
>   first and judge the role second, and a domain-owned permission re-checked by the use case.
>   See [§17](#17-rejected-designs).
> - **What moves** — every class, test and invariant this touches — is listed in
>   [§18](#18-what-moves).
> - **Questions for the product owner** are collected in [§11](#11-open-questions).

---

## 1. Scope

Covers, and only covers, the two operations the spec puts in scope:

- **register a submission** — one command; **nine** refusal reasons as the caller sees them
  (BR-01, BR-02, BR-04..BR-06, BR-09..BR-12, BR-29): two decided at the adapter, seven in the
  domain
- **search submissions** — one query; **one** refusal reason as the caller sees it (BR-01),
  decided at the adapter. The query side itself refuses nothing (BR-16..BR-28, BR-30)

Module: **`submission`** — one business capability, nameable without "and" because registration
and search are the write and read side of the same capability, not two capabilities.

```
com.gs5.underwriting.submission
├── domain/      WRITE side.  Pure Java. Aggregate package-private. Knows no roles.
├── query/       READ side.   Projections only. Cannot import domain/. Knows no roles.
└── adapters/
    ├── access/       the guard: who may call which use case. Plain Java, no Spring.
    ├── web/
    ├── persistence/
    ├── <the publishing adapter — deferred, see §12>
    └── config/       the one place that may see a raw use case
```

There is **no `application` package and no `*Service` or `*Facade` class.** The use case *is* the
application boundary; Checkstyle `noServiceSuffixDeclaration` plus ArchUnit
`no_procedural_service_or_facade_layer` fail the build on the alternative.

`domain/` and `query/` import only `java.*`, `com.gs5.underwriting.shared.kernel.*` and
`org.jspecify.annotations.*`. Each package carries a `package-info.java` with `@NullMarked`.

**"The domain knows no roles" is enforced, not hoped for.** `ActorRole` lives in
`adapters/access/`, and the existing ArchUnit rule `write_and_read_models_stay_pure_java` already
forbids `domain/` and `query/` from depending on anything in `..adapters..`. Putting the role
there is what turns the product owner's decision into a build failure rather than a convention.

---

## 2. File inventory

```
shared/kernel/                              (stays tiny: four types)
├── Result.java                             unchanged
├── ActorContext.java                       CHANGED: final class, tenantId + actorId only, parse(...)
├── ControlCharacters.java                  NEW: the single definition of Unicode category Cc
└── LogSafeText.java                        CHANGED: delegates the category test to ControlCharacters

submission/
├── domain/
│   ├── package-info.java                   @NullMarked
│   ├── SubmissionId.java                   public record
│   ├── ExternalReference.java              NEW  public final class — value object, parse(...)
│   ├── InsuredLimit.java                   NEW  public final class — value object, parse(...)
│   ├── RegisterSubmissionCommand.java      public record of the two value objects + parse(...)
│   ├── RegisterSubmissionError.java        public sealed interface — 7 variants
│   ├── RegisterSubmissionUseCase.java      public final class — execute(ActorContext, Command)
│   ├── SubmissionCommandRepository.java    public interface — outbound port
│   ├── SubmissionEventPublisher.java       public interface — outbound port
│   ├── SubmissionIdGenerator.java          public interface — outbound port
│   ├── SubmissionSnapshot.java             public record — persistence contract
│   ├── SubmissionDomainEvent.java          public sealed interface
│   └── Submission.java                     PACKAGE-PRIVATE aggregate
├── query/
│   ├── package-info.java                   @NullMarked
│   ├── SubmissionFilter.java               NEW  public sealed interface — Narrowing | MatchesNothing
│   ├── ReferenceCriterion.java             NEW  public final class — query-side value object
│   ├── SearchSubmissionsQuery.java         public record (filter + page request; clamps the page)
│   ├── SearchSubmissionsUseCase.java       public final class — ask(ActorContext, Query)
│   ├── SubmissionSearchCriteria.java       public record — tenant + five filters
│   ├── SubmissionSummaryView.java          public record — one result row
│   ├── SubmissionPageView.java             public record — page + total count
│   └── SubmissionQueryRepository.java      public interface — read port, returns views
│                                           (SearchSubmissionsError is DELETED — see §5.2)
└── adapters/
    ├── access/       NEW   ActorRole (moved out of the kernel), AuthenticatedCaller,
    │                       AccessRefusal + CallerNotIdentified + PermissionDenied,
    │                       Permission, OperationGate, Admission
    ├── web/                SubmissionCommandController, SubmissionQueryController,
    │                       RegisterSubmissionRequest (gains toCommand()), RegisterRefusal (union),
    │                       ActorContextResolver (now yields Optional<AuthenticatedCaller>)
    ├── persistence/        SubmissionJdbcEntity, SubmissionCommandJdbcRepository,
    │                       SubmissionQueryJdbcRepository, SubmissionCommandSqlAdapter,
    │                       SubmissionQuerySqlAdapter, SubmissionPersistenceMapper   (package-private)
    ├── ?                   the SubmissionEventPublisher adapter. Technology and package are a
    │                       deferred adapter decision — see §12. This design does not name a
    │                       transport, a topic, a message key, a wire contract or a relay.
    └── config/             SubmissionConfiguration — builds the guarded gates

```

---

## 3. Commands (write side)

| Command record | Use case method | Returns | Event on success |
|---|---|---|---|
| `RegisterSubmissionCommand` | `execute(ActorContext actor, RegisterSubmissionCommand command)` | `Result<SubmissionId, RegisterSubmissionError.ReferenceAlreadyUsed>` | `SubmissionDomainEvent.SubmissionRegistered` |

The use case refuses on exactly one ground, because by the time it runs everything else has been
decided: the caller was identified and permitted at the adapter, and the input was parsed into
values that cannot be invalid. What the *service* answers to a caller is richer, and is set out in
[§3.2](#32-refusals-nine-as-the-caller-sees-them).

### 3.1 The command record

```java
public record RegisterSubmissionCommand(ExternalReference externalRef, InsuredLimit insuredLimit) {

    /**
     * Reference first (missing, too long, control character), then the amount (missing, not
     * positive, wrong precision) — the order of judgement in the specification. The first
     * failure is the refusal; nothing later is examined.
     */
    public static Result<RegisterSubmissionCommand, RegisterSubmissionError> parse(
            @Nullable String externalRef, @Nullable BigDecimal insuredLimit);
}
```

**It is still a standalone `public record`.** There is no shared `Command` interface and no
`handle`, and the ArchUnit rule `commands_and_queries_are_standalone_records` is unchanged and
still passes. What changed is the components: two value objects instead of a nullable `String` and
a nullable `BigDecimal`, so **no `@Nullable` is left on the write side**. A record's canonical
constructor is public, and that is safe here for the one reason that matters: both components are
valid by construction and the spec states no rule that relates them, so no combination of the two
is invalid. A type that *does* guard an invariant cannot be a record
([§15.1](#151-the-rules-every-value-object-follows)).

The command carries neither the organisation, nor the registering caller, nor the registration
moment (BR-15): those come from the admitted `ActorContext` and from the `Clock`.

`parse` is where the specification's order of judgement for steps 3 and 4 lives, **once, in the
domain**, so a second adapter cannot judge the reference and the amount in a different order.
Whether the caller may call the operation at all is not decided here, or anywhere in the domain
([§16](#16-the-access-guard)); `parse` is simply never reached for a caller who was refused.

| Input | Required | Constraint | Enforced by | Refused as |
|---|---|---|---|---|
| reference | yes | present and not empty (BR-04); at most 64 code points (BR-05); no category-Cc character (BR-29); never trimmed or case-folded (BR-07) | `ExternalReference.parse` | `ReferenceMissing`, `ReferenceTooLong`, `ReferenceContainsControlCharacter` |
| insured limit | yes | present (BR-10); strictly positive (BR-11); exactly two decimal places (BR-12); no upper bound (BR-13); euro, no currency (BR-14) | `InsuredLimit.parse` | `InsuredLimitMissing`, `InsuredLimitNotPositive`, `InsuredLimitPrecisionInvalid` |

### 3.2 Refusals: nine as the caller sees them

The specification's list for registration is exhaustive and is the service's contract with its
callers: **nine reasons**. That contract does not change. What changes is *which layer produces
which reason*, because the product owner moved the permission decision to the adapter.

```java
// adapters/access — produced by the guard, never by the domain
public sealed interface AccessRefusal permits CallerNotIdentified, PermissionDenied { }
public record CallerNotIdentified() implements AccessRefusal { }                 // BR-01
public record PermissionDenied(ActorRole role) implements AccessRefusal { }      // BR-02

// domain — produced by the value objects' parse and by the persistence port
public sealed interface RegisterSubmissionError {
    record ReferenceMissing() implements RegisterSubmissionError { }                          // BR-04
    record ReferenceTooLong(int maxLength, int actualLength)
            implements RegisterSubmissionError { }                                            // BR-05
    record ReferenceContainsControlCharacter(int codePoint, int index)
            implements RegisterSubmissionError { }                                            // BR-29
    record InsuredLimitMissing() implements RegisterSubmissionError { }                       // BR-10
    record InsuredLimitNotPositive() implements RegisterSubmissionError { }                   // BR-11
    record InsuredLimitPrecisionInvalid(int requiredScale, int actualScale)
            implements RegisterSubmissionError { }                                            // BR-12
    record ReferenceAlreadyUsed(String externalRef)
            implements RegisterSubmissionError { }                                            // BR-06, BR-09
}
```

| # | Spec refusal reason | Layer | Produced by | Rule | HTTP |
|---|---|---|---|---|---|
| 1 | Caller not identified | **adapter** | `OperationGate.admit` | BR-01 | 401 |
| 2 | Role may not register | **adapter** | `OperationGate.admit`, as `PermissionDenied(role)` | BR-02 | 403 |
| 3 | Reference missing | domain `Result` | `ExternalReference.parse` | BR-04 | 400 |
| 4 | Reference too long | domain `Result` | `ExternalReference.parse` | BR-05 | 400 |
| 5 | Reference contains a control character | domain `Result` | `ExternalReference.parse` | BR-29 | 400 |
| 6 | Insured limit missing | domain `Result` | `InsuredLimit.parse` | BR-10 | 400 |
| 7 | Insured limit not positive | domain `Result` | `InsuredLimit.parse` | BR-11 | 400 |
| 8 | Insured limit precision invalid | domain `Result` | `InsuredLimit.parse` | BR-12 | 400 |
| 9 | Reference already used | domain `Result` | the persistence port, inside `execute` | BR-06, BR-09 | 409 |

The numbering is the spec's order of judgement, and it is the order they are tried in. Rows 1-2
are decided before any input is parsed; rows 3-8 are decided when the adapter calls the domain's
`parse`; row 9 is the only one `execute` itself can return. **Rows 3-8 are domain-defined and
adapter-invoked:** the rules and the error values live in `domain/`, and the adapter calls them at
the point the order requires.

What this costs, stated plainly: the set of nine is no longer one sealed type, so the compiler can
no longer prove the list complete from a single `switch` over a single hierarchy. Two things put
that proof back.

1. The web edge folds one **union** type, `RegisterRefusal` ([§13.1](#131-web-edge)), with a single
   flat `switch` over nested record patterns and **no `default`**, so a tenth variant added to
   either hierarchy fails to compile at the one place that renders it. (Checked in a scratch
   compile of exactly this shape with `javac` 25: a flat nested-pattern switch over
   `Access(CallerNotIdentified)`, `Access(PermissionDenied(...))` and the domain variants is
   accepted as exhaustive, with no `default`.)
2. A **refusal-catalogue contract test** ([§16.7](#167-tests)) enumerates the nine spec reasons,
   drives each one through the edge, asserts its status and problem type, and asserts by
   reflection that `AccessRefusal` plus `RegisterSubmissionError` have exactly those nine leaves.
   Adding or removing a reason without touching the spec fails the build.

`PermissionDenied` is the guard's generic word. The register endpoint renders it as the spec's
"role may not register" (`role-may-not-register`, `403`, "The role VIEWER may not register a
submission."), so **every `ProblemDetail` is byte-for-byte what it was before this revision.**
Tests that assert refusals must therefore assert on the problem type and status, not on a class
name.

**`AccessRefusal` is deliberately shared across operations.** This design's usual rule is one
`*Error` per operation, because a business failure belongs to one operation. Identification and
permission are not business failures of an operation; they are the access concern in front of
every operation, and the product owner placed them there. Two hierarchies each holding their own
`CallerNotIdentified` would be duplication of a single fact. The architect audit rule "no shared
bag of errors across operations" is about business errors and should say so
([§11.2](#112-repository-conventions-this-design-asks-to-change)).

**No variant carries a sensitive value** (BR-22, OPEN-01). No variant carries the insured limit:
`InsuredLimitNotPositive` carries nothing, and `InsuredLimitPrecisionInvalid` carries two scales,
which are a property of the notation and not of the magnitude. `ReferenceContainsControlCharacter`
carries two **numbers**, the code point and its zero-based code-point index, and never the
character itself: a raw NEWLINE in an error record forges a log line and a raw NUL truncates one.
`ReferenceAlreadyUsed` carries the reference, which BR-23 allows, and is rendered with
category-Cc code points escaped ([§10](#10-logging-and-masking)). `PermissionDenied` carries the
role, which identifies nobody. No variant carries `actorId`.

### 3.3 Use case

```java
public final class RegisterSubmissionUseCase {

    public RegisterSubmissionUseCase(SubmissionCommandRepository repository,
                                     SubmissionEventPublisher eventPublisher,
                                     SubmissionIdGenerator idGenerator,
                                     Clock clock);

    public Result<SubmissionId, RegisterSubmissionError.ReferenceAlreadyUsed> execute(
            ActorContext actor, RegisterSubmissionCommand command);
}
```

Its shape is two statements: build the aggregate, which cannot fail ([§4](#4-aggregate)), then
insert it and, on success only, publish the event.

```java
var submission = Submission.register(idGenerator.nextSubmissionId(), actor,
        command.externalRef(), command.insuredLimit(), clock.instant());
return repository.insert(submission.toSnapshot())
        .peek(_ -> eventPublisher.publish(submission.registeredEvent(idGenerator.nextEventId())));
```

Four points about it:

- **The actor is a parameter, not read from the scoped value.** A use case that read
  `ActorContext.current()` would have to decide what to do when nothing is bound, and the only
  honest answer is a refusal — which would put "caller not identified" back in the domain, the
  re-check the product owner rejected. Taking the actor as a parameter makes the question
  unaskable. The parameter is supplied by `Admission.run`, never by the controller, so the adapter
  cannot hand the use case a different actor from the one it admitted
  ([§16.2](#162-the-shape)). The use case reads `actor.tenantId()` and `actor.actorId()` and
  nothing else; there is no role to read.
- **The error type is narrowed to the one variant `execute` can return.** The port already
  returns `Result<SubmissionId, ReferenceAlreadyUsed>`, so the pipeline passes it straight through:
  the `.mapError(error -> error)` widening the previous revision needed is gone, and a test can no
  longer be written that expects `execute` to return a reference or amount refusal, because the
  type says it cannot.
- **`peek` is what makes "no event for a refused registration" structural.** It runs its side
  effect on the success branch only, so a `ReferenceAlreadyUsed` cannot reach the publisher. The
  insert comes first for the same reason: the BR-09 race is settled by the database, so nothing is
  announced until the row exists.
- Only methods already on `shared/kernel/Result.java` are used (`peek`). No combinator is
  invented at the call site.

What happens if publishing fails after the insert succeeded is in
[§3.5](#35-the-port-split-and-the-trade-off-it-accepts). No `@Transactional`, no `@Service`, no
Spring import.

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
final class Submission {              // PACKAGE-PRIVATE — not public, no public constructor

    private final SubmissionId id;
    private final UUID tenantId;                  // BR-15: the admitted caller's organisation
    private final ExternalReference externalRef;  // value object — valid by construction
    private final InsuredLimit insuredLimit;      // value object — valid by construction
    private final String registeredBy;            // ActorContext.actorId, already parsed
    private final Instant registeredAt;
    private final @Nullable Long version;         // plain Long, no annotation, null when new

    /** New submission. Total: it cannot fail, because nothing it receives can be invalid. */
    static Submission register(SubmissionId id, ActorContext actor,
                               ExternalReference externalRef, InsuredLimit insuredLimit,
                               Instant registeredAt);

    /** Rebuilds state loaded from the database by parsing the stored values again. */
    static Optional<Submission> reconstitute(SubmissionSnapshot snapshot);

    SubmissionSnapshot toSnapshot();                                  // version stays as held

    SubmissionDomainEvent.SubmissionRegistered registeredEvent(UUID eventId);
}
```

`SubmissionSnapshot` is unchanged and public. It is the persistence contract, an *outbound* record
the aggregate fills, so it keeps raw `String` and `BigDecimal` fields and its explicit masking
`toString()`; the rule that raw input enters only through `parse` is about *input*
([§15.9](#159-enforcement)).

### 4.1 What the aggregate looks like when it receives value objects

**`register` can no longer fail.** Previously this factory checked seven invariants and returned a
`Result`. Every one of them has moved to the type that owns the value:

| Invariant | Rule | Was | Now |
|---|---|---|---|
| role permits registering | BR-02, BR-03 | `Submission.register` | the guard, in `adapters/access` ([§16](#16-the-access-guard)) |
| reference present | BR-04 | `Submission.register` | `ExternalReference.parse` |
| reference length | BR-05 | `Submission.register` | `ExternalReference.parse` |
| no control character | BR-29 | `Submission.register` | `ExternalReference.parse` |
| insured limit present | BR-10 | `Submission.register` | `InsuredLimit.parse` |
| insured limit positive | BR-11 | `Submission.register` | `InsuredLimit.parse` |
| exactly two decimals | BR-12 | `Submission.register` | `InsuredLimit.parse` |
| reference unused in the organisation | BR-06, BR-09 | the store (unique index) | the store (unchanged) |

This is not a hollowed-out aggregate hiding a design flaw, and it is worth saying why. The
specification gives a submission **no state transitions, no status, and no rule that relates one
field to another**, so there is nothing for an aggregate to protect *beyond* what its fields'
types already guarantee. The invariants did not disappear; they became unrepresentable.

What the aggregate still owns is exactly what is left: assigning identity, recording ownership by
the admitted organisation (BR-15), stamping the registration moment, producing the snapshot with
its version, and producing `SubmissionRegistered`. It remains the right home for the first rule
that *does* span fields, depend on state or depend on data. **That is the boundary the product
owner drew:** permission to call an operation is decidable from the caller and the operation
alone, so it left; a rule that needs the aggregate stays. The test for which side a rule falls on
is in [§16.1](#161-the-principle).

### 4.2 `reconstitute` parses too

`reconstitute` has **no production caller in this feature**, because a submission is read-only
once registered and no command loads one. It is specified because it is half of the snapshot
contract: the persistence round trip loads the inserted row back through the mapper and
`reconstitute`s it, which is what proves `version` survives both directions.

It does not skip validation. A row read from the database is external data — a legacy row, a
schema drift, a manual edit — and "parse, don't validate" applies at every boundary, so it runs the
same `ExternalReference.parse` and `InsuredLimit.parse` and returns `Optional.empty()` when a stored
value no longer parses. There is no unchecked "trusted" factory on the value objects, because a
second way in is exactly how the guarantee erodes.

This is not hypothetical: **BR-29 is retroactive over stored data.** A submission registered with
a TAB in its reference before BR-29 existed would no longer reconstitute. A failure to parse a
stored row is a data-integrity condition, not a business refusal of any operation in this feature,
so it does not belong in `RegisterSubmissionError`; the first command that loads a submission
defines its own variant for it.

`register` sets `version = null`. That null is what tells the persistence adapter this aggregate
has never been stored — see [§9.2](#92-the-version-trap--read-this-before-writing-the-mapper).

Other aggregates would be referenced by typed id only. None exists here.

### 4.3 The order of judgement, end to end

| Step | Judged | Where | Refusal, in the order it is tried |
|---|---|---|---|
| 1 | caller identified | `OperationGate.admit` | `CallerNotIdentified` |
| 2 | role permits registering | `OperationGate.admit` | `PermissionDenied` |
| 3 | reference | `RegisterSubmissionCommand.parse` → `ExternalReference.parse` | missing, then too long, then control character |
| 4 | insured limit | `RegisterSubmissionCommand.parse` → `InsuredLimit.parse` | missing, then not positive, then precision |
| 5 | reference against history | `execute` → the persistence port | `ReferenceAlreadyUsed` |

Every position is pinned by a test. The reasoning the previous revision gave still holds, inside
the factories:

- **Role before input** (steps 1-2 before 3-4): [§16.3](#163-the-order-at-the-edge).
- **Presence before shape:** `""` trivially contains no control character, so a control-character
  check placed first would pass an empty reference and report the real defect later and less
  clearly.
- **Length before content:** matches the spec's own order, and bounds the control-character scan
  to at most 64 code points — a 10 MB reference ending in a TAB is refused for length without being
  scanned for the TAB. A reference that is both too long and contains a control character is
  refused as `ReferenceTooLong`.
- **Positivity before precision:** `BigDecimal.ZERO` has `scale() == 0`, so it violates both BR-11
  and BR-12; this order makes it `InsuredLimitNotPositive`, the more fundamental fact, and the
  spec's own worked examples list `0.00` under BR-11.
- **History last:** it is the only refusal that costs a database round trip.

---

## 5. Queries (read side)

| Query record | Use case method | Returns | View records |
|---|---|---|---|
| `SearchSubmissionsQuery` | `ask(ActorContext actor, SearchSubmissionsQuery query)` | `SubmissionPageView` | `SubmissionPageView`, `SubmissionSummaryView` |

`query/` imports nothing from `domain/` — not `SubmissionId`, not `SubmissionSnapshot`, and now not
`ExternalReference` either. The read side carries raw `UUID` and has its own value object for the
one input that needs one ([§5.1](#51-the-query-record-and-the-filter-parse)).

**The return type is the view itself, not a `Result`.** The query side refuses nothing: every
search that reaches it succeeds, including the ones that match nothing (BR-20), have a
contradictory range (BR-21), lie past the end (BR-27) or carry a control character in the
reference filter (BR-30). A `Result<_, E>` needs an `E` with at least one variant, and a sealed
interface cannot have zero; inventing a placeholder variant to keep the shape would be exactly the
"speculative variant" this design forbids. The repository rule that every use case returns a
`Result` was written when every use case could refuse, and needs a carve-out
([§11.2](#112-repository-conventions-this-design-asks-to-change)).

### 5.1 The query record and the filter parse

```java
public record SearchSubmissionsQuery(SubmissionFilter filter, int page, int size) {

    public static final int MIN_PAGE_SIZE = 1;
    public static final int MAX_PAGE_SIZE = 100;   // engineering decision, §7

    public SearchSubmissionsQuery {
        page = Math.max(0, page);
        size = Math.clamp(size, MIN_PAGE_SIZE, MAX_PAGE_SIZE);
    }
}

public sealed interface SubmissionFilter {

    /** Every supplied criterion can match something: safe to hand to the store. */
    record Narrowing(@Nullable ReferenceCriterion reference,
                     @Nullable BigDecimal insuredLimitFrom,
                     @Nullable BigDecimal insuredLimitTo,
                     @Nullable Instant registeredFrom,
                     @Nullable Instant registeredTo) implements SubmissionFilter { }
                     // toString masks both insured-limit bounds (BR-22, strict reading)

    /** BR-30: the reference filter provably matches nothing. The store is never asked. */
    record MatchesNothing() implements SubmissionFilter { }

    /** Total: never fails, never refuses. */
    static SubmissionFilter parse(@Nullable String reference,
                                  @Nullable BigDecimal insuredLimitFrom,
                                  @Nullable BigDecimal insuredLimitTo,
                                  @Nullable Instant registeredFrom,
                                  @Nullable Instant registeredTo);
}

public final class ReferenceCriterion {          // a final class: it guards an invariant
    static Optional<ReferenceCriterion> parse(String text);   // empty iff text contains a category-Cc character
    public String value();
}
```

All five filters are optional (BR-19) and combine with AND (BR-18). In `Narrowing`, `null` means
"this criterion was not supplied" — a meaning, not unparsed input — and it survives to a bound SQL
parameter (`:param IS NULL OR ...`), which an `Optional` cannot. Filter bounds are **not**
re-validated against BR-11 or BR-12: those are rules about what may be *registered*, and a bound may
be negative, zero, or carry any scale. `SearchSubmissionsQuery` prints safely by composition,
because `Narrowing` masks the bounds itself.

**How `query/` represents a filter that can match nothing.** `SubmissionFilter.parse` is total: it
never fails, it returns one of two shapes.

- `Narrowing` — every criterion is satisfiable. Its `reference`, if present, is a
  `ReferenceCriterion`, a value that **cannot contain a control character**, so nothing in a
  `Narrowing` can make the store fail.
- `MatchesNothing` — the reference filter contains a category-Cc character. No such reference can
  have been registered, because BR-29 refuses every one, so the search matches nothing. This is the
  whole of BR-30, as a type.

**Why a type and not a check in front of the database.** The defect this fixes (SEC-10) is that a
filter containing U+0000 reached PostgreSQL, which cannot represent it, and answered `500` for an
input the spec calls a successful empty search. A guard in front of the store is one more place to
forget. Here the port's input, `SubmissionSearchCriteria`, is built only from a `Narrowing`, a
`Narrowing` holds only a `ReferenceCriterion`, and a `ReferenceCriterion` cannot be built from text
containing a control character. **The adapter never sends such a filter to the database because
there is no value it could send.** The filter is never "cleaned up": no character is stripped or
replaced to make it match something (BR-30), and a filter of spaces only is an ordinary
`ReferenceCriterion`, because SPACE is not a control character.

**Why `query/` has its own value object and does not reuse `ExternalReference`.** Two reasons.
`query/` cannot import `domain/` (ArchUnit and the package-private aggregate agree). And the
semantics differ: an invalid reference at registration is a *refusal*, an invalid reference as a
filter is a *successful empty search*; sharing the type would force one of the two to be wrong.
What is shared is the definition of the category itself, in the kernel (`ControlCharacters`,
[§15.5](#155-the-single-definition-of-category-cc)), so the two cannot drift.

**Only the reference needs a value object.** The other four criteria are `BigDecimal` and
`Instant`, and every value of those types is a legal comparison value (the spec says so for the
amounts). A contradictory range (BR-21) is deliberately *not* given a `MatchesNothing`
representation: unlike BR-30 it does not break the store, the predicate `>= 5000 AND <= 1000` simply
matches no row, and a second code path for it would be a second implementation of a rule the SQL
already implements correctly. The distinction is this design's rule in miniature: **a typed
representation is for what the store *cannot* represent, not for what it merely finds empty.**
`MatchesNothing` can gain a reason later without touching its callers if that ever changes.

**Page numbering is 0-based** (`offset = page * size`), confirmed by the product owner. The page
request normalises itself inside the record's compact constructor, so the use case, the adapter and
the response body cannot disagree about what was applied: `size` is clamped into
`[MIN_PAGE_SIZE, MAX_PAGE_SIZE]` and `page` to `>= 0`, and an out-of-range request clamps and never
refuses ([§7](#7-engineering-decision-maximum-page-size)).

### 5.2 Refusals: search still has exactly one

The spec's list for search is exhaustive and deliberate: **one** reason, caller not identified
(BR-01), because once the caller is known every search succeeds. That is unchanged. What the
sentence means now that the query side returns no error:

- It is a property of the **service**, not of a domain type. `SearchSubmissionsError` is
  **deleted**; its single variant was always the access concern's, and is now `CallerNotIdentified`
  in `adapters/access`.
- It is **typed**, not merely documented. The search gate is built with the permission
  `anyIdentifiedCaller()`, whose refusal type is `CallerNotIdentified` alone
  ([§16.2](#162-the-shape)), so `OperationGate.admit` for search returns
  `Result<Admission<...>, CallerNotIdentified>` and the search controller's `switch` has exactly
  one case. A second reason would not compile.
- It is **tested**. The contract test asserts that search is never refused for a role (BR-16) by
  driving every `ActorRole` value through the gate, and that matching nothing, a contradictory
  range, a page past the end and a control character in the reference filter are all successes
  with an empty page and a total of zero.

`CallerNotIdentified` is the same record the register endpoint uses. That sharing is deliberate
([§3.2](#32-refusals-nine-as-the-caller-sees-them)).

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
                                       @Nullable ReferenceCriterion reference,
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

The only change in this revision is the type of `reference`: it was a `String` and is now the value
object, so the persistence adapter unwraps `reference.value()` into the bound parameter and the SQL
in [§6](#6-sql-shape) is unchanged.

`offset` is `long`: `page * size` with `page` near `Integer.MAX_VALUE` overflows an `int` and would
silently produce a negative offset. The use case computes `(long) query.page() * query.size()`.

### 5.5 Query use case

```java
public final class SearchSubmissionsUseCase {

    public SearchSubmissionsUseCase(SubmissionQueryRepository repository);

    public SubmissionPageView ask(ActorContext actor, SearchSubmissionsQuery query);
}
```

Its shape is a `switch` over the sealed filter with **no `default`**, so a third filter shape added
later fails to compile here instead of being silently treated as one of the two:

```java
return switch (query.filter()) {
    case SubmissionFilter.Narrowing narrowing -> pageOf(actor.tenantId(), narrowing, query);
    case SubmissionFilter.MatchesNothing _ ->
            new SubmissionPageView(List.of(), 0L, query.page(), query.size());
};
```

- **`MatchesNothing` answers without touching the repository:** an empty page, a total of zero, and
  the applied page and size echoed (BR-30, BR-20, BR-26). A test asserts that the repository fake
  received no call, which is the test that proves the database is never sent such a filter.
- **`Narrowing`** builds a `SubmissionSearchCriteria` from `actor.tenantId()` and the filter, and
  asks the repository for the page and the total.
- **The use case never reads a role.** There is none to read (BR-16: a role neither narrows nor
  widens a search), and the actor it receives is the one the gate admitted. It reads
  `actor.tenantId()`, which is the only thing BR-17 allows to decide what comes back.
- **BR-20, BR-21 and BR-27 still need no code branch.** A predicate of `>= 5000 AND <= 1000`
  matches no row, so the page is empty and the count is zero; an `OFFSET` past the end returns no
  rows while the count stays true. Each of the three is a test assertion, not a special case.

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
is reached, as a transport-level 400. That is not a business refusal and is not one of the
spec's refusal reasons; it is noted in [§11](#11-open-questions) (Q1, Q6). Like every framework
conversion, it runs before the guard.

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
  it (which re-parses the stored values, [§4.2](#42-reconstitute-parses-too), and returns an
  `Optional`), and assert both that it parsed and that the version survived.

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
`SubmissionSearchCriteria` into the bound parameters of the two statements in [§6](#6-sql-shape) —
unwrapping `reference.value()` into the `externalRef` parameter, so the SQL is unchanged — and
returns the projection records untouched. It never loads `SubmissionJdbcEntity` and never sees the
aggregate (it cannot: `Submission` is package-private in another package).

---

## 10. Logging and masking

| Field | May it be logged? | Rule | How this design enforces it |
|---|---|---|---|
| `insuredLimit` | **Never** | BR-22 | `InsuredLimit.toString()` prints `***`, so `RegisterSubmissionCommand` is safe by composition; the raw holders — `SubmissionSnapshot`, `SubmissionSummaryView`, `SubmissionRegistered`, and the filter bounds in `SubmissionFilter.Narrowing` and `SubmissionSearchCriteria` — keep explicit masks; no error variant carries the amount; no `ProblemDetail` echoes it |
| `externalRef` | Yes, in full — **with category-Cc code points escaped as `\uXXXX`** | BR-23, BR-29 | a valid `ExternalReference` or `ReferenceCriterion` cannot contain a control character, so it prints as it is; the raw holders (`SubmissionSnapshot`, `SubmissionRegistered`, `ReferenceAlreadyUsed`, the `ProblemDetail`) escape it. Escaping is not redaction: the value stays complete and readable, and an unescaped control character in a log line is log injection ([§3.2](#32-refusals-nine-as-the-caller-sees-them)) |
| `tenantId` | Not classified | spec §6 | printed; it is the isolation boundary, not a secret |
| `actorId` / `registeredBy` | **Not logged** | OPEN-01 | masked `***` in the `toString()` of `ActorContext` (its first, upstream holder), `AuthenticatedCaller`, `SubmissionSnapshot`, `SubmissionSummaryView` and `SubmissionRegistered`; no error variant carries it |
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

Not an empty section. None of these blocks stage 3, but the first group needs the product owner
before the guard's behaviour can be called final.

### 11.1 Questions for the product owner

- **Q1 — Does "an unauthorised caller's input is never examined" include the framework's
  deserialisation of the request body?** With `@RequestBody`, Spring and Jackson convert the body
  into `RegisterSubmissionRequest` (a `String` and a `BigDecimal`) *before* the controller method,
  and therefore before the guard, runs. The same holds for the `@RequestParam` conversion of the
  search amounts and instants. Our own code examines nothing before the guard, but a library parser
  is reachable by a caller who is about to be answered `401` or `403`. It is the surface the
  rejected parse-first plan handed over, in miniature: a flat two-field record, a library number
  parser, no business rule. Closing it fully means taking the body as an unparsed `String` and
  deserialising after `admit`, at the cost of owning the malformed-JSON response and the content
  negotiation Spring gives for free.
  *Recommendation: accept it now and record it.* The proper fix belongs with the identity-source
  decision: authentication in a servlet filter ahead of MVC dispatch precedes body conversion by
  construction, while the per-use-case permission stays in the gate. Is that acceptable, or must
  the body be read only after the guard?
- **Q2 — Is a caller with an unrecognised role "caller not identified" (`401`) rather than "role
  may not register" (`403`)?** The spec's glossary says a caller is known by organisation,
  identity *and* role together, and BR-01 says no caller is known when any of the three is
  unavailable, so a role claim that matches none of the three roles means no caller is known. This
  design follows that reading. It also means a claim spelled `viewer` is not recognised as `VIEWER`:
  the match is exact and case-sensitive on the role name, and the resolver, not the guard, maps a
  token's spelling to it.
- **Q3 — What shape may the caller's identity take?** The spec never says. This design requires
  `actorId` to be non-blank, at most 255 code points and free of control characters, for a storage
  reason and not a business one: it is written to `registered_by VARCHAR(255)`, so a longer value
  or one containing U+0000 would be a spec-valid caller that the store cannot hold — the BR-29
  defect again, one column over ([§15.6](#156-the-caller-from-claims)). Confirm, or give the real
  shape.
- **Q4 — Is "search consults no role at all" the intended reading of BR-16?** Search is declared
  as "any identified caller", not as an explicit list of the three roles. The two are identical
  today. They differ when a fourth role is added to `ActorRole`: "any identified caller" lets it
  search, an explicit list would refuse it until someone decided. BR-16 says a role "neither
  narrows nor widens" a search, which is the first reading, and it makes "search has exactly one
  refusal reason" true by type. Register is the opposite on purpose: an explicit list, so a new
  role cannot register until a decision is made.
- **Q5 — BR-13 says the insured limit has no maximum; what does "valid by construction" mean at
  the edge of what the store and the transport accept?** PostgreSQL `numeric` documents a limit of
  131072 digits before the decimal point (to be verified for the PostgreSQL version in use), and
  Jackson's default stream constraints cap the length of a number token (1000 characters in the
  2.15+ line; to be verified for Jackson 3). Both are
  far above any real amount, and both turn a spec-valid input into an unexplained failure, which is
  precisely the BR-29 defect. Options: accept and document the effective ceiling; or take a
  business-approved maximum number of digits. BR-13 forbids introducing a maximum without a
  decision, so this design introduces none.
- **Q6 — What should an unparseable filter value, or an empty reference filter, do?** The spec
  says no filter value is ever refused (BR-20, BR-21, BR-30), yet `insuredLimitFrom=abc` or
  `registeredFrom=yesterday` is rejected with `400` by Spring's type conversion, outside the
  one-reason list and before the guard. Separately, `reference=` (present but empty) is silent in
  the spec: this design lets it match nothing, because no registered reference is empty, which is
  the literal reading of BR-07 ("compared exactly as supplied"), but "not supplied" is an arguable
  alternative. Options for the first: keep the `400` as a transport error and reword the spec to
  say "no well-formed filter value"; or bind the parameters as text, parse after the guard, and
  treat an unparseable one as matching nothing.
- **Q7 — Does the spec's wording need to change for the layer split? This design says no.** The
  spec says "every refusal reason a command can produce is listed" and that the only thing judged
  before a search runs is that the caller is identified. Read as statements about the *service's*
  operations, both remain exactly true, and the spec deliberately names no layer. Confirm that
  reading, since the use case itself now refuses far less than the list ([§3.2](#32-refusals-nine-as-the-caller-sees-them)).

### 11.2 Repository conventions this design asks to change

For the coordinator, not the product owner. Each is a place where a written repository rule says
something this revision contradicts, and the next reviewer will cite it unless it is amended.

- **R1 — "Every use case returns `Result<T, ThatOperationsError>`"**
  (`copilot-instructions.md` §3, the CQRS skill). Search's use case now returns the view, because
  it cannot refuse. Carve-out: a use case that cannot refuse returns its value.
- **R2 — The architect audit rule "no shared bag of errors across operations".**
  `AccessRefusal` is shared by both operations on purpose. Scope the rule to business errors.
- **R3 — `domain-cqrs-purity.instructions.md`:** "every operation that depends on who is asking
  reads `ActorContext.current()`", "validate invariants inside the aggregate, not in a caller", and
  "`ActorRole`… is read inside `domain/`". Now: the actor is a parameter, a value's invariants live
  in its value object, and the role lives in `adapters/access/` and is read nowhere in `domain/`.
- **R4 — `security-owasp-java.instructions.md` A08 and the `sec-reviewer` rule "`[CRITICAL]` an
  inbound record… with no bounds in its compact constructor".** That rule would flag
  `RegisterSubmissionRequest`, which is raw by design, and would not flag the command. Bounds are
  enforced by the value objects' `parse`. Reword: every inbound field must reach a `parse`; a raw
  DTO is acceptable only as the argument of one. Otherwise review will "fix" this design back into
  compact-constructor validation.
- **R5 — `ActorContext.CURRENT`, `callWith`, `runWith` and `current()` now have no production
  consumer.** The use cases take the actor as a parameter. They are retained this round, because
  deleting them also means amending a Checkstyle rule, `copilot-instructions.md` §1 and
  `ActorContextTest`, which is a separable decision. Delete them, or keep them for the next module.
- **R6 — `ActorRole.canDecide()` is deleted.** It has no consumer, and "may decide" is a different
  concept from "may register"; keeping it invites its reuse as the register permission.
- **R7 — Two existing ArchUnit rule messages are now stale, though their logic still holds.**
  `command_controllers_touch_only_the_write_side` and `query_controllers_touch_only_the_read_side`
  say a controller "talks to its `*UseCase`"; it now talks to an `OperationGate`. The conditions
  are unchanged and still pass (the query controller depends on nothing in `..domain..`); only the
  `.because(...)` text needs amending, so the next reader is not told something false.
- **R8 — Six new ArchUnit rules** ([§15.9](#159-enforcement), [§16.4](#164-bypass-is-a-build-failure)),
  each to be broken on purpose once, as `AGENTS.md` requires of any structural gate.

### 11.3 Engineering items carried

1. **`actorId` in logs (OPEN-01, carried from the state board).** Never put to the business.
   Strict default applied: not logged, masked everywhere. `@sec-reviewer` must confirm at stage 6.
2. **How the caller is authenticated is undecided and nothing in the repository provides it.**
   `spring-boot-starter-security` is **not** in `pom.xml`, and the epistemic gate forbids adding a
   dependency to make a design work. What is decided, and confirmed by the product owner, is that
   `ActorContextResolver` is **fail-closed**: no identity source configured means no caller, which
   the gate answers `401`, never an invented caller ([§13.1](#131-web-edge)). What remains open is
   the identity source itself — an open dependency for stage 4 and for `@sec-reviewer`. A
   header-trusting resolver is not authentication and must never ship outside a test profile.
3. **"At most 64 characters" — code points or UTF-16 code units?** BR-29 restricts the reference's
   character set only to the extent of excluding category Cc, so a reference may still contain
   supplementary characters, where the two measures differ. Code points chosen, because it matches
   PostgreSQL `VARCHAR(64)` and so cannot overflow the column. A reference of 64 emoji is accepted
   under this reading and would be refused under the other.
4. **Masking the insured-limit filter bounds.** An interpretation of BR-22, not a stated rule.
   Flagged rather than applied silently.
5. **"Newest first" is not a total order.** `registered_at` is not unique, so BR-24 alone cannot
   satisfy BR-28. A deterministic `id DESC` tie-break was added. Technical, but the product owner
   should know that submissions registered in the same microsecond have an order nobody specified.
6. **Transport-level failures sit outside the spec's refusal model.** A malformed body, or
   `size=abc`, is rejected by the framework with a `400` before the controller runs. That is not a
   business refusal and deliberately does not become a variant of anything, but it is a response
   shape the spec does not describe. See Q1 and Q6.
7. **How the event is published is deliberately undecided** —
   [§12.1](#121-deferred-adapter-decision-how-the-event-is-published) lists the candidates and
   chooses none. This is an open *adapter* decision for stage 4, not a business question, and it is
   the only reason the announcement is at-most-once today
   ([§3.5](#35-the-port-split-and-the-trade-off-it-accepts)).
8. **A single `Clock` bean.** Declared in `SubmissionConfiguration` because no shared configuration
   package exists. The second module that needs one must move it to a shared configuration rather
   than declare a second.
9. **Logging a refused reference requires escaping, and that needs confirming against BR-23.** BR-23
   says the reference may appear in a log "in full and readable". For a reference refused under
   BR-29 — the only references that can now contain a control character — writing it raw is **log
   injection**: a NEWLINE forges an extra log entry and an escape sequence can manipulate whoever
   reads the log. This design escapes every category-Cc code point as `\uXXXX`, which is both
   readable and inert, and argues that satisfies BR-23 rather than bending it. An interpretation,
   not a stated rule, and the only item here with a security consequence if it is got wrong. For
   `@sec-reviewer`.
10. **"No restriction" rules need an adapter check before they are confirmed.** The standing lesson
    from [§8.3](#83-what-the-schema-never-constrained-the-references-character-set), carried
    forward rather than left in a post-mortem: a rule that grants unrestricted input is a strong
    claim, it is only as true as the narrowest constraint beneath it, and a unit test against a
    permissive fake cannot verify it. It is now also a *design rule for value objects*
    ([§15.1](#151-the-rules-every-value-object-follows), rule 6) and applies to BR-13 today (Q5).
11. **A 500 for a valid input is a specification defect, and one path can still produce one.** The
    spec's refusal-reason definition says a caller must never receive an unexplained failure for an
    input it calls valid. A publishing failure ([§3.5](#35-the-port-split-and-the-trade-off-it-accepts))
    still answers `500`. The argument that this is legitimately an outage rather than a missing
    refusal reason — the same input usually succeeds, and the state change did happen — is made
    there and should be reviewed, not assumed.
12. **The guard-before-parse order is shaped by the API and pinned by tests, not enforced by the
    compiler.** `ExternalReference.parse` is public, so a controller could call it before `admit`.
    What the compiler and the build *do* enforce is that the use case is unreachable without an
    `Admission` ([§16.3](#163-the-order-at-the-edge)). Said plainly so nobody believes it is
    stronger than it is.
13. **`ExternalReference.parse` returns `RegisterSubmissionError`,** a per-operation type, from a
    value object that other operations will want. Acceptable with one operation. When a second one
    needs the reference, split the value object's own small error type and map it into each
    operation's variants rather than sharing this one.
14. **`OperationGate<C, R, X>` has three type parameters.** The third buys search's one-case
    `switch`. If it is judged too heavy, dropping it makes `PermissionDenied` a reachable branch
    of the search controller that a test must prove unreachable; nothing else changes.

### Settled since the first revision of this document

- **`submissionId` in the search result** — confirmed by the product owner ([§5.3](#53-views)).
- **0-based page numbering** — confirmed by the product owner
  ([§5.1](#51-the-query-record-and-the-filter-parse)).
- **Fail-closed `ActorContextResolver`, no Spring Security in this module** — confirmed
  ([§13.1](#131-web-edge)); the identity source behind it stays open, as item 2 above.
- **Publishing is a port, not a transport** — architectural correction accepted; it withdrew the
  combined port ([§3.5](#35-the-port-split-and-the-trade-off-it-accepts)) and removed every topic,
  message key, wire contract and relay from this design ([§12](#12-events-and-the-publishing-port)).
- **Control characters in a reference are refused (BR-29)** — decided by the product owner on
  2026-11-02 after exploratory testing, who was offered the narrow NUL-only fix and chose the whole
  of category Cc. The reversal it carries (TAB, NEWLINE and NUL move from valid to refused) is
  deliberate. SPACE is untouched: it is not a control character, and a reference of spaces is still
  valid, still not trimmed.
- **A control character in the reference filter matches nothing and is a success (BR-30)** —
  decided on 2026-11-02 after review found it answered `500` (SEC-10). Represented as a type
  ([§5.1](#51-the-query-record-and-the-filter-parse)).
- **Parse, don't validate** — decided by the product owner on 2026-11-02 ([§15](#15-value-objects)).
- **Authorisation is an adapter concern** — decided by the product owner on 2026-11-02
  ([§16](#16-the-access-guard)). The two designs it replaced are in [§17](#17-rejected-designs).

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

    // The permission table: who may call which use case, as data, declared in exactly one place.
    private static final Permission<AccessRefusal> REGISTER_SUBMISSION =
            Permission.rolesOnly(ActorRole.UNDERWRITER, ActorRole.SENIOR_UNDERWRITER);  // BR-02, BR-03
    private static final Permission<CallerNotIdentified> SEARCH_SUBMISSIONS =
            Permission.anyIdentifiedCaller();                                           // BR-16

    @Bean
    Clock clock() { return Clock.systemUTC(); }

    @Bean
    SubmissionIdGenerator submissionIdGenerator();            // UUID.randomUUID() behind the port

    @Bean
    OperationGate<RegisterSubmissionCommand,
                  Result<SubmissionId, RegisterSubmissionError.ReferenceAlreadyUsed>,
                  AccessRefusal> registerSubmissionGate(SubmissionCommandRepository repository,
                                                        SubmissionEventPublisher eventPublisher,
                                                        SubmissionIdGenerator idGenerator,
                                                        Clock clock);

    @Bean
    OperationGate<SearchSubmissionsQuery, SubmissionPageView, CallerNotIdentified>
            searchSubmissionsGate(SubmissionQueryRepository repository);
}
```

How it fits together:

```
any inbound adapter            OperationGate<C, R, X>                      raw use case
(controller, consumer, job)    ┌─────────────────────────────┐
        │                      │ Permission<X>               │       NOT a bean. Built inside the
        │ admit(caller) ─────> │   check(caller)             │       @Bean method and handed to the
        │ <── Admission | X ── │                             │       gate as a method reference.
        │                      └─────────────────────────────┘       Only adapters/config can even
        │  (parse the request — only now)                            name its class.
        └── admission.run(input) ──────────────────────────────────> execute(actor, command)
                                                                      ask(actor, query)
                                                                          │
                              ports: SubmissionCommandRepository ─────────┤
                                       └─> SubmissionCommandSqlAdapter  @Component
                                              └─> SubmissionCommandJdbcRepository
                                     SubmissionEventPublisher  └─> ??? adapter DEFERRED — §12.1
                                     SubmissionIdGenerator
                                     SubmissionQueryRepository └─> SubmissionQuerySqlAdapter @Component
                                              └─> SubmissionQueryJdbcRepository (native SQL -> views)
```

The use cases are **plain classes constructed in the configuration**, not `@Component`-scanned:
that is what keeps `domain/` and `query/` free of Spring imports. The adapters are package-private
`@Component`s; Spring Data repository interfaces are package-private and discovered automatically.

**The raw use cases are not beans.** Each is constructed inside the `@Bean` method of its gate and
passed to it as a method reference (`useCase::execute`), so there is nothing for another inbound
adapter to inject even by accident. Only this package can name the class at all, and the build
fails if any other adapter tries ([§16.4](#164-bypass-is-a-build-failure)).

**The permission table is two declarations.** Registration: `UNDERWRITER` and `SENIOR_UNDERWRITER`,
treated identically (BR-02, BR-03). Search: any identified caller, with no role consulted (BR-16).
Both live in this class and nowhere else, so who may do what can be read in one place and
reviewed in one place.

**Injection by generic type.** The two gates differ only in their type arguments, and the
controllers declare them in full. Spring resolves beans by generic type, but this design has not
run it; confirm at stage 4 that both gates resolve, and if not, inject by bean name rather than
weakening the types.

The `SubmissionEventPublisher` bean is the one piece of wiring this design leaves open
([§12.1](#121-deferred-adapter-decision-how-the-event-is-published)). Whatever stage 4 picks, it is
a single bean satisfying a single-method port, so the choice touches this configuration class and
nothing else — which is the test of whether the deferral was real.

### 13.1 Web edge

```java
record RegisterSubmissionRequest(@Nullable String externalRef, @Nullable BigDecimal insuredLimit) {

    /** The per-request-type parser: a one-line hand-off to the domain's own parse. */
    Result<RegisterSubmissionCommand, RegisterSubmissionError> toCommand() {
        return RegisterSubmissionCommand.parse(externalRef, insuredLimit);
    }
}
```

**The request record is raw on purpose.** It is the wire shape: the one place a nullable `String`
and a nullable `BigDecimal` are *meant* to exist, because it is the argument of a `parse`. The
rules that bound it live in the value objects, not in this record's constructor
([§11.2](#112-repository-conventions-this-design-asks-to-change), R4). There is deliberately no
separate `*Parser` class: the parsing rules belong to the domain, and a class whose body is
`RegisterSubmissionCommand.parse(a, b)` would be the delegating layer this design bans elsewhere.

- **No Bean Validation annotations on the request record.** `spring-boot-starter-validation` is on
  the classpath, but `@NotNull` / `@Positive` / `@Digits` / `@Pattern` would intercept BR-04,
  BR-05, BR-29, BR-10, BR-11 and BR-12 and answer with a framework-shaped `400`, bypassing the
  named refusal reasons and making **six of the nine** refusals (rows 3-8) unreachable. They would
  also examine the input *before* the guard. BR-29 makes this more tempting, not less: a
  `@Pattern` is the obvious way to express "no control characters" and is exactly the wrong place
  for it, because it would turn the refusal the product owner asked for back into an unnamed
  framework error, which is the defect BR-29 exists to fix.
- **`insuredLimit` must be declared `BigDecimal`.** Any `double` or `float` in the request record
  destroys the scale and makes BR-12 unenforceable. Nothing in the pipeline normalises it.
- **The fold is a flat, exhaustive `switch` with no `default`.** The edge needs one failure type
  for a pipeline that fails in two layers, so it declares a union:

```java
sealed interface RegisterRefusal {                           // adapters/web, package-private
    record Access(AccessRefusal refusal) implements RegisterRefusal { }
    record Rejected(RegisterSubmissionError error) implements RegisterRefusal { }
}
```

  and renders it with one `switch` over nested record patterns (nine leaf cases, no `default`):
  `Access(CallerNotIdentified)`, `Access(PermissionDenied(role))`, then the seven
  `Rejected(...)` variants. A tenth reason added to either hierarchy fails to compile here. BR-29
  was the worked example of why this matters: a new variant makes every `switch` over it fail
  until the refusal is given a status and a message, where a `default` would have shipped it as
  whatever the fallback was.

### 13.2 The register request, step by step

```
POST /api/submissions
  1. caller    = actorContextResolver.resolve()        Optional<AuthenticatedCaller>
  2. admission = registerGate.admit(caller)            401 or 403 end here. Nothing of the request
                                                       has been examined by our code.
  3. command   = request.toCommand()                   400s end here. Only reached after step 2.
  4. id        = admission.run(command)                409 ends here.
  5. 201 Created, Location: /api/submissions/{id}
```

In railway terms: `admit(...)` is mapped into `RegisterRefusal.Access`; the request's own parse and
`run` are mapped into `RegisterRefusal.Rejected`; each `flatMap` short-circuits on the first
failure, which is exactly the spec's rule that the first failure found is the one refusal and
nothing later is considered. `Result.flatMap`, `Result.mapError` and `Result.fold` are all already
on `shared/kernel/Result.java`; no combinator is invented.

| Outcome | HTTP | Problem type |
|---|---|---|
| success | `201 Created`, `Location: /api/submissions/{id}`, body carries the id | — |
| `Access(CallerNotIdentified)` | `401` | `caller-not-identified` |
| `Access(PermissionDenied(role))` | `403` | `role-may-not-register` |
| `Rejected(ReferenceMissing)` | `400` | `reference-missing` |
| `Rejected(ReferenceTooLong)` | `400` | `reference-too-long` |
| `Rejected(ReferenceContainsControlCharacter)` | `400` | `reference-contains-control-character` |
| `Rejected(InsuredLimitMissing)` | `400` | `insured-limit-missing` |
| `Rejected(InsuredLimitNotPositive)` | `400` | `insured-limit-not-positive` |
| `Rejected(InsuredLimitPrecisionInvalid)` | `400` | `insured-limit-precision-invalid` |
| `Rejected(ReferenceAlreadyUsed)` | `409` | `reference-already-used` |

Status codes, problem types and detail texts are **unchanged** from the previous revision; the
contract test ([§16.7](#167-tests)) pins them.

### 13.3 The search request, step by step

```
GET /api/submissions?reference=&insuredLimitFrom=&insuredLimitTo=&registeredFrom=&registeredTo=&page=&size=
  1. caller    = actorContextResolver.resolve()
  2. admission = searchGate.admit(caller)              401 ends here. It is the only refusal.
  3. filter    = SubmissionFilter.parse(reference, from, to, registeredFrom, registeredTo)
                                                       total: it cannot refuse
  4. page      = admission.run(new SearchSubmissionsQuery(filter, page, size))
  5. 200 with SubmissionPageView
```

The controller's refusal `switch` has exactly one case, because the gate's refusal type is
`CallerNotIdentified` alone. The transport defaults are `page=0` — the first page — and `size=100`,
the maximum, which keeps the design to one page-size number ([§7](#7-engineering-decision-maximum-page-size)).

The filter parse is total and silent, so its position relative to the guard changes no response;
it runs after the guard anyway, which keeps the spec's statement that the only thing judged before
a search runs is that the caller is identified literally true.

### 13.4 Identifying the caller, fail-closed

`ActorContextResolver` returns `Optional<AuthenticatedCaller>`.

**The resolver is fail-closed, confirmed by the product owner.** With no identity source
configured it returns `Optional.empty()` — it never invents, defaults or infers a caller, a
tenant or a role. The gate then answers `401`, which is the correct answer under BR-01 and the only
safe one: a resolver that manufactures a caller when it cannot identify one turns BR-01 into
decoration and makes every tenant boundary in BR-17 meaningless. When an identity source exists,
the resolver extracts the three claims as text and hands them to `AuthenticatedCaller.parse`; any
claim that is missing or malformed makes the whole result empty ([§15.6](#156-the-caller-from-claims)).
There is no Spring Security in this module, and none is added to make the design work.

**A header-trusting resolver must never ship.** Reading `X-Tenant-Id` / `X-Actor-Id` /
`X-Actor-Role` off the request and believing them is not authentication: any caller can set those
headers and become any caller in any organisation, which is total cross-tenant compromise of BR-17
and total bypass of the permission table. It is not even a profile-gated bean in this repository,
because a bean that exists can be activated. Tests do not need one: they build the
`AuthenticatedCaller` directly through `AuthenticatedCaller.parse` and hand it to the gate.

This is deliberately **not** a servlet filter wrapping the call in a scoped value: `doFilter`
declares `IOException` and `ServletException`, and bridging that to a `Runnable` means catching and
re-raising a checked exception inside `src/main`, a trap next to the Checkstyle zero-throw gate. It
is also no longer needed: the actor is a parameter now, so there is no scope to bind.

### 13.5 What a response may carry

Every `ProblemDetail` carries a stable code and a user-safe message: no stack trace, no SQL
fragment, no internal id, **no insured limit** (BR-22), and nothing that reveals another
organisation's data. `ReferenceAlreadyUsed` is safe to return as `409` precisely because
uniqueness never crosses organisations (BR-08): the conflicting submission is always one of the
caller's own. The reference is escaped on the way into any `ProblemDetail` or log line — every
code point of category Cc rendered as `\uXXXX` ([§10](#10-logging-and-masking)) — because the
BR-29 refusal path is where such a reference is guaranteed to appear.

---

## 14. Not doing

Explicitly out of scope, so the next agent does not add it:

- **No status / lifecycle column or state machine.** The spec defines no state beyond "registered".
- **No decision, approval, referral or declinature** operation, and no event about one.
- **No amend, withdraw, cancel or delete** operation. A submission is immutable once registered,
  which is why `submissions` has no `UPDATE` path in this design.
- **No authority limit** anywhere. BR-03 treats `UNDERWRITER` and `SENIOR_UNDERWRITER`
  identically; the register permission is a two-element set in the wiring.
- **No currency field** in any record, column, event or API payload. Every amount is euro by
  definition (BR-14).
- **No maximum insured limit** (BR-13) — hence unconstrained `NUMERIC`, not `NUMERIC(19, 2)`.
- **No character-set restriction on the reference beyond BR-29.** Control characters (category Cc)
  are refused and that is the **only** such restriction: no trimming, no case folding, no Unicode
  normalisation, no allow-list of scripts, no rejection of spaces, punctuation, symbols or emoji
  (BR-07, BR-29). Hence `COLLATE "C"`. A wider character rule needs a business decision, exactly as
  BR-29 got one.
- **No retention or archival** of submissions.
- **No `Forbidden` for a record, and no cross-tenant lookup.** Another organisation's submission
  is invisible, never forbidden (BR-17). The `403` in this design is a *permission* refusal decided
  from the caller's role alone before any data is touched, and it reveals nothing about any record
  ([§16.6](#166-403-and-404)).
- **No search audit event.** Whether a read should be reported to audit was never asked of the
  business.
- **No transport, topic, message key, wire contract or relay for the event.** The domain publishes
  through `SubmissionEventPublisher` and knows nothing else; the adapter behind it is a stage-4
  decision ([§12.1](#121-deferred-adapter-decision-how-the-event-is-published)). `outbox_events`
  and `inbox_events` exist in V1 and this module is **not** obliged to use them.
- **No second refusal reason for a failed announcement.** The spec's refusal list is exhaustive;
  an infrastructure failure is not a business outcome
  ([§3.5](#35-the-port-split-and-the-trade-off-it-accepts)).
- **No second refusal reason on Search.** Oversized page requests clamp
  ([§7](#7-engineering-decision-maximum-page-size)); a filter that can match nothing succeeds
  ([§5.1](#51-the-query-record-and-the-filter-parse)).
- **No permission check, and no role, in `domain/` or `query/`.** No `ActorRole` import, and no
  re-check "for safety": the product owner rejected it ([§17.2](#172-a-domain-owned-permission-re-checked-by-the-use-case)),
  and the existing purity rule makes it a build failure.
- **No rule that depends on data in the guard.** The guard decides from the caller and the
  operation alone ([§16.1](#161-the-principle)). An amount-based limit or a state transition stays
  in the domain.
- **No unchecked factory on a value object** — no `of`, `trusted`, `unsafe` or `fromStored`
  ([§15.1](#151-the-rules-every-value-object-follows), rule 7).
- **No inbound adapter that names a raw use case, and no raw use case as a bean**
  ([§16.4](#164-bypass-is-a-build-failure)).
- **No `MatchesNothing` for a contradictory range (BR-21).** The SQL already finds it empty; a
  typed representation is for what the store cannot represent ([§5.1](#51-the-query-record-and-the-filter-parse)).
- **No keyset pagination.** The business asked for page numbers.
- **No JPA, Hibernate, QueryDSL or jOOQ**, no `com.fasterxml.jackson`, no `@Entity` on an
  aggregate, no `*Service` or `*Facade` class, no `application` package, no `throw new` and no
  `Objects.requireNonNull` in `src/main`, and no JEP 507 primitive type patterns (preview is off).

---

## 15. Value objects

The product owner's principle: **a business operation must only ever receive valid input, and a
domain object must never exist in a half-validated state.** The way to make that true rather than
hoped for is to make invalid values unrepresentable: the adapter parses raw input into value
objects, and the use case receives them fully formed.

Two review findings were the same defect. A search filter containing U+0000 reached PostgreSQL and
answered `500` (SEC-10). And `ActorContext`, which will be built from external token claims, could
hold `null` where NullAway — which only sees types it is told about — could not see it. In both,
raw external data was entering typed code. NullAway excludes `null` at compile time wherever a type
is non-null, and that only holds if nothing `@Nullable` has to reach the domain.

### 15.1 The rules every value object follows

1. **A value that guards an invariant is a `final class` with a private constructor, never a
   record.** A record's canonical constructor must be at least as accessible as the record, so
   `public record ExternalReference(String value)` would let anyone write
   `new ExternalReference("\u0000")`. Checked with `javac` 25: a private canonical constructor on a
   public record is rejected with "invalid canonical constructor… attempting to assign stronger
   access privileges; was public". Records remain right for data with no invariant beyond its
   components' types: `SubmissionId`, the commands, the views, the errors.
2. **`parse` returns a `Result` when the spec names the refusal, and an `Optional` when it does
   not.** `Result<_, RegisterSubmissionError>` for the write-side values, whose failures the spec
   lists; `Optional` for `ActorContext`, `AuthenticatedCaller` and `ReferenceCriterion`. No `throw`
   and no `Objects.requireNonNull` — Checkstyle `noThrowNew` and `noRequireNonNull` fail the build.
3. **Raw input enters only as the parameter of a method named `parse`.** That parameter may be
   `@Nullable`, because absence is data (and for BR-04 and BR-10 it is a named refusal). Nowhere
   else in `domain/` or `query/` does a method take a raw `String` or `BigDecimal`
   ([§15.9](#159-enforcement)).
4. **Immutable, with value equality** on the wrapped value (Checkstyle `EqualsHashCode` requires
   `equals` and `hashCode` together).
5. **It owns its own log-safety.** `InsuredLimit.toString()` prints `***`. A valid
   `ExternalReference` or `ReferenceCriterion` cannot contain a control character, so its text is
   already inert. A record holding one therefore prints safely *by composition*, and the class of
   defect where a record forgot its mask (SEC-02, SEC-03, SEC-04) cannot recur through a value
   object. Raw holders — the snapshot, the event, the summary view, the filter bounds — keep their
   explicit masks.
6. **A value object admits only what the store can hold.** This is BR-29's lesson turned into a
   rule ([§8.3](#83-what-the-schema-never-constrained-the-references-character-set)): "no
   restriction" is only as true as the storage beneath it. Each value object states its storage fit
   ([§15.8](#158-storage-fit-in-one-table)).
7. **There is no second way in.** No `of`, `trusted`, `unsafe`, `fromStored`. Even the persistence
   adapter reconstitutes by parsing ([§4.2](#42-reconstitute-parses-too)). A back door is how a
   guarantee erodes, one convenient call at a time.

### 15.2 `ExternalReference`

```java
public final class ExternalReference {

    /** BR-05 — counted in code points. */
    public static final int MAX_LENGTH = 64;

    public static Result<ExternalReference, RegisterSubmissionError> parse(@Nullable String text);

    public String value();
    // equals / hashCode on value(); toString prints the value (no control character can be in it)
}
```

The three checks run in the spec's order, inside `parse`, and the first failure is the result:

| Input | Outcome |
|---|---|
| `null`, `""` | `ReferenceMissing` (BR-04) |
| `" "`, `"   "` | valid — present, non-empty, no control character, not normalised away (BR-07) |
| exactly 64 code points, including 64 emoji (128 UTF-16 units) | valid |
| 65 code points | `ReferenceTooLong(64, 65)` (BR-05) |
| 65 code points, one of them a TAB | `ReferenceTooLong` — length is judged before content |
| `"AB\tCD"`, `"\u0000"`, `"\u0085"` | `ReferenceContainsControlCharacter(codePoint, index)` (BR-29) |
| `"a b"`, `"  a  "` | valid, preserved exactly (BR-07) |

- **`isEmpty()`, not `isBlank()`.** BR-04 refuses a reference that "contains no characters at all",
  and BR-07 says two references differing in surrounding blank space are *different* references. A
  reference of spaces is valid. `isBlank()` would refuse it, and BR-29 does not change that: "control
  character" is narrower than "whitespace". TAB and NEWLINE are both; **SPACE (U+0020) is only
  whitespace**.
- **The test is the category, not a list.** `ControlCharacters.firstIn` ([§15.5](#155-the-single-definition-of-category-cc))
  is exactly Unicode category Cc — U+0000–U+001F and U+007F–U+009F. `Character.isWhitespace` and a
  `\s` regex are both wrong: they disagree with the category over most of the upper range, and
  U+0085 NEXT LINE is the case that catches them. The product owner chose the whole category over
  the narrow NUL-only fix so that no second control character has to be discovered later.
- **Code points, not `String.length()`.** The reported index is a code-point index, counted the same
  way BR-05 counts length, so the two refusals measure positions alike.
- **No trimming, no case folding, no normalisation** (BR-07). Hence `COLLATE "C"` on the column.
- **Storage fit:** at most 64 code points fits `VARCHAR(64)` exactly, since PostgreSQL counts
  characters, and the absence of every Cc character includes U+0000, which no text column can hold.
  The value object is where U+0000 is stopped now, instead of the driver.

### 15.3 `InsuredLimit`

```java
public final class InsuredLimit {

    /** BR-12 — exactly two decimal places, read as supplied. */
    public static final int REQUIRED_SCALE = 2;

    public static Result<InsuredLimit, RegisterSubmissionError> parse(@Nullable BigDecimal amount);

    public BigDecimal amount();
    // equals / hashCode on amount(); toString never shows it
}
```

Order: absent → `InsuredLimitMissing` (BR-10); `signum() <= 0` → `InsuredLimitNotPositive` (BR-11);
`scale() != 2` → `InsuredLimitPrecisionInvalid(2, actual)` (BR-12). `BigDecimal.ZERO` violates both
BR-11 and BR-12, and is told `InsuredLimitNotPositive` because the sign is checked first.

- **The scale is read as supplied.** No `stripTrailingZeros()` and no `setScale()` anywhere: either
  would make BR-12 unenforceable, because `1200` and `1200.00` would become indistinguishable and
  the spec accepts one and refuses the other. Because the scale of a valid value is always 2,
  `equals` agrees with `compareTo` for every value this type can hold.
- **`1E+2` has scale −2** and is refused as a precision error at once; an exponent cannot be used to
  build an enormous number cheaply.
- **No upper bound** (BR-13) and no currency (BR-14).
- **It carries no variant that shows the amount** (BR-22): `InsuredLimitNotPositive` is empty and
  `InsuredLimitPrecisionInvalid` carries the two scales only.
- **Storage fit:** positive, scale 2, any magnitude — which is what the unconstrained `NUMERIC`
  with its two `CHECK`s holds, *up to PostgreSQL's own digit limit.* That gap is Q5.

### 15.4 Where the constants live

| Constant | Value | Lives on | Was | Read by |
|---|---|---|---|---|
| `MAX_LENGTH` | 64 | `ExternalReference` | `RegisterSubmissionCommand.MAX_EXTERNAL_REF_LENGTH` | the factory, `ReferenceTooLong`, tests, an IT asserting the column is that wide |
| `REQUIRED_SCALE` | 2 | `InsuredLimit` | `RegisterSubmissionCommand.REQUIRED_INSURED_LIMIT_SCALE` | the factory, `InsuredLimitPrecisionInvalid`, tests |
| `MAX_ACTOR_ID_LENGTH` | 255 | `ActorContext` | — | `ActorContext.parse`, an IT asserting `registered_by` is that wide |
| `MIN_PAGE_SIZE`, `MAX_PAGE_SIZE` | 1, 100 | `SearchSubmissionsQuery` | unchanged | the compact constructor ([§7](#7-engineering-decision-maximum-page-size)) |

Each constant lives on the one type that owns the rule, so there is one place to change it and one
place a test points at. They are named `public static final` constants because Checkstyle
`noMagicNumbers` rejects the bare `64` and `255`. The two width constants are the schema's twin:
the migration comment and the IT cite them, so the column and the constant cannot drift apart
unnoticed.

### 15.5 The single definition of category Cc

The category test currently exists in `Submission` and, separately, in `LogSafeText`, and a third
copy would appear in `query/`, which cannot import `domain/`. The kernel's own justification for
`LogSafeText` — "three private copies would be three places for the category to drift" — applies
to the rule itself, so it moves there once:

```java
// shared/kernel
public final class ControlCharacters {
    private ControlCharacters() { }

    public static boolean isControl(int codePoint);           // Character.getType(cp) == Character.CONTROL
    public static Optional<Found> firstIn(String text);       // first offender, or empty

    public record Found(int codePoint, int index) { }         // index counted in code points
}
```

Callers: `ExternalReference`, `ReferenceCriterion`, `ActorContext.parse` (for `actorId`) and
`LogSafeText`. The kernel stays tiny: it gains this type and loses `ActorRole`, so it holds four
types — `Result`, `ActorContext`, `ControlCharacters`, `LogSafeText`.

### 15.6 The caller from claims

`ActorContext` is built only through a parse that returns `Optional.empty()` on anything missing or
malformed, so the fail-closed resolver yields `401`. It cannot be a record (rule 1).

```java
// shared/kernel — a final class; the constructor is private
public final class ActorContext {

    public static final int MAX_ACTOR_ID_LENGTH = 255;     // the width of registered_by

    public static Optional<ActorContext> parse(@Nullable String tenantId, @Nullable String actorId);

    public UUID tenantId();
    public String actorId();
    public boolean belongsToTenant(UUID otherTenantId);
    // toString masks actorId (OPEN-01)
}

// adapters/access
public final class AuthenticatedCaller {

    public static Optional<AuthenticatedCaller> parse(@Nullable String tenantId,
                                                      @Nullable String actorId,
                                                      @Nullable String role);
    public ActorContext actor();
    public ActorRole role();
}
```

- **`tenantId` is a UUID in its canonical textual form** — 36 characters, hexadecimal digits in the
  8-4-4-4-12 grouping — checked *before* conversion. `UUID.fromString` alone is not a parse:
  checked in a scratch run, `UUID.fromString("1-1-1-1-1")` returns `00000001-0001-0001-0001-000000000001`
  instead of failing, so a malformed claim would become a valid-looking identity, the opposite of
  fail-closed. The strict shape check comes first, and `fromString` is then called only on text
  already known to be canonical. The nil UUID is accepted; nothing in the spec says otherwise.
- **`actorId` is non-blank, at most `MAX_ACTOR_ID_LENGTH` code points, and free of control
  characters**, and is not trimmed. This is an engineering constraint, not a business rule: the spec
  never says what a caller's identity looks like, and the constraint exists because the value is
  written to `registered_by VARCHAR(255)` (Q3).
- **`role` is matched exactly, by constant name, over `ActorRole.values()`.** Never `valueOf`, which
  throws. An unknown role makes the whole result empty (Q2).
- **Anything missing or malformed yields `Optional.empty()`.** Never a partial value, and no reason:
  a reason would be an oracle for someone probing token validation. A `null` claim cannot reach a
  typed field, which is the NullAway gap this closes — NullAway cannot see a `null` that arrives
  from an unannotated library, but it can see that a `parse` takes `@Nullable` and returns a
  non-null result.
- **`AuthenticatedCaller.parse` composes `ActorContext.parse` with the role lookup.** The role is
  not part of `ActorContext` any more ([§16.5](#165-actorcontext-and-actorrole)).
- **Tests build callers through `parse`** — a small fixture such as `Actors.underwriter()` calling
  `AuthenticatedCaller.parse(tenant.toString(), "uw-01", "UNDERWRITER")` — so every test exercises
  the production path instead of a test-only constructor.

### 15.7 The read-side counterpart

`ReferenceCriterion` and `SubmissionFilter` are specified in
[§5.1](#51-the-query-record-and-the-filter-parse): `query/` has its own value object because it
cannot import `domain/` and because an invalid reference means a different thing there (a
successful empty search, not a refusal). Both sides use the same `ControlCharacters`.

### 15.8 Storage fit, in one table

| Value object | Admits | The store | Fit |
|---|---|---|---|
| `ExternalReference` | 1 to 64 code points, no category Cc | `VARCHAR(64) COLLATE "C"`, `CHECK (length > 0)`, `CHECK` no control character | exact. U+0000 is stopped here, not by the driver |
| `InsuredLimit` | strictly positive, scale 2, any magnitude | `NUMERIC`, `CHECK (> 0)`, `CHECK (scale = 2)` | exact up to PostgreSQL's digit limit (Q5) |
| `ActorContext.actorId` | non-blank, up to 255 code points, no category Cc | `registered_by VARCHAR(255)` | exact; needs the width IT |
| `ActorContext.tenantId` | any canonical UUID | `UUID` | exact |
| `ReferenceCriterion` | any text without category Cc, any length | a bound parameter compared with a `VARCHAR(64)` column | holds — a longer value simply matches nothing; the container's request-size limit bounds it in practice and no business bound exists |

### 15.9 Enforcement

Three ArchUnit rules pin the principle, each to be broken on purpose once (`AGENTS.md` requires
that of any structural gate, and `failOnEmptyShould` is `true`, so each must match something).

- **`raw_input_enters_only_through_parse`** — a method declared in a class in `..domain..` or
  `..query..` that takes a `String` or a `BigDecimal` must be named `parse`. Constructors are not
  methods, so the egress records (errors, snapshot, event, views) are not caught; they are filled
  by valid code, not by callers.
- **`commands_and_queries_carry_only_parsed_values`** — no field of a class whose name ends in
  `Command` or `Query` has type `String` or `BigDecimal`. This is the rule the product owner asked
  for: *domain objects hold no raw input fields.*
- **`guarded_types_have_no_public_constructor`** — a public class that is not a record, interface or
  enum, in `..domain..`, `..query..`, `..shared.kernel..` or `..adapters.access..`, and is not a
  `*UseCase` or `OperationGate`, has no public constructor. This is what keeps rule 1 true as new
  value objects arrive: a record cannot satisfy it, so an invariant guarded by a record is caught.

Tests, all pure and fast, with no use case and no fake in sight: `ExternalReferenceTest` (the table
above, plus a supplementary character before the offender to prove the index is in code points),
`InsuredLimitTest` (`ZERO`, `0.00`, `-0.01`, `0.01`, `1200`, `1200.5`, `1200.005`, `1E+2`, a huge
magnitude, and that `toString` never shows the amount), `RegisterSubmissionCommandParseTest` (a
reference error beats an amount error when both are present), `ControlCharactersTest` (the
boundaries U+001F, U+0020, U+007E, U+007F, U+009F, U+00A0), `SubmissionFilterTest` (a category-Cc
reference is `MatchesNothing`; spaces only is `Narrowing`; `null` is no criterion; a contradictory
range is still `Narrowing`), and two integration tests asserting `ExternalReference.MAX_LENGTH`
and `ActorContext.MAX_ACTOR_ID_LENGTH` equal the actual column widths.

---

## 16. The access guard

### 16.1 The principle

**Who may call a use case is decided in the adapter, before any input is parsed.** The adapter
knows which caller may invoke which operation; the domain does not decide who may call it. The
order at the edge is: identify the caller (`401`), check the permission for that use case (`403`),
and only then parse the request into value objects. An unauthorised caller's input is never
examined by our code. This is the product owner's decision, and the reviewer rules now say the same
(`security-owasp-java.instructions.md` A01, `sec-reviewer`).

The line between what leaves the domain and what stays is one question: **could I decide this from
the caller and the operation alone?** If yes, it is a *permission*, and it belongs at the edge. If
deciding it needs the data, it is a *business rule*, and it stays in the domain because it needs
the aggregate.

| Rule | Decidable from caller + operation alone? | Lives in |
|---|---|---|
| Only an Underwriter or Senior Underwriter may register (BR-02, BR-03) | yes | the guard |
| Every role may search (BR-16) | yes | the guard |
| A reference is unique within an organisation (BR-06, BR-09) | no — needs the store | the domain and its port |
| *(out of scope)* an Underwriter may approve up to their authority limit | no — needs the amount | the domain |
| *(out of scope)* a submission may be approved only while pending | no — needs the state | the domain |

**This is not a licence to move data-dependent rules out.** If a data-dependent rule needs a
quantity that comes from the caller — an authority limit, say — the seam is that the adapter
supplies it as a *parsed value inside the command* (an `AuthorityLimit`, derived by the adapter's
own policy from the caller's role) and the domain compares it with the amount. The domain still
never receives a role. The spec puts authority limits out of scope; this names where the seam
would be and designs nothing more.

### 16.2 The shape

```java
// adapters/access — plain Java, no Spring
public enum ActorRole { VIEWER, UNDERWRITER, SENIOR_UNDERWRITER }          // moved out of the kernel

public final class AuthenticatedCaller { /* §15.6 */ }                     // ActorContext + ActorRole

public sealed interface AccessRefusal permits CallerNotIdentified, PermissionDenied { }
public record CallerNotIdentified() implements AccessRefusal { }
public record PermissionDenied(ActorRole role) implements AccessRefusal { }

public sealed interface Permission<X extends AccessRefusal> {

    Result<AuthenticatedCaller, X> check(Optional<AuthenticatedCaller> caller);

    /** Search: any identified caller. No role is consulted (BR-16). It can refuse only CallerNotIdentified. */
    static Permission<CallerNotIdentified> anyIdentifiedCaller();

    /** Register: exactly these roles, and nobody else. Fail-closed: a new role is not in the set. */
    static Permission<AccessRefusal> rolesOnly(ActorRole first, ActorRole... others);
}

public final class OperationGate<C, R, X extends AccessRefusal> {

    /** Public only so adapters/config can build it; ArchUnit forbids anyone else (§16.4). */
    public OperationGate(Permission<X> permission, BiFunction<ActorContext, C, R> operation);

    public Result<Admission<C, R>, X> admit(Optional<AuthenticatedCaller> caller);
}

public final class Admission<C, R> {                  // no public constructor: only OperationGate mints one

    /** Runs the guarded operation for exactly the actor that was admitted. */
    public R run(C input);
}
```

Checked, not asserted: this shape was compiled with `javac` 25 in a scratch directory — a register
gate refuses a Viewer with `PermissionDenied`, a search gate admits the same Viewer, an empty
caller is `CallerNotIdentified` for both, and nothing in it needs a cast.

- **Permissions are data, declared once.** The two `Permission` values are constants in
  `SubmissionConfiguration` ([§13](#13-wiring)). The evaluation logic lives exactly once, inside the
  two implementations of `Permission`, so there is no `if (role == ...)` in a controller, a use case
  or anywhere else. `rolesOnly` takes `first` plus `others`, so an empty set — a permission that
  silently denies everyone — cannot be written.
- **`Permission` is a polymorphic method, not a `switch` in the gate,** which is why no cast is
  needed: each implementation knows its own refusal type, and the gate simply returns what the
  permission returns. That is also what lets search's gate carry `CallerNotIdentified` alone
  ([§5.2](#52-refusals-search-still-has-exactly-one)).
- **`Admission` is the proof, and the only door.** The controller never holds a reference to the
  use case. It holds a gate; the gate gives it an `Admission` or a refusal; the `Admission` runs the
  operation. `run` supplies the *admitted* actor, so the adapter cannot substitute a different one
  for the use case — there is no confused-deputy gap between "who was checked" and "who the use
  case believes is asking".
- **No permission is evaluated inside the use case.** The use case is handed an `ActorContext`
  and uses its tenant and identity; it has nothing to re-check, because it is never reached by a
  caller the gate refused.

### 16.3 The order at the edge

```
identify the caller   →   permission for THIS use case   →   parse the request   →   execute
   401                          403                              400s                  409
```

What actually holds that order, said without flattering it:

| Guarantee | Enforced by | Strength |
|---|---|---|
| The use case cannot be run without passing the permission for it | `Admission` is the only door; the raw use case is not a bean and its class is nameable only from `adapters/config` | **compiler and build** (A1, A3) |
| An `Admission` cannot be forged | its constructor is not public; only `OperationGate` calls it | **compiler and build** (A3) |
| The use case sees the admitted actor and no other | `Admission.run` supplies it | **by construction** |
| Every refusal is rendered | one flat `switch`, no `default` | **compiler** |
| Parsing the request happens after the permission | the shape of the API — the only handle to `run` comes out of `admit`, and parsing sits inside its `flatMap` — plus tests | **tests, not the compiler** |
| The framework's own body and parameter conversion happens after the permission | not enforced | **residual — Q1** |

The fifth row is the honest limit of a type-based design. `ExternalReference.parse` and
`InsuredLimit.parse` are public, because the adapter must be able to call them, so a controller
that deliberately parsed before calling `admit` would compile. The API makes the correct order the
only convenient one, and the tests below make the wrong order fail; but "the compiler forbids it" is
true of the guard being *bypassed*, not of it being *run late*. Said plainly so nobody believes it is
stronger than it is.

### 16.4 Bypass is a build failure

The security reviewer's remaining worry is a second inbound path — another controller, a consumer, a
job — that injects the raw use case and skips the guard. There is **one enforcement point**, the
gate built in `adapters/config`, and three ArchUnit rules that make going around it a build
failure. Each must match something (`failOnEmptyShould` is `true`) and each is to be broken on
purpose once.

- **A1 — `raw_use_cases_are_reachable_only_from_wiring`** — no class in `..adapters..` outside
  `..adapters.config..` depends on a class in `..domain..` or `..query..` whose simple name ends in
  `UseCase`. *Because a second inbound path that injects a raw use case skips the permission check;
  the only handle it can be given is an `Admission`.* This is the rule that pins the bypass. To
  break it on purpose: inject `RegisterSubmissionUseCase` into `SubmissionCommandController` and
  watch the build fail.
- **A2 — `gates_are_constructed_only_by_wiring`** — no class outside `..adapters.config..` calls an
  `OperationGate` constructor. *Because permissions are declared in one place; a gate built anywhere
  else is a permission nobody reviews.*
- **A3 — `admissions_are_minted_only_by_the_gate`** — no class other than `OperationGate` calls an
  `Admission` constructor. *Because an `Admission` is evidence that the permission was checked.*

Because the raw use case is also not a Spring bean ([§13](#13-wiring)), there is no bean for a
careless `@Autowired` to find. The domain's blindness is pinned by a rule that already exists:
`write_and_read_models_stay_pure_java` forbids `domain/` and `query/` from touching anything in
`..adapters..`, and `ActorRole` and every guard type are now in `..adapters.access..`. Test code is
excluded from the analysis (`DoNotIncludeTests`), which is how the unit tests construct a use case
directly.

**How a second inbound path gets the guard.** A Kafka consumer, say, injects the gate it needs, turns
its message headers into an `AuthenticatedCaller` with `AuthenticatedCaller.parse`, calls `admit`,
parses its payload with the same `parse` functions, and calls `run`. It cannot do anything else. If
no existing `Permission` fits it, it needs a new gate bean — declared in `adapters/config`, where a
reviewer will see the permission it was given.

### 16.5 `ActorContext` and `ActorRole`

Does registration still need the role? **No.** The adapter authorises, so neither `Submission` nor
`RegisterSubmissionUseCase` takes one, and nothing in `domain/` or `query/` can name it.

| Type | Before | After | Why |
|---|---|---|---|
| `ActorContext` (kernel) | `record(tenantId, actorId, role)`; public constructor; scoped-value API | `final class(tenantId, actorId)`; private constructor; `parse`; scoped-value API retained, unused ([§11.2](#112-repository-conventions-this-design-asks-to-change), R5) | the domain needs *who* and *which organisation*, not *what they may do* |
| `ActorRole` | kernel enum with `canDecide()` | `adapters/access` enum, no methods | the permission model is the adapter's; the existing purity rule then makes a domain role-check a build failure |
| `AuthenticatedCaller` | — | `adapters/access`: an `ActorContext` plus an `ActorRole` | the thing an identity source yields and the gate evaluates |
| the kernel | `Result`, `ActorContext`, `ActorRole`, `LogSafeText` | `Result`, `ActorContext`, `ControlCharacters`, `LogSafeText` | still four types |

`canDecide()` is deleted rather than moved: nothing consumes it, and "may decide" is a different
concept from "may register", so keeping it would invite its reuse as the register permission.

### 16.6 403 and 404

**Confirmed: a permission refusal reveals nothing about any tenant's data.**

- The `403` is decided from the caller's role and the operation alone, before any parse, lookup or
  query. It cannot vary with the body, the reference, this organisation's data or any other
  organisation's. It therefore cannot confirm that a record exists, in this tenant or another.
- It is a different case from the one the "`NotFound`, never `Forbidden`" rule protects, which is a
  lookup *by id* of a record that belongs to someone else. This feature has no lookup by id. When
  one arrives, another tenant's id answers `NotFound`, decided in the query side *after* the
  permission and never merged with it.
- Its body contains only the caller's own role.
- The order is part of the protection. Because the permission is judged before parsing and before
  the store, a Viewer — genuine or with stolen credentials — cannot use registration to probe the
  validation rules or, through a `409`, which references an organisation already holds. A refusal
  that arrived after parsing or after the uniqueness check would be exactly that oracle.
- `401` versus `403` says "we do not know who you are" versus "you may not do this". That is
  standard, and it reveals nothing about data.

### 16.7 Tests

All of these run without Spring and in milliseconds.

1. **`PermissionMatrixTest`.** A role-by-operation table of expected decisions that must cover
   `ActorRole.values()` exactly, so adding a role fails the test until someone classifies it.
   Register: `VIEWER` denied with `PermissionDenied(VIEWER)`; `UNDERWRITER` and `SENIOR_UNDERWRITER`
   admitted. Search: every role admitted (BR-16). An empty caller is `CallerNotIdentified` for
   both.
2. **`OperationGateTest`.** A denied caller never reaches the operation (a counting lambda records
   zero calls); an admitted caller reaches it exactly once, with the admitted actor and the input
   passed to `run`.
3. **`ActorContextParseTest` and `AuthenticatedCallerParseTest`.** `null`, empty, blank, a
   non-canonical UUID such as `1-1-1-1-1`, a 256-code-point `actorId`, an `actorId` with a TAB or a
   NUL, an unknown role, and a role spelled `viewer` each yield `Optional.empty()`; a good triple
   yields a caller whose `toString` does not print the `actorId`.
4. **Edge order tests, on the controllers as plain objects.** Build the controller with a gate over
   a fake use case and a stub resolver. The one that pins "judged before parsed": a Viewer sends a
   valid reference and a **poisoned `BigDecimal`** — a subclass whose every method fails the test —
   and must receive `403` with the poison untouched. `BigDecimal` is not final and `String` is, so
   the poison goes in the amount. Its control: an Underwriter sending the same poisoned request
   *does* touch it, so the first case cannot pass vacuously. Around it: a Viewer with a control
   character in the reference is told `403`, not `400`; the same Underwriter is told `400
   reference-contains-control-character`; an empty caller with an invalid body is told `401`; a valid
   Underwriter request is `201` and the fake received exactly the admitted actor. For search: an
   empty caller with an unmatchable filter is `401` and the repository fake is untouched; a Viewer
   with a control character in the reference filter is `200` with an empty page and the repository
   fake is untouched.
5. **The refusal-catalogue contract test.** For each of the nine spec reasons, an input that
   produces it, driven through the edge, asserting status and problem type; plus, by reflection,
   that the permitted subclasses of `AccessRefusal` and `RegisterSubmissionError` together are
   exactly those nine, and that search has exactly one reason. This is the test that keeps the
   contract from shrinking silently. It asserts on problem types, never on class names, because the
   spec's "role may not register" is rendered from `PermissionDenied`.
6. **ArchUnit:** the three rules in [§16.4](#164-bypass-is-a-build-failure) and the three in
   [§15.9](#159-enforcement), each broken on purpose once and the failure seen.
7. **Integration:** unchanged persistence ITs, plus the two width ITs.

### 16.8 Trade-offs accepted and what is still open

- **The nine refusals are produced in two layers,** so no single type proves them complete. The
  union's flat `switch` and the catalogue test put the proof back, at the cost of a union type that
  would not otherwise exist.
- **The ordering knowledge is now at the edge,** and "parse after the permission" is held by API
  shape and tests, not by the compiler (§16.3, row 5).
- **The use cases take the actor as a parameter,** and the scoped-value API in the kernel has no
  production consumer (R5). Two conventions are not better than one; one of them is now dead and
  should be deleted or justified.
- **`AccessRefusal` is shared across operations,** an exception to "one `*Error` per operation"
  that this design argues for ([§3.2](#32-refusals-nine-as-the-caller-sees-them)).
- **Search returns a plain view,** an exception to "every use case returns a `Result`" (R1).
- **The aggregate cannot fail** ([§4.1](#41-what-the-aggregate-looks-like-when-it-receives-value-objects)).
- **Three type parameters on the gate.** The third buys search's one-case `switch`; see
  [§11.3](#113-engineering-items-carried), item 14.
- **Framework conversion precedes the guard** (Q1). Closing it is a decision about whether to own
  JSON parsing, not a defect in the guard.

---

## 17. Rejected designs

Two designs were proposed and rejected on the way to this one. They are recorded because each is the
natural thing for the next person to reinvent.

### 17.1 Parse first and judge the role second

*The shape:* the adapter parses the request into value objects, then asks whether the caller may
register; every refusal arrives in the order of the data.

*Why it was rejected:*

1. **It hands an unauthorised caller the input-handling attack surface.** The parser would run on
   bytes from someone we have already decided not to serve: a code-point count over an arbitrarily
   long string, a `BigDecimal` of arbitrary digit count, and every parser bug that will ever exist.
   An unauthorised caller should cost us a role comparison, not a parse.
2. **It makes a refusal a probing oracle.** The spec's own reasoning for the order: a caller who may
   not register has no business having what they sent read, interpreted or judged. If field
   refusals came first, anyone holding a Viewer's credentials, genuine or stolen, could use
   registration to learn the validation rules, and a `409` would tell them which references the
   organisation already holds.
3. **It contradicts the spec's order of judgement** — caller, role, reference, amount, history — and
   a test that already pins it: a Viewer sending a control character is told about the role, not
   about the reference.
4. **Nothing in the code would resist it.** The next controller can parse first again, and only a
   test would notice.

"Parse, don't validate" does not require parsing first. It requires that an operation receive only
parsed values. Identity and permission are decided from the caller and never needed the request.

### 17.2 A domain-owned permission, re-checked by the use case

*The shapes:* (i) the domain exposes the permission rule, the adapter consults it before parsing, and
the use case re-checks it as defence in depth; (ii) its typed form, proposed in an earlier revision
of this document: a `RegisteringCaller` value object that only a domain `parse` can mint, carried in
the command, so that no re-check is needed because a Viewer cannot be represented.

*Why it was rejected (the product owner's decision):* **the adapter, not the domain, knows which
resource may call which use case.** A permission is a property of the *pairing* of a caller and an
entry point, not of the business operation, and the domain cannot know its callers — it is reused by
whatever inbound adapters exist. Beyond that:

- (i) states one policy twice, and two statements of one policy drift apart. It also means the use
  case must still answer "no caller is bound", which puts the `401` and `403` back in the domain.
- (ii) puts the role into the command and the kernel, so the domain reasons about roles, and the
  next feature will find in it a licence to put the authority limit's logic there too. The line
  that matters is between a permission, decidable from the caller and the operation alone, and a
  rule that needs the data ([§16.1](#161-the-principle)); (ii) blurs it.

What survives: the insight that the order of judgement is business knowledge. It survives as the
position of the guard in the edge flow, the contract test, and the pinned order inside each `parse`.

### 17.3 A decorator around `execute(command)`

This is the obvious reading of "apply the guard where the use cases are wired", and it cannot
satisfy the order. A decorator on `execute(command)` receives a *command*, and a command exists
only after the request was parsed, so the permission would be decided after the input was handled:
17.1 in disguise. The same enforcement point works once it moves in front of the input. The gate
yields an `Admission` first, and the `Admission` is the only way to run the use case.

### 17.4 A servlet filter, `HandlerInterceptor`, argument resolver or Spring Security

- **A filter or interceptor** runs before the controller and so, genuinely, before body conversion,
  which is attractive (Q1). But it knows URLs and not use cases, so permissions would be declared
  against paths; a consumer or a job would get none of it; and handing the result to the controller
  means a request attribute, an untyped `Object`, losing the compiler's help.
- **An argument resolver** has to refuse by throwing, and this codebase bans exceptions as control
  flow.
- **Method security (`@PreAuthorize`)** is not on the classpath and refuses by throwing
  `AccessDeniedException`.

A filter is the right place for *authentication* — establishing who the caller is — once an identity
source is chosen (Q1). The per-use-case *permission* stays in the gate.

---

## 18. What moves

### 18.1 Production classes

| Class | Change |
|---|---|
| `shared.kernel.ActorContext` | record to `final class`; `tenantId` and `actorId` only; private constructor; `parse`; role removed |
| `shared.kernel.ActorRole` | moved to `submission.adapters.access`; `canDecide()` deleted |
| `shared.kernel.ControlCharacters` | **new** |
| `shared.kernel.LogSafeText` | delegates the category test to `ControlCharacters` |
| `domain.ExternalReference`, `domain.InsuredLimit` | **new** |
| `domain.RegisterSubmissionCommand` | raw components replaced by the two value objects; gains `parse`; the two constants move out |
| `domain.RegisterSubmissionError` | loses `CallerNotIdentified` and `RoleMayNotRegister` (to `adapters.access`): nine variants become seven |
| `domain.RegisterSubmissionUseCase` | `execute(ActorContext, RegisterSubmissionCommand)`; returns `Result<SubmissionId, ReferenceAlreadyUsed>`; no `ActorContext.current()`, no `fromOptional`, no widening |
| `domain.Submission` | `register` is total; the validation helpers are deleted; value-object fields; `reconstitute` returns `Optional` and re-parses; no `ActorRole` |
| `query.SubmissionFilter`, `query.ReferenceCriterion` | **new** |
| `query.SearchSubmissionsQuery` | five raw filters replaced by one `SubmissionFilter` |
| `query.SearchSubmissionsUseCase` | `ask(ActorContext, SearchSubmissionsQuery)` returns `SubmissionPageView`; a `switch` over the filter |
| `query.SearchSubmissionsError` | **deleted** |
| `query.SubmissionSearchCriteria` | `externalRef: String` becomes `reference: ReferenceCriterion` |
| `adapters.access.*` | **new:** `ActorRole`, `AuthenticatedCaller`, `AccessRefusal`, `CallerNotIdentified`, `PermissionDenied`, `Permission`, `OperationGate`, `Admission` |
| `adapters.web.ActorContextResolver` | yields `Optional<AuthenticatedCaller>` |
| `adapters.web.RegisterSubmissionRequest` | gains `toCommand()` |
| `adapters.web.RegisterRefusal` | **new** union |
| `adapters.web.SubmissionCommandController` | the five-step flow; a nine-case flat `switch`; no `ActorContext.callWith` |
| `adapters.web.SubmissionQueryController` | the flow in [§13.3](#133-the-search-request-step-by-step); a one-case `switch`; calls `SubmissionFilter.parse` |
| `adapters.persistence.SubmissionQuerySqlAdapter` | unwraps `reference.value()` |
| `adapters.persistence.SubmissionPersistenceMapper` | handles `Optional` from `reconstitute` |
| `adapters.config.SubmissionConfiguration` | builds the two gates; declares the permission table; raw use cases are no longer beans |
| migrations, SQL | none |

### 18.2 Tests

Counted from the current tree.

- **`new ActorContext(...)`: 18 sites in 6 files** become fixture calls built on `parse`, and the
  `ActorRole` import in 6 test files changes package.
- **`new RegisterSubmissionCommand(...)`: 10 sites in 4 files** become `RegisterSubmissionCommand.parse`
  or value-object fixtures.
- **`new SearchSubmissionsQuery(...)`: 18 sites in 4 files** build their filter through
  `SubmissionFilter.parse`.
- **The role tests** (`RegisterSubmissionUseCaseTest`'s role group and its viewer-before-control-character
  case; `RoleMayNotRegister` appears in 2 files) can no longer be written against the use case at
  all, which is the point. They move to `PermissionMatrixTest`, `OperationGateTest` and the edge order
  tests.
- **`CallerNotIdentified` is asserted in 4 files** (both use-case tests and both adversarial tests);
  those assertions move to the gate tests. **`SearchSubmissionsError` appears in 2 files** and goes.
- **The validation-order tests** in `RegisterSubmissionUseCaseTest` and
  `SubmissionRegistrationAdversarialTest` move to `ExternalReferenceTest`, `InsuredLimitTest` and
  `RegisterSubmissionCommandParseTest`: pure, with no use case and no fake.
- **`ActorContextTest`:** the `canDecide` and role assertions leave, the parse tests arrive, the
  scoped-value tests stay (R5).
- **`ReferenceLogInjectionTest`:** builds raw commands and queries today. Part of what it proved is
  now unrepresentable for a value object; keep it for the raw holders (snapshot, event, error,
  views).
- **`InMemorySubmissionQueryRepository`** takes a `ReferenceCriterion` and needs no knowledge of
  category Cc.
- **Integration tests** that build commands through the register use case (3 test files use it) go
  through `parse`.
- **New:** gate, parse, edge-order and catalogue tests, `ControlCharactersTest`,
  `SubmissionFilterTest`, the two width ITs, and six ArchUnit rules.

### 18.3 Invariants

| Invariant | Before | After |
|---|---|---|
| BR-01 caller identified | each use case, `fromOptional(ActorContext.current())` | `OperationGate.admit` |
| BR-02, BR-03 role may register | `Submission.register` | `Permission.rolesOnly`, declared in config |
| BR-16 every role may search | the search use case never read a role | `Permission.anyIdentifiedCaller`; no role consulted |
| BR-04, BR-05, BR-29 | `Submission.register` | `ExternalReference.parse` |
| BR-10, BR-11, BR-12 | `Submission.register` | `InsuredLimit.parse` |
| BR-30 | unspecified in code; the filter reached the store and answered `500` | `SubmissionFilter.parse` yields `MatchesNothing` |
| BR-06, BR-09 | the store, via a unique index | unchanged |
| BR-15 ownership | the aggregate | unchanged |
| BR-17 tenant isolation | the use case passes `actor.tenantId()` | unchanged |
| BR-22, BR-23 masking | per-record `toString` | per-record, and the value objects now mask themselves |
| a caller's identity is well-formed | nothing: any string, `null` possible | `ActorContext.parse`, `AuthenticatedCaller.parse` |

---

Design written to `docs/adr/submission-registration-design.md`. Open a new chat and call
`@test-designer` to turn it into contracts and failing tests.
