# Design: submission registration

Mode: **`@architect` design mode.** Produced from `docs/specs/submission-registration-spec.md`
(BR-01..BR-30) following `.github/skills/spec-to-plan/SKILL.md`. No production or test code was
written; every signature and every SQL statement below lives in this document only.

Every business number here traces to the spec. The one number the spec deliberately delegates —
the maximum page size — is recorded in [§7](#7-engineering-decision-maximum-page-size) as an
engineering decision with its reasoning. Nothing else in this document invents a business rule.

> **Revision 4 — read this first.** The module has two packages, `domain/` and `adapters/`. There is
> no `query/` package. Read use cases live in `domain/` next to write use cases.
>
> - **Why.** The ban on `query/` importing `domain/` forced duplication and primitive obsession, and
>   it has already happened in this repository. `NumberCriterion` duplicated `SubmissionNumber`.
>   `ControlCharacters` had to be placed in the kernel only so two packages could both reach it.
>   Views carried raw `UUID` and `String` instead of `SubmissionId` and domain types. CQS is a
>   separation of *responsibility*, not of folders, and it is now enforced by the one public method
>   each use case exposes (`ask` or `execute`), not by a package ([§5.6](#56-cqs-enforced-without-folders)).
> - **Parse, don't validate** is unchanged: a use case receives only values that are valid by
>   construction, and raw input enters only through a method named `parse` ([§15](#15-value-objects)).
> - **Authorisation is an adapter concern** and the order is unchanged: identify the caller (401),
>   check the permission for that use case (403), parse the request, execute ([§16](#16-the-access-guard)).
>   `OperationGate` keeps **two** type parameters, as the product owner asked ([§16.2](#162-the-shape)).
> - **The caller-facing contract is unchanged.** Registration is still refused for exactly nine
>   reasons. Search is still refused for exactly one, now proven by a test rather than by the type
>   ([§5.2](#52-refusals-search-still-has-exactly-one)).
> - **`NumberCriterion` is deleted.** Search filters reuse `SubmissionNumber`. An empty number
>   is "not supplied" (BR-31), and a number with a control character is "matches nothing" (BR-30).
> - **`ControlCharacters` stays in `shared/kernel`,** for a reason that is now the only one left
>   ([§15.5](#155-the-single-definition-of-category-cc)).
> - **The change list** of every class, test, rule, instruction, skill, agent and AGENTS.md section
>   that prescribes `query/` is in [§18.4](#184-documents-and-rules-outside-this-design).
> - **Questions for the product owner** are in [§11.1](#111-questions-for-the-product-owner).

---

> **Revision 6 (model A: append-only record).** Recorded facts are never changed or removed, a change of state is a new fact, and the view shows the most current state only (BR-44 to BR-46). The record and its latest-state view are [§20](#20-the-record-append-only-facts-and-the-latest-state-view). The submission number is the only identifier that leaves the service (BR-47), and this revision checks every surface against it.

---

## 1. Scope

Covers, and only covers, the three operations the spec puts in scope:

- **register a submission** — one command; **nine** refusal reasons as the caller sees them
  (BR-01, BR-02, BR-04..BR-06, BR-09..BR-12, BR-29): two decided at the adapter, seven in the domain
- **approve a submission** — one command; **five** refusal reasons as the caller sees them (BR-01, BR-33,
  BR-35, BR-36, BR-41): two decided at the gate, three in the domain (§19)
- **search submissions** — one query; **one** refusal reason as the caller sees it (BR-01), decided
  at the adapter. The read use case refuses nothing of its own (BR-16..BR-28, BR-30, BR-31)

Module: **`submission`** — one business capability, with a write side and a read side.

```
com.gs5.underwriting.submission
├── domain/      Everything the business does: write use cases (execute) and read use cases (ask),
│                 their commands, queries, views, ports, value objects and the package-private
│                 aggregate. Pure Java. Knows no roles.
└── adapters/
    ├── access/       the guard: who may call which use case. Plain Java, no Spring.
    ├── web/
    ├── persistence/
    ├── <the publishing adapter — deferred, see §12>
    └── config/       the one place that may see a raw use case
```

There is no `query/` package and no `application` package, and no `*Service` or `*Facade` class.
Checkstyle `noServiceSuffixDeclaration` and ArchUnit `no_procedural_service_or_facade_layer` fail the
build on the alternatives, and ArchUnit `no_query_package_exists` ([§15.10](#1510-cqs-rules-verbatim))
fails it on a `query/` package.

`domain/` imports only `java.*`, `com.gs5.underwriting.shared.kernel.*` and
`org.jspecify.annotations.*`. Each package carries a `package-info.java` with `@NullMarked`.

**"The domain knows no roles" is enforced, not hoped for.** `ActorRole` lives in
`adapters/access/`, and the existing ArchUnit rule `write_and_read_models_stay_pure_java` forbids
`domain/` from depending on anything in `..adapters..`.

**CQS without folders.** The read side and the write side are both in `domain/`, so nothing in the
package tree separates them. What separates them is the one public method each use case exposes,
and ArchUnit checks that the method and the dependencies agree with it ([§5.6](#56-cqs-enforced-without-folders)).
A read use case cannot depend on the aggregate, a command repository or an event publisher. A write
use case cannot depend on a query repository or a view.

---

## 2. File inventory

```
shared/kernel/                          (four types)
├── Result.java                         unchanged
├── ActorContext.java                   final class: tenantId + actorId; parse(...); no role
├── ControlCharacters.java              the single definition of Unicode category Cc (stays here, §15.5)
└── LogSafeText.java                    delegates the category test to ControlCharacters

submission/
├── domain/
│   ├── package-info.java               @NullMarked
│   ├── SubmissionId.java               public record
│   ├── SubmissionNumber.java          public final class — value object; parse returns SubmissionNumberError
│   ├── SubmissionNumberError.java     public sealed interface — 3 variants, shared by both sides (§15.2)
│   ├── InsuredLimit.java               public final class — value object, parse(...)
│   ├── RegisterSubmissionCommand.java  public record of the two value objects, parse(...)
│   ├── RegisterSubmissionError.java    public sealed interface — 7 variants
│   ├── RegisterSubmissionUseCase.java  public final class — execute(ActorContext, Command)
│   ├── SubmissionCommandRepository.java    public interface — outbound port (write)
│   ├── SubmissionEventPublisher.java       public interface — outbound port (write)
│   ├── SubmissionIdGenerator.java          public interface — outbound port (write)
│   ├── SubmissionSnapshot.java         public record — persistence contract
│   ├── SubmissionDomainEvent.java      public sealed interface
│   ├── Submission.java                 PACKAGE-PRIVATE aggregate
│   ├── SearchSubmissionsQuery.java     public record — filter, page, size
│   ├── SubmissionFilter.java           public sealed interface — Narrowing | MatchesNothing (§5.1)
│   ├── SearchSubmissionsUseCase.java   public final class — ask(ActorContext, Query)
│   ├── SubmissionSearchCriteria.java   public record — tenant + four filters + number
│   ├── SubmissionSummaryView.java      public record — one result row, domain types
│   ├── SubmissionPageView.java         public record — page + total count
│   └── SubmissionQueryRepository.java  public interface — outbound port (read)
│                                       (no NumberCriterion; no SearchSubmissionsError — both deleted)
└── adapters/
    ├── access/       ActorRole (moved from the kernel), AuthenticatedCaller, AccessRefusal,
    │                 CallerNotIdentified, PermissionDenied, Permission, OperationGate, Admission
    ├── web/          SubmissionCommandResource, SubmissionQueryResource, RegisterSubmissionRequest,
    │                 RegisterRefusal (union), ActorContextResolver
    ├── persistence/  command and query adapters, JDBC repositories, entity, mapper (package-private)
    ├── <publishing adapter, deferred — §12.1>
    └── config/       SubmissionConfiguration — builds the two gates; declares the permission table

```

---

## 3. Commands (write side)

| Command record | Use case method | Returns | Event on success |
|---|---|---|---|
| `RegisterSubmissionCommand` | `execute(ActorContext actor, RegisterSubmissionCommand command)` | `Result<SubmissionId, RegisterSubmissionError.NumberAlreadyUsed>` | `SubmissionDomainEvent.SubmissionRegistered` |

The use case refuses on exactly one ground, because by the time it runs everything else has been
decided: the caller was identified and permitted at the adapter, and the input was parsed into
values that cannot be invalid. What the *service* answers to a caller is richer, and is set out in
[§3.2](#32-refusals-nine-as-the-caller-sees-them).

### 3.1 The command record

```java
public record RegisterSubmissionCommand(SubmissionNumber submissionNumber, InsuredLimit insuredLimit) {

    /**
     * Number first (missing, too long, control character), then the amount (missing, not
     * positive, wrong precision) — the order of judgement in the specification. The first
     * failure is the refusal; nothing later is examined.
     */
    public static Result<RegisterSubmissionCommand, RegisterSubmissionError> parse(
            @Nullable String submissionNumber, @Nullable BigDecimal insuredLimit);
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
domain**, so a second adapter cannot judge the number and the amount in a different order.
Whether the caller may call the operation at all is not decided here, or anywhere in the domain
([§16](#16-the-access-guard)); `parse` is simply never reached for a caller who was refused.

| Input | Required | Constraint | Enforced by | Refused as |
|---|---|---|---|---|
| number | yes | present and not empty (BR-04); at most 64 code points (BR-05); no category-Cc character (BR-29); never trimmed or case-folded (BR-07) | `SubmissionNumber.parse` | `NumberMissing`, `NumberTooLong`, `NumberContainsControlCharacter` |
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
    record NumberMissing() implements RegisterSubmissionError { }                          // BR-04
    record NumberTooLong(int maxLength, int actualLength)
            implements RegisterSubmissionError { }                                            // BR-05
    record NumberContainsControlCharacter(int codePoint, int index)
            implements RegisterSubmissionError { }                                            // BR-29
    record InsuredLimitMissing() implements RegisterSubmissionError { }                       // BR-10
    record InsuredLimitNotPositive() implements RegisterSubmissionError { }                   // BR-11
    record InsuredLimitPrecisionInvalid(int requiredScale, int actualScale)
            implements RegisterSubmissionError { }                                            // BR-12
    record NumberAlreadyUsed(String submissionNumber)
            implements RegisterSubmissionError { }                                            // BR-06, BR-09
}
```

| # | Spec refusal reason | Layer | Produced by | Rule | HTTP |
|---|---|---|---|---|---|
| 1 | Caller not identified | **adapter** | `OperationGate.admit` | BR-01 | 401 |
| 2 | Role may not register | **adapter** | `OperationGate.admit`, as `PermissionDenied(role)` | BR-02 | 403 |
| 3 | Number missing | domain `Result` | `SubmissionNumber.parse` | BR-04 | 400 |
| 4 | Number too long | domain `Result` | `SubmissionNumber.parse` | BR-05 | 400 |
| 5 | Number contains a control character | domain `Result` | `SubmissionNumber.parse` | BR-29 | 400 |
| 6 | Insured limit missing | domain `Result` | `InsuredLimit.parse` | BR-10 | 400 |
| 7 | Insured limit not positive | domain `Result` | `InsuredLimit.parse` | BR-11 | 400 |
| 8 | Insured limit precision invalid | domain `Result` | `InsuredLimit.parse` | BR-12 | 400 |
| 9 | Number already used | domain `Result` | the persistence port, inside `execute` | BR-06, BR-09 | 409 |

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
which are a property of the notation and not of the magnitude. `NumberContainsControlCharacter`
carries two **numbers**, the code point and its zero-based code-point index, and never the
character itself: a raw NEWLINE in an error record forges a log line and a raw NUL truncates one.
`NumberAlreadyUsed` carries the number, which BR-23 allows, and is rendered with
category-Cc code points escaped ([§10](#10-logging-and-masking)). `PermissionDenied` carries the
role, which identifies nobody. No variant carries `actorId`.

### 3.3 Use case

```java
public final class RegisterSubmissionUseCase {

    public RegisterSubmissionUseCase(SubmissionCommandRepository repository,
                                     SubmissionEventPublisher eventPublisher,
                                     SubmissionIdGenerator idGenerator,
                                     Clock clock);

    public Result<SubmissionId, RegisterSubmissionError.NumberAlreadyUsed> execute(
            ActorContext actor, RegisterSubmissionCommand command);
}
```

Its shape is two statements: build the aggregate, which cannot fail ([§4](#4-aggregate)), then
insert it and, on success only, publish the event.

```java
var submission = Submission.register(idGenerator.nextSubmissionId(), actor,
        command.submissionNumber(), command.insuredLimit(), clock.instant());
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
  returns `Result<SubmissionId, NumberAlreadyUsed>`, so the pipeline passes it straight through:
  the `.mapError(error -> error)` widening the previous revision needed is gone, and a test can no
  longer be written that expects `execute` to return a number or amount refusal, because the
  type says it cannot.
- **`peek` is what makes "no event for a refused registration" structural.** It runs its side
  effect on the success branch only, so a `NumberAlreadyUsed` cannot reach the publisher. The
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
     * organisation already used this exact number (BR-06, BR-09).
     */
    Result<SubmissionId, RegisterSubmissionError.NumberAlreadyUsed> insert(
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
anywhere in `domain/` or this document's schema. `publish` returns `void` and not a
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
  missing refusal reason. A publishing failure is not a property of the input — no number and no
  amount makes it more or less likely, every input is still valid, and the state change did happen.
  It is a transient infrastructure failure, which is the one thing a 500 legitimately means. The
  distinction is the test to apply the next time this question comes up: **if the same input always
  fails, it is a missing refusal reason; if the same input usually succeeds, it is an outage.** Flagged
  in [§11](#11-open-questions) so it is reviewed rather than assumed.
- **The one mitigation that comes free is worth naming:** the number is a *caller-supplied*
  natural key, unique per organisation (BR-06). A caller who retries after an ambiguous failure gets
  `NumberAlreadyUsed`, not a second submission. The operation is therefore idempotent on the
  caller's own key by construction — no double registration is possible — and the caller can
  confirm the outcome by searching for the number. BR-09 and BR-06 do this work; no design
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
    private final SubmissionNumber submissionNumber;  // value object — valid by construction
    private final InsuredLimit insuredLimit;      // value object — valid by construction
    private final String registeredBy;            // ActorContext.actorId, already parsed
    private final Instant registeredAt;
    private final @Nullable Long version;         // plain Long, no annotation, null when new

    /** New submission. Total: it cannot fail, because nothing it receives can be invalid. */
    static Submission register(SubmissionId id, ActorContext actor,
                               SubmissionNumber submissionNumber, InsuredLimit insuredLimit,
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
| number present | BR-04 | `Submission.register` | `SubmissionNumber.parse` |
| number length | BR-05 | `Submission.register` | `SubmissionNumber.parse` |
| no control character | BR-29 | `Submission.register` | `SubmissionNumber.parse` |
| insured limit present | BR-10 | `Submission.register` | `InsuredLimit.parse` |
| insured limit positive | BR-11 | `Submission.register` | `InsuredLimit.parse` |
| exactly two decimals | BR-12 | `Submission.register` | `InsuredLimit.parse` |
| number unused in the organisation | BR-06, BR-09 | the store (unique index) | the store (unchanged) |

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
same `SubmissionNumber.parse` and `InsuredLimit.parse` and returns `Optional.empty()` when a stored
value no longer parses. There is no unchecked "trusted" factory on the value objects, because a
second way in is exactly how the guarantee erodes.

This is not hypothetical: **BR-29 is retroactive over stored data.** A submission registered with
a TAB in its number before BR-29 existed would no longer reconstitute. A failure to parse a
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
| 3 | number | `RegisterSubmissionCommand.parse` → `SubmissionNumber.parse` | missing, then too long, then control character |
| 4 | insured limit | `RegisterSubmissionCommand.parse` → `InsuredLimit.parse` | missing, then not positive, then precision |
| 5 | number against history | `execute` → the persistence port | `NumberAlreadyUsed` |

Every position is pinned by a test. The reasoning the previous revision gave still holds, inside
the factories:

- **Role before input** (steps 1-2 before 3-4): [§16.3](#163-the-order-at-the-edge).
- **Presence before shape:** `""` trivially contains no control character, so a control-character
  check placed first would pass an empty number and report the real defect later and less
  clearly.
- **Length before content:** matches the spec's own order, and bounds the control-character scan
  to at most 64 code points — a 10 MB number ending in a TAB is refused for length without being
  scanned for the TAB. A number that is both too long and contains a control character is
  refused as `NumberTooLong`.
- **Positivity before precision:** `BigDecimal.ZERO` has `scale() == 0`, so it violates both BR-11
  and BR-12; this order makes it `InsuredLimitNotPositive`, the more fundamental fact, and the
  spec's own worked examples list `0.00` under BR-11.
- **History last:** it is the only refusal that costs a database round trip.

---

## 5. Queries (read side)

The read side lives in `domain/`, next to the write side. It is separated from the write side by
**responsibility, not by folder**: a read use case is discriminated by its one public method, `ask`,
and is forbidden from depending on anything that changes state; a write use case is `execute` and
is forbidden from depending on anything that only reads. The rules are in [§5.6](#56-cqs-enforced-without-folders).

| Read type | Use case method | Returns | Takes |
|---|---|---|---|
| `SearchSubmissionsUseCase` | `ask(ActorContext actor, SearchSubmissionsQuery query)` | `SubmissionPageView` | `SearchSubmissionsQuery` |

The read side uses the domain's own types wherever they exist. `SubmissionId` and the number
type are the same classes the write side uses, so a view cannot disagree with the aggregate about
what an identifier or a number is. What is removed is the duplicate: there is no second
number type for filters ([§5.1](#51-the-query-record-and-the-filter-parse)).

**The return type is the view itself, not a `Result`.** The read side refuses nothing: every
search that reaches it succeeds, including the ones that match nothing (BR-20), have a
contradictory range (BR-21), lie past the end (BR-27), carry a control character in the number
filter (BR-30) or have an empty number filter (BR-31). A `Result<_, E>` needs an `E` with at
least one variant, and a sealed interface cannot have zero; a placeholder variant would be exactly
the speculative variant this design forbids. The rule that every use case returns a `Result` was
written when every use case could refuse, and needs a carve-out ([§11.2](#112-repository-conventions-this-design-asks-to-change), R1).

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

    /** Every supplied criterion can match something. Safe to hand to the store. */
    record Narrowing(@Nullable SubmissionNumber number,
                     @Nullable BigDecimal insuredLimitFrom,
                     @Nullable BigDecimal insuredLimitTo,
                     @Nullable Instant registeredFrom,
                     @Nullable Instant registeredTo) implements SubmissionFilter { }
                     // toString masks both insured-limit bounds (BR-22, strict reading)

    /** BR-30: the number filter contains a control character and provably matches nothing. */
    record MatchesNothing() implements SubmissionFilter { }

    /** Total: never fails, never refuses. */
    static SubmissionFilter parse(@Nullable String number,
                                  @Nullable BigDecimal insuredLimitFrom,
                                  @Nullable BigDecimal insuredLimitTo,
                                  @Nullable Instant registeredFrom,
                                  @Nullable Instant registeredTo);
}
```

`SubmissionNumber` is the write side's own value object, reused. The search filter does not have
a second number type. The mapping from its parse result to the filter is this, and it is the
whole of the search-side number rule:

| Input number | `SubmissionNumber.parse` result | Filter |
|---|---|---|
| absent (`null`) | — | `number = null`: not supplied, narrows nothing (BR-19) |
| present but empty, `""` | `Missing` | `number = null`: **treated as not supplied (BR-31)** |
| contains a category-Cc character | `ControlCharacter` | **`MatchesNothing` (BR-30)** |
| 65 or more code points | `TooLong` | **`MatchesNothing` (BR-32)** |
| valid | `Result.success(number)` | `number = that SubmissionNumber` (BR-07, compared exactly) |

The too-long row is BR-32, which extends BR-30: a number filter longer than 64 characters matches nothing, because no number that long can have been registered (BR-05). It is a success with an empty page and a total of zero, never a refusal, and the filter is never shortened to make it match. A filter of exactly 64 is compared normally. Length is counted the way BR-05 counts it; how that is, is an open question ([§11.1](#111-questions-for-the-product-owner), Q8).

**The mapping is one function, not a second implementation.** `SubmissionFilter.parse` calls
`SubmissionNumber.parse` and folds the result. It contains no character test of its own. A reader
who wants to know what a search filter accepts reads the same method a registration reads, and
the two cannot drift.

**Why a search filter failing is not a refusal.** The register parse maps the same failure to a
refusal, because a registration with that number is a request the business cannot honour. A
search with that number is a question whose true answer is "none", and the spec asks for an
empty success, not an error (BR-30). One parse with two folds is the honest expression of that:
the failure is the same fact; what it means depends on the operation.

**Why a type and not a check in front of the database.** SEC-10 was a filter containing U+0000
reaching PostgreSQL, which cannot represent it, and answering `500` for a search the spec calls a
successful empty one. Here the store's input, `SubmissionSearchCriteria`, is built only from a
`Narrowing`, and a `Narrowing` holds only an `SubmissionNumber`, which cannot contain a control
character. **The adapter never sends such a filter to the database because there is no value it
could send.**

**Only the number needs a value object.** The other four criteria are `BigDecimal` and `Instant`,
and every value of those types is a legal comparison value (the spec says so for the amounts). A
contradictory range (BR-21) is deliberately not a `MatchesNothing`: unlike BR-30 it does not break
the store, the predicate `>= 5000 AND <= 1000` simply matches no row, and a second code path for
it would be a second implementation of a rule the SQL already implements. The distinction is this
design's rule in miniature: **a typed representation is for what the store cannot represent, not
for what it merely finds empty.**

**Page numbering is 0-based** (`offset = page * size`), confirmed by the product owner. The page
request normalises itself inside the record's compact constructor ([§7](#7-engineering-decision-maximum-page-size)).

### 5.2 Refusals: search still has exactly one

The spec's list for search is exhaustive and deliberate: **one** reason, caller not identified
(BR-01). That is unchanged. What it means for the read side:

- It is a property of the **service**, not of a domain type. `SearchSubmissionsError` is
  **deleted**; its single variant is the access concern's, `CallerNotIdentified` in `adapters/access`.
- It is **proven by a test, not by the type — accepted by the product owner.** `OperationGate` has two type parameters, so the gate's refusal type is `AccessRefusal`, shared by both operations. The search controller's `switch` therefore has two arms: `CallerNotIdentified` (`401`) and `PermissionDenied` (`403`). The second arm can never run for search, because search's permission is `anyIdentifiedCaller()`, which cannot refuse on role. It is written rather than omitted so the `switch` stays exhaustive with no `default`. The previous revision proved this in the type, with a third type parameter; the product owner chose two parameters and accepted that the proof moves to a test. This is settled, not open.
- It is **tested**. The contract test drives every `ActorRole` through the gate (BR-16), asserts that
  search never produces `PermissionDenied`, and checks
  that matching nothing, a contradictory range, a page past the end, a control character in the
  number filter, an empty number filter and a number filter longer than 64 (BR-32) are all successes with an empty page and a total
  of zero.

### 5.3 Views

```java
public record SubmissionSummaryView(String submissionNumber,
                                    BigDecimal insuredLimit,
                                    String registeredBy,
                                    Instant registeredAt,
                                    SubmissionState state) {
    @Override
    public String toString() {   // BR-22 amount, OPEN-01 actorId
        return "SubmissionSummaryView[submissionNumber=" + LogSafeText.escapeControlCharacters(submissionNumber)
                + ", insuredLimit=***, registeredBy=***"
                + ", registeredAt=" + registeredAt
                + ", state=" + state + "]";
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

**A view carries what the store holds, as the store holds it** — decided by the product owner. The number is a `String` and the insured limit a `BigDecimal`, not an `SubmissionNumber` and an `InsuredLimit`. The reason: a view is *output read from our own store*, not input from a client, and parse-don't-validate applies at the input boundary. Parsing a stored value again on the way out would need a branch for a failure the schema already rules out ([§9.4](#94-query-adapter)), and that branch could only be a hard failure or a new error path. Neither is wanted, and neither is needed.

**The internal `SubmissionId` is on no view.** A view is what a caller sees, and the internal identity is never shown (spec, *Submission Number*). The write side uses it as the row key; nothing read from the store is typed back into it.

This does not bring back the duplication revision 4 removed. The search *filter* is input, and it still goes through `SubmissionNumber.parse` ([§5.1](#51-the-query-record-and-the-filter-parse)); there is still no `NumberCriterion`.

The view masks the amount and the caller in its own `toString` (BR-22, OPEN-01) and escapes the number (BR-23), because it is a raw holder: nothing in its types does that for it. `registeredBy` is a `String` for the same reason as before, and for the reason above.

`totalMatchCount` is `long` and counts the whole matching set, ignoring the page (BR-26). `page` and
`size` echo what was applied ([§7](#7-engineering-decision-maximum-page-size)).

### 5.4 Read port and criteria

```java
public record SubmissionSearchCriteria(UUID tenantId,
                                       @Nullable SubmissionNumber number,
                                       @Nullable BigDecimal insuredLimitFrom,
                                       @Nullable BigDecimal insuredLimitTo,
                                       @Nullable Instant registeredFrom,
                                       @Nullable Instant registeredTo) { }

public interface SubmissionQueryRepository {

    List<SubmissionSummaryView> findPage(SubmissionSearchCriteria criteria, int limit, long offset);

    long countMatching(SubmissionSearchCriteria criteria);
}
```

`tenantId` lives inside the criteria and only the use case can build one (BR-17). The read port is an
outbound port in the same way the write port is: it has its own package-free interface in
`domain/`, and it speaks domain types. `number` is an `SubmissionNumber`, so the persistence
adapter unwraps `number.value()` into the bound parameter, and the SQL in [§6](#6-sql-shape) is
unchanged.

`offset` is `long`: `page * size` near `Integer.MAX_VALUE` overflows an `int`.

### 5.5 Query use case

```java
public final class SearchSubmissionsUseCase {

    public SearchSubmissionsUseCase(SubmissionQueryRepository repository);

    public SubmissionPageView ask(ActorContext actor, SearchSubmissionsQuery query);
}
```

A `switch` over the sealed filter with **no `default`**, so a third filter shape fails to compile here:

```java
return switch (query.filter()) {
    case SubmissionFilter.Narrowing narrowing -> pageOf(actor.tenantId(), narrowing, query);
    case SubmissionFilter.MatchesNothing _ ->
            new SubmissionPageView(List.of(), 0L, query.page(), query.size());
};
```

- **`MatchesNothing` answers without touching the repository:** an empty page, a total of zero, and the
  applied page and size echoed (BR-30, BR-20, BR-26). A test asserts the repository fake received no call.
- **`Narrowing`** builds the criteria from `actor.tenantId()` and the filter, and asks the repository
  for the page and the total.
- **The use case never reads a role.** There is none to read (BR-16), and the actor it receives is
  the one the gate admitted.
- **BR-20, BR-21, BR-27 still need no code branch.**

### 5.6 CQS enforced without folders

Command-query separation here is a separation of **responsibility**. It is not a separation of
folders, and the rules below enforce it without one. There are two kinds of use case in `domain/`,
told apart by the single public method each one exposes, which the existing ArchUnit rule already
pins by name:

| Kind | Public method | Takes | Returns | Must not depend on |
|---|---|---|---|---|
| read | `ask` | a `*Query` | a `*View` (or a view page) | `Submission`, any `*CommandRepository`, any `*EventPublisher`, any `*IdGenerator` |
| write | `execute` | a `*Command` | a `Result<_, ...>` | any `*QueryRepository`, any `*View` |

The discriminator is the method name and nothing else, because the name is what the rule can read
from the class without any prefix guessing. `SearchSubmissionsUseCase` is a read use case because it
exposes `ask`. A new read use case that is named `GetSubmissionUseCase` but exposes `ask` is still
a read use case, and a class named `FindSomethingUseCase` that exposes `execute` is still a write
use case. That is the point of not using prefixes. A name is a hint for a reader; the method is
what the build checks.

The rules themselves are in [§15.10](#1510-cqs-rules-verbatim).

---

## 6. SQL shape

> **Amended in revision 6:** the search reads the `submission_current` view of [§20.3](#203-the-latest-state-view-the-strategy), not a join written into each statement.

> **Amended in revision 5:** the `findPage` statement gains the approval state as a `LEFT JOIN` ([§19.9](#199-the-search-change)). `countMatching` is unchanged.

Both statements are native SQL in `@Query("""...""")`, mapped straight into the projection record.
The read repository extends `Repository`, not `CrudRepository`: a read repository that offers
`save()` will eventually be used to save.

```java
interface SubmissionQueryJdbcRepository extends Repository<SubmissionJdbcEntity, UUID> {

    @Query("""
            SELECT id AS submission_id,
                   submission_number,
                   insured_limit,
                   registered_by,
                   registered_at
            FROM submissions
            WHERE tenant_id = :tenantId
              AND (CAST(:submissionNumber AS VARCHAR) IS NULL
                   OR submission_number = CAST(:submissionNumber AS VARCHAR))
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
                                         @Param("submissionNumber") @Nullable String submissionNumber,
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
              AND (CAST(:submissionNumber AS VARCHAR) IS NULL
                   OR submission_number = CAST(:submissionNumber AS VARCHAR))
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
  (a UUID, up to 64 characters of number, an unbounded but realistically short numeric, an actor
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

> **Revision 6:** V1 to V3 are not edited. The single new migration is V4, specified in [§20.4](#204-the-migration-v4), and it is the only way the migration history changes.

> V3, which adds the approval table, is specified in [§19.8](#198-schema-v3). Its reviewed content is the one committed at `5bf01b3`, which [§20.4](#204-the-migration-v4) restores.

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
    -- COLLATE "C": BR-07 compares numbers exactly as supplied, including letter case and
    -- surrounding blank space. A database created with a case-insensitive ICU collation would
    -- otherwise make both this column's equality test and the unique index below case-folding,
    -- silently breaking BR-06 and BR-07. VARCHAR(64) counts characters, which is exactly the
    -- code-point measure the domain applies for BR-05, so an accepted number always fits.
    -- NOTE: neither the length nor this collation ever constrained the number's CHARACTER
    -- SET. The type itself refuses U+0000 outright, below SQL. See §8.3.
    submission_number  VARCHAR(64) COLLATE "C" NOT NULL,
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

    -- BR-06 + BR-08: unique PER TENANT, so the same number in another organisation is fine.
    -- This is also what settles the BR-09 race: the loser's insert conflicts.
    CONSTRAINT uq_submissions_tenant_submission_number UNIQUE (tenant_id, submission_number),

    -- Defence in depth for the domain invariants. Each one mirrors a business rule that is
    -- already refused in the aggregate; a row that violates one of them is a bug, not a refusal.
    CONSTRAINT ck_submissions_submission_number_not_empty CHECK (length(submission_number) > 0),          -- BR-04
    -- BR-29. Mirrors the domain check, and cannot fully mirror it: see §8.3. The escape syntax
    -- must be verified against PostgreSQL when the migration is written.
    CONSTRAINT ck_submissions_submission_number_no_control_chars
        CHECK (submission_number !~ '[\x01-\x1F\x7F-\x9F]'),                                         -- BR-29
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

-- No index for the number filter: uq_submissions_tenant_submission_number already builds a
-- btree on (tenant_id, submission_number), which serves both the uniqueness check and the
-- exact-number search. A second index on the same columns would only cost write throughput.
```

`scale(numeric)` requires PostgreSQL 13 or newer; the project's Testcontainers image is
`postgres:17-alpine`.

### 8.2 Indexes: which index serves which filter

| Search input | Index used | Why |
|---|---|---|
| tenant (always present, BR-17) | `idx_submissions_tenant_registered_at` — leading column | Every search carries it, so it leads the workhorse index |
| `registeredFrom` / `registeredTo` range | `idx_submissions_tenant_registered_at` — second column | A range on the second column of a composite btree after an equality on the first is an index range scan |
| ordering `registered_at DESC, id DESC` (BR-24, BR-28) | `idx_submissions_tenant_registered_at` | The index is declared in exactly the ORDER BY direction, so the planner returns rows pre-ordered: **no sort node**, and the scan stops after `LIMIT + OFFSET` rows |
| `submissionNumber` exact match | `uq_submissions_tenant_submission_number` (the unique constraint's own btree) | Equality on both columns of the index; BR-06 guarantees at most one row, so this is a single index lookup |
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

### 8.3 What the schema never constrained: the number's character set

This section exists because the first revision of this design reasoned carefully about
`submission_number` — its length, its collation, its emptiness — and none of that reasoning touched the
thing that actually broke.

**`VARCHAR(64)` and `CHECK (length(submission_number) > 0)` were never what would stop a NUL byte.**
They constrain how *many* characters the column holds and that it holds at least one; they say
nothing about *which* characters. The thing that refused U+0000 was **the PostgreSQL text type
itself, enforced by the driver** — a `text`/`varchar` value cannot contain U+0000 at all, because
the wire protocol and the server's internal string representation are NUL-terminated. That is not a
constraint anybody declared and not a constraint anybody can relax. It sits **below the level where
business refusals are decided**, so when it fired it could only come out as an infrastructure
failure: a 500, for an input the business had called valid, with no refusal reason to name.

Two consequences for this design:

- **The new `ck_submissions_submission_number_no_control_chars` CHECK is defence in depth and cannot be
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
        @Column("submission_number") String submissionNumber,
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
                (id, tenant_id, submission_number, insured_limit, registered_by, registered_at, version)
            VALUES
                (:id, :tenantId, :submissionNumber, :insuredLimit, :registeredBy, :registeredAt, 0)
            ON CONFLICT (tenant_id, submission_number) DO NOTHING
            """)
    int insertIfNumberFree(/* one @Param per column */);
}
```

`ON CONFLICT ... DO NOTHING` plus a row-count check — `1` means inserted, `0` means the
organisation already used the number — is the same idiom `inbox_events` already uses in V1, and
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
    public Result<SubmissionId, RegisterSubmissionError.NumberAlreadyUsed> insert(
            SubmissionSnapshot snapshot) {
        // insertIfNumberFree(...) -> 0 means duplicate: Result.failure(NumberAlreadyUsed)
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
non-null `version`; a duplicate `(tenant_id, submission_number)` leaves the earlier row untouched and
inserts nothing; the same number under a different `tenant_id` inserts normally (BR-08).

### 9.4 Query adapter

`SubmissionQuerySqlAdapter implements SubmissionQueryRepository`, in `adapters/persistence`. It is a
driven adapter for a port declared in `domain/`, exactly as the command adapter is. It unwraps
`criteria.number()` into `number.value()` for the bound `submissionNumber` parameter, so the SQL in
[§6](#6-sql-shape) is unchanged, and it returns the views it builds from rows. Since revision 6 it reads the `submission_current` view ([§20.3](#203-the-latest-state-view-the-strategy)), and the projection carries no internal identity.

**Building a view from a row does not parse.** The adapter copies the stored number and amount into the view as `String` and `BigDecimal`, and wraps the stored id in a `SubmissionId`, which cannot fail ([§5.3](#53-views)). There is no failure branch, so there is no `throw`, no new error type and no refusal path on a search, which must not refuse. The values are trusted because the store will hold nothing else: `VARCHAR(64)` bounds the length, `CHECK (length > 0)` excludes the empty string, the control-character `CHECK` excludes every category-Cc character except U+0000, which PostgreSQL cannot store, and the two insured-limit `CHECK`s hold the amount to BR-11 and BR-12. Every row the database can hold is one the write side could have registered.

The adapter never loads `SubmissionJdbcEntity` into a use case and never sees the aggregate, which
is package-private in `domain/`.

---

## 10. Logging and masking

| Field | May it be logged? | Rule | How this design enforces it |
|---|---|---|---|
| `insuredLimit` | **Never** | BR-22 | `InsuredLimit.toString()` prints `***`, so `RegisterSubmissionCommand` is safe by composition; the raw holders — `SubmissionSnapshot`, `SubmissionSummaryView`, `SubmissionRegistered`, and the filter bounds in `SubmissionFilter.Narrowing` and `SubmissionSearchCriteria` — keep explicit masks; no error variant carries the amount; no `ProblemDetail` echoes it |
| `submissionNumber` | Yes, in full — **with category-Cc code points escaped as `\uXXXX`** | BR-23, BR-29 | a valid `SubmissionNumber` or `NumberCriterion` cannot contain a control character, so it prints as it is; the raw holders (`SubmissionSnapshot`, `SubmissionRegistered`, `NumberAlreadyUsed`, the `ProblemDetail`) escape it. Escaping is not redaction: the value stays complete and readable, and an unescaped control character in a log line is log injection ([§3.2](#32-refusals-nine-as-the-caller-sees-them)) |
| `tenantId` | Not classified | spec §6 | printed; it is the isolation boundary, not a secret |
| `actorId` / `registeredBy` / `approvedBy` | **Not logged** | OPEN-01, BR-42 | masked `***` in the `toString()` of `ActorContext` (its first, upstream holder), `AuthenticatedCaller`, `SubmissionSnapshot`, `SubmissionSummaryView`, `SubmissionRegistered`, `SubmissionApproval` and `SubmissionApproved` (as `approvedBy`); no error variant carries it |
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
- **Q6 — What should an unparseable filter value, or an empty number filter, do?** The spec
  says no filter value is ever refused (BR-20, BR-21, BR-30), yet `insuredLimitFrom=abc` or
  `registeredFrom=yesterday` is rejected with `400` by Spring's type conversion, outside the
  one-reason list and before the guard. Separately, `number=` (present but empty) is silent in
  the spec: this design lets it match nothing, because no registered number is empty, which is
  the literal reading of BR-07 ("compared exactly as supplied"), but "not supplied" is an arguable
  alternative. Options for the first: keep the `400` as a transport error and reword the spec to
  say "no well-formed filter value"; or bind the parameters as text, parse after the guard, and
  treat an unparseable one as matching nothing.
- **Q7 — Does the spec's wording need to change for the layer split? This design says no.** The
  spec says "every refusal reason a command can produce is listed" and that the only thing judged
  before a search runs is that the caller is identified. Read as statements about the *service's*
  operations, both remain exactly true, and the spec deliberately names no layer. Confirm that
  reading, since the use case itself now refuses far less than the list ([§3.2](#32-refusals-nine-as-the-caller-sees-them)).

8. **How are BR-05's "64 characters" counted?** Raised by the analyst and not decided by the spec. This design counts **code points**, because that is what PostgreSQL `VARCHAR(64)` counts, so an accepted number cannot overflow the column. UTF-16 code units would refuse a number of 64 emoji that code points accept. BR-32 inherits the same count. This is the design's reading, **not** a decision; it is a question for the product owner.

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
- **R9 — The `query/` package and its prescriptions.** Every instruction, skill, agent and AGENTS.md line that names `query/`, with the wording it needs, is listed in [§18.4](#184-documents-and-rules-outside-this-design). It includes the two architect-audit lines that prescribe `query/`.
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
3. **How BR-05 counts characters** is now a question for the product owner, Q8 in [§11.1](#111-questions-for-the-product-owner). Code points are used until it is answered.
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
9. **Logging a refused number requires escaping, and that needs confirming against BR-23.** BR-23
   says the number may appear in a log "in full and readable". For a number refused under
   BR-29 — the only numbers that can now contain a control character — writing it raw is **log
   injection**: a NEWLINE forges an extra log entry and an escape sequence can manipulate whoever
   reads the log. This design escapes every category-Cc code point as `\uXXXX`, which is both
   readable and inert, and argues that satisfies BR-23 rather than bending it. An interpretation,
   not a stated rule, and the only item here with a security consequence if it is got wrong. For
   `@sec-reviewer`.
10. **"No restriction" rules need an adapter check before they are confirmed.** The standing lesson
    from [§8.3](#83-what-the-schema-never-constrained-the-numbers-character-set), carried
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
    compiler.** `SubmissionNumber.parse` is public, so a controller could call it before `admit`.
    What the compiler and the build *do* enforce is that the use case is unreachable without an
    `Admission` ([§16.3](#163-the-order-at-the-edge)). Said plainly so nobody believes it is
    stronger than it is.
13. **Resolved in revision 4.** `SubmissionNumber.parse` returns its own `SubmissionNumberError`, shared by both sides. Each operation maps its three variants into its own error ([§15.2](#152-submissionnumber)). The cost is three records declared in two places.
14. **Resolved in revision 4.** `OperationGate` has two type parameters, as the product owner decided. The proof that search has one refusal moved from the type to a test ([§5.2](#52-refusals-search-still-has-exactly-one)).

### Settled since the first revision of this document

- **`submissionId` in the search result** — confirmed by the product owner ([§5.3](#53-views)).
- **0-based page numbering** — confirmed by the product owner
  ([§5.1](#51-the-query-record-and-the-filter-parse)).
- **Fail-closed `ActorContextResolver`, no Spring Security in this module** — confirmed
  ([§13.1](#131-web-edge)); the identity source behind it stays open, as item 2 above.
- **Publishing is a port, not a transport** — architectural correction accepted; it withdrew the
  combined port ([§3.5](#35-the-port-split-and-the-trade-off-it-accepts)) and removed every topic,
  message key, wire contract and relay from this design ([§12](#12-events-and-the-publishing-port)).
- **Control characters in a number are refused (BR-29)** — decided by the product owner on
  2026-11-02 after exploratory testing, who was offered the narrow NUL-only fix and chose the whole
  of category Cc. The reversal it carries (TAB, NEWLINE and NUL move from valid to refused) is
  deliberate. SPACE is untouched: it is not a control character, and a number of spaces is still
  valid, still not trimmed.
- **A control character in the number filter matches nothing and is a success (BR-30)** —
  decided on 2026-11-02 after review found it answered `500` (SEC-10). Represented as a type
  ([§5.1](#51-the-query-record-and-the-filter-parse)).
- **Search's one refusal is proven by a test, not by the type** — the `OperationGate<C, R>` downgrade was accepted by the product owner ([§5.2](#52-refusals-search-still-has-exactly-one)).
- **A number filter longer than 64 matches nothing (BR-32)** — decided by the product owner; it extends BR-30, and exactly 64 is compared normally ([§5.1](#51-the-query-record-and-the-filter-parse)).
- **Views carry stored values as `String` and `BigDecimal`** — decided by the product owner: parse-don't-validate applies at the input boundary, and a view is output from our own store ([§5.3](#53-views), [§9.4](#94-query-adapter)).
- **Parse, don't validate** — decided by the product owner on 2026-11-02 ([§15](#15-value-objects)).
- **Authorisation is an adapter concern** — decided by the product owner on 2026-11-02
  ([§16](#16-the-access-guard)). The two designs it replaced are in [§17](#17-rejected-designs).

### Contradiction found — reported, and since fixed

The spec is internally consistent; I found nothing in it that is wrong. The one conflict was with a
**repository skill**, not the spec: `.github/skills/hexagonal-cqrs-slice/SKILL.md` illustrated the
aggregate with `submissionNumber.isBlank()` and a variant named `BlankSubmissionNumber`, and
`NonPositiveInsuredLimit(BigDecimal insuredLimit)` carrying the amount — the first would have
refused the whitespace-only number that BR-04 plus BR-07 make *valid*, and the second violates
BR-22. **That skill has since been corrected** (`isEmpty()`, variant names matching the spec, and
two new checklist items: no sensitive value in an error record, and validation must match the
specification literally). The rule still stands in this design for anyone reading an older copy:
**`isEmpty()`, and no amount inside any error variant.**

BR-29 does **not** reopen that question, and this is the one place the two are easy to confuse.
`isBlank()` is still wrong: it refuses a number of spaces, which the spec still calls valid.
BR-29 refuses category Cc, which excludes SPACE. A reader who takes BR-29 as licence to switch to
`isBlank()` reintroduces the original defect and breaks a rule the product owner has now confirmed
twice.

---

## 12. Events and the publishing port

| Event | Raised when | Carries | Announced through |
|---|---|---|---|
| `SubmissionDomainEvent.SubmissionRegistered` | a registration succeeds (BR-15) | organisation, submission number, number, insured limit, registering caller, registration moment | `SubmissionEventPublisher` — a port. What is behind it is not decided here |
| `SubmissionDomainEvent.SubmissionApproved` | an approval succeeds (BR-40) | the organisation, the submission number, the approver's identity (masked in logs), the moment of approval. No insured limit ([§19.7](#197-the-event-submissionapproved-br-40)) | `SubmissionEventPublisher`, the same port |

```java
public sealed interface SubmissionDomainEvent {

    UUID eventId();

    SubmissionNumber submissionNumber();

    UUID tenantId();

    Instant occurredAt();

    record SubmissionRegistered(UUID eventId,
                                SubmissionNumber submissionNumber,
                                UUID tenantId,
                                BigDecimal insuredLimit,
                                String registeredBy,
                                Instant occurredAt) implements SubmissionDomainEvent {

        @Override
        public String toString() {   // BR-22 amount, OPEN-01 actorId
            return "SubmissionRegistered[eventId=" + eventId + ", submissionNumber=" + submissionNumber
                    + ", tenantId=" + tenantId
                    + ", insuredLimit=***, registeredBy=***"
                    + ", occurredAt=" + occurredAt + "]";
        }
    }
}
```

The sealed interface has two variants, `SubmissionRegistered` (BR-15) and `SubmissionApproved` (BR-40, [§19.7](#197-the-event-submissionapproved-br-40)). The spec names exactly these two business facts. Searching announces nothing — a read is not a business fact — and the event is never raised
for a refused registration, which this design makes structural through `Result.peek`, whose side
effect runs on the success branch only (see [§3.3](#33-use-case)).

`eventId` is a property of the fact — a stable identity for "this announcement", which is what lets
any eventual recipient recognise a repeat. It is not a transport artefact, so it stays on the record.

### 12.1 Deferred adapter decision: how the event is published

**No decision is made here, and that is the design.** The domain publishes a business fact through
a port. What sits behind that port is an adapter decision, and this module can be built, tested and
reviewed without it: nothing in `domain/`, the schema in [§8](#8-schema) or the two
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
- **If the chosen mechanism orders or routes by key, the key is the tenant and the submission number, never the internal identity (BR-47).** That is a
  property of the event (one submission's facts belong in one sequence), not a design choice being
  smuggled in: the `submissionNumber` accessor on the sealed interface is all the adapter needs, and this
  document names no mechanism that would use it.
- **Whatever it writes to, check first that the store can hold every value the event carries** —
  the lesson of [§8.3](#83-what-the-schema-never-constrained-the-numbers-character-set), applied
  before rather than after. The event carries `submissionNumber`, and BR-29 now guarantees it is free of
  control characters, so a line-delimited or text-based format is safe from a number that breaks
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
    private static final Permission REGISTER_SUBMISSION =
            Permission.rolesOnly(ActorRole.UNDERWRITER, ActorRole.SENIOR_UNDERWRITER);  // BR-02, BR-03
    private static final Permission SEARCH_SUBMISSIONS =
            Permission.anyIdentifiedCaller();                                           // BR-16

    @Bean
    Clock clock() { return Clock.systemUTC(); }

    @Bean
    SubmissionIdGenerator submissionIdGenerator();            // UUID.randomUUID() behind the port

    @Bean
    OperationGate<RegisterSubmissionCommand,
                  Result<SubmissionId, RegisterSubmissionError.NumberAlreadyUsed>>
            registerSubmissionGate(SubmissionCommandRepository repository,
                                   SubmissionEventPublisher eventPublisher,
                                   SubmissionIdGenerator idGenerator,
                                   Clock clock);

    @Bean
    OperationGate<SearchSubmissionsQuery, SubmissionPageView>
            searchSubmissionsGate(SubmissionQueryRepository repository);
}
```

How it fits together:

```
any inbound adapter            OperationGate<C, R>                         raw use case
(controller, consumer, job)    ┌─────────────────────────────┐
        │                      │ Permission                  │       NOT a bean. Built inside the
        │ admit(caller) ─────> │   check(caller)             │       @Bean method and handed to the
        │ <── Admission | Refusal │                           │       gate as a method reference.
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
that is what keeps `domain/` free of Spring imports. The adapters are package-private
`@Component`s; Spring Data repository interfaces are package-private and discovered automatically.

**The raw use cases are not beans.** Each is constructed inside the `@Bean` method of its gate and
passed to it as a method reference (`useCase::execute`), so there is nothing for another inbound
adapter to inject even by accident. Only this package can name the class at all, and the build
fails if any other adapter tries ([§16.4](#164-bypass-is-a-build-failure)).

**The permission table is two declarations.** Registration: `UNDERWRITER` and `SENIOR_UNDERWRITER`,
treated identically (BR-02, BR-03). Search: any identified caller, with no role consulted (BR-16).
Both live in this class and nowhere else, so who may do what can be read in one place and
reviewed in one place.
**The permission data is named `AccessPolicy`** (design D14). The implementation's `AccessPolicy` is the seam through which this table is read: `mayRegister` and `mayApprove` answer from the fixed role set above, and `tenantIdFrom` belongs to the identity source. The approving set is `ActorRole.canDecide()`, which `ActorRoleTest` pins to exactly `UNDERWRITER` and `SENIOR_UNDERWRITER`. The table, the seam and the test name one set, so they cannot drift apart.


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
record RegisterSubmissionRequest(@Nullable String submissionNumber, @Nullable BigDecimal insuredLimit) {

    /** The per-request-type parser: a one-line hand-off to the domain's own parse. */
    Result<RegisterSubmissionCommand, RegisterSubmissionError> toCommand() {
        return RegisterSubmissionCommand.parse(submissionNumber, insuredLimit);
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
| success | `201 Created`, `Location: /api/submissions/{number}`, body carries the submission number (BR-47) | — |
| `Access(CallerNotIdentified)` | `401` | `caller-not-identified` |
| `Access(PermissionDenied(role))` | `403` | `role-may-not-register` |
| `Rejected(NumberMissing)` | `400` | `number-missing` |
| `Rejected(NumberTooLong)` | `400` | `number-too-long` |
| `Rejected(NumberContainsControlCharacter)` | `400` | `number-contains-control-character` |
| `Rejected(InsuredLimitMissing)` | `400` | `insured-limit-missing` |
| `Rejected(InsuredLimitNotPositive)` | `400` | `insured-limit-not-positive` |
| `Rejected(InsuredLimitPrecisionInvalid)` | `400` | `insured-limit-precision-invalid` |
| `Rejected(NumberAlreadyUsed)` | `409` | `number-already-used` |

Status codes, problem types and detail texts are **unchanged** from the previous revision; the
contract test ([§16.7](#167-tests)) pins them.

### 13.3 The search request, step by step

```
GET /api/submissions?submissionNumber=&insuredLimitFrom=&insuredLimitTo=&registeredFrom=&registeredTo=&page=&size=
  1. caller    = actorContextResolver.resolve()        Optional<AuthenticatedCaller>
  2. admission = searchGate.admit(caller)              401 or 403 end here. 403 is unreachable for
                                                        search by construction; §5.2 explains.
  3. filter    = SubmissionFilter.parse(number, from, to, registeredFrom, registeredTo)
                                                        total: it cannot refuse
  4. page      = admission.run(new SearchSubmissionsQuery(filter, page, size))
  5. 200 with SubmissionPageView
```

The controller's refusal `switch` has two arms, `CallerNotIdentified` (`401`) and `PermissionDenied`
(`403`), and no `default`. The second arm is unreachable for search and is proven so by a test
([§16.7](#167-tests)), not by the type. The transport defaults are `page=0`, the first page, and
`size=100`, the maximum, which keeps the design to one page-size number ([§7](#7-engineering-decision-maximum-page-size)).

The filter parse is total and silent, so its position relative to the guard changes no response. It
runs after the guard anyway, which keeps the spec's statement true: the only thing judged before a
search runs is that the caller is identified.



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
organisation's data. `NumberAlreadyUsed` is safe to return as `409` precisely because
uniqueness never crosses organisations (BR-08): the conflicting submission is always one of the
caller's own. The number is escaped on the way into any `ProblemDetail` or log line — every
code point of category Cc rendered as `\uXXXX` ([§10](#10-logging-and-masking)) — because the
BR-29 refusal path is where such a number is guaranteed to appear.

---

## 14. Not doing

Explicitly out of scope, so the next agent does not add it:

- **No status column and no state machine.** The state is two values, *registered* and *approved* (BR-36), derived from the existence of an approval row ([§19.5](#195-the-approval-state)), not stored on the registered row.
- **No decision other than approval** — no referral and no declinature, and no event about one. Approval is specified in [§19](#19-approval).
- **No UPDATE or DELETE on a fact table, and no TRUNCATE** (BR-44, [§20.2](#202-append-only-how-the-database-enforces-it)).
- **No amend, withdraw, cancel, delete or un-approve** operation. An approval is final (BR-36). A submission is immutable once registered,
  which is why `submissions` has no `UPDATE` path in this design. An approval is a row in its own table, so it cannot create one ([§19.5](#195-the-approval-state)).
- **No authority limit** anywhere. BR-03 treats `UNDERWRITER` and `SENIOR_UNDERWRITER`
  identically; the register permission is a two-element set in the wiring.
- **No currency field** in any record, column, event or API payload. Every amount is euro by
  definition (BR-14).
- **No maximum insured limit** (BR-13) — hence unconstrained `NUMERIC`, not `NUMERIC(19, 2)`.
- **No character-set restriction on the number beyond BR-29.** Control characters (category Cc)
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
- **No permission check, and no role, in `domain/`.** No `ActorRole` import, and no
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
   `public record SubmissionNumber(String value)` would let anyone write
   `new SubmissionNumber("\u0000")`. Checked with `javac` 25: a private canonical constructor on a
   public record is rejected with "invalid canonical constructor… attempting to assign stronger
   access privileges; was public". Records remain right for data with no invariant beyond its
   components' types: `SubmissionId`, the commands, the views, the errors.
2. **`parse` returns a `Result` when the spec names the refusal, and an `Optional` when it does
   not.** `Result<_, RegisterSubmissionError>` for the write-side values, whose failures the spec
   lists; `Optional` for `ActorContext` and `AuthenticatedCaller`, whose failures carry no reason the spec names. No `throw`
   and no `Objects.requireNonNull` — Checkstyle `noThrowNew` and `noRequireNonNull` fail the build.
3. **Raw input enters only as the parameter of a method named `parse`.** That parameter may be
   `@Nullable`, because absence is data (and for BR-04 and BR-10 it is a named refusal). Nowhere
   else in `domain/` does a method take a raw `String` or `BigDecimal`
   ([§15.9](#159-enforcement)).
4. **Immutable, with value equality** on the wrapped value (Checkstyle `EqualsHashCode` requires
   `equals` and `hashCode` together).
5. **It owns its own log-safety.** `InsuredLimit.toString()` prints `***`. A valid
   `SubmissionNumber` cannot contain a control character, so its text is
   already inert. A record holding one therefore prints safely *by composition*, and the class of
   defect where a record forgot its mask (SEC-02, SEC-03, SEC-04) cannot recur through a value
   object. Raw holders — the snapshot, the event, the summary view, the filter bounds — keep their
   explicit masks.
6. **A value object admits only what the store can hold.** This is BR-29's lesson turned into a
   rule ([§8.3](#83-what-the-schema-never-constrained-the-numbers-character-set)): "no
   restriction" is only as true as the storage beneath it. Each value object states its storage fit
   ([§15.8](#158-storage-fit-in-one-table)).
7. **There is no second way in.** No `of`, `trusted`, `unsafe`, `fromStored`. Even the persistence
   adapter reconstitutes by parsing ([§4.2](#42-reconstitute-parses-too)). A back door is how a
   guarantee erodes, one convenient call at a time.

### 15.2 `SubmissionNumber`

```java
public final class SubmissionNumber {

    /** BR-05 — counted in code points. */
    public static final int MAX_LENGTH = 64;

    public static Result<SubmissionNumber, SubmissionNumberError> parse(@Nullable String text);

    public String value();
    // equals / hashCode on value(); toString prints the value (no control character can be in it)
}

public sealed interface SubmissionNumberError {
    record Missing() implements SubmissionNumberError { }                          // BR-04
    record TooLong(int maxLength, int actualLength) implements SubmissionNumberError { }   // BR-05
    record ControlCharacter(int codePoint, int index) implements SubmissionNumberError { } // BR-29
}
```

**The value object has its own error type.** Previously `parse` returned `RegisterSubmissionError`,
which made the value object depend on one operation's failure type. The search filter then had to
read a registration error and handle variants that a number can never produce. Both sides now
share `SubmissionNumberError`, which has exactly the three things a number can be refused for,
and each operation maps those three into its own type.

The register mapping is one total, exhaustive function, in `RegisterSubmissionCommand.parse`:

```java
return switch (error) {
    case SubmissionNumberError.Missing _                 -> new RegisterSubmissionError.NumberMissing();
    case SubmissionNumberError.TooLong(var max, var len) -> new RegisterSubmissionError.NumberTooLong(max, len);
    case SubmissionNumberError.ControlCharacter(var cp, var at) ->
            new RegisterSubmissionError.NumberContainsControlCharacter(cp, at);
};
```

The search mapping is in [§5.1](#51-the-query-record-and-the-filter-parse), and it is the same
exhaustive shape with a different meaning for each arm. The three `RegisterSubmissionError`
number variants keep their names and their wire contract, so nothing a caller sees changes. The
cost is three records declared in two places, which is the price of a value object that does not
depend on an operation.

The three checks run in the spec's order, inside `parse`, and the first failure is the result:

| Input | Outcome |
|---|---|
| `null`, `""` | `Missing` (BR-04) |
| `" "`, `"   "` | valid — present, non-empty, no control character, not normalised away (BR-07) |
| exactly 64 code points, including 64 emoji (128 UTF-16 units) | valid |
| 65 code points | `TooLong(64, 65)` (BR-05) |
| 65 code points, one of them a TAB | `TooLong` — length is judged before content |
| `"AB\tCD"`, `"\u0000"`, `"\u0085"` | `ControlCharacter(codePoint, index)` (BR-29) |
| `"a b"`, `"  a  "` | valid, preserved exactly (BR-07) |

- **`isEmpty()`, not `isBlank()`.** BR-04 refuses a number that "contains no characters at all",
  and BR-07 says two numbers differing in surrounding blank space are *different* numbers. A
  number of spaces is valid. `isBlank()` would refuse it, and BR-29 does not change that: "control
  character" is narrower than "whitespace". TAB and NEWLINE are both; **SPACE (U+0020) is only
  whitespace**.
- **The test is the category, not a list.** `ControlCharacters.firstIn` ([§15.5](#155-the-single-definition-of-category-cc))
  is exactly Unicode category Cc. `Character.isWhitespace` and a `\s` regex are both wrong: they
  disagree with the category over most of the upper range, and U+0085 NEXT LINE is the case that
  catches them.
- **Code points, not `String.length()`.** The reported index is a code-point index, the same way
  BR-05 counts length.
- **No trimming, no case folding, no normalisation** (BR-07). Hence `COLLATE "C"` on the column.
- **Storage fit:** at most 64 code points fits `VARCHAR(64)`, since PostgreSQL counts characters, and
  excluding every Cc character excludes U+0000, which no text column can hold ([§15.8](#158-storage-fit-in-one-table)).

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
| `MAX_LENGTH` | 64 | `SubmissionNumber` | `RegisterSubmissionCommand.MAX_SUBMISSION_NUMBER_LENGTH` | the factory, `NumberTooLong`, tests, an IT asserting the column is that wide |
| `REQUIRED_SCALE` | 2 | `InsuredLimit` | `RegisterSubmissionCommand.REQUIRED_INSURED_LIMIT_SCALE` | the factory, `InsuredLimitPrecisionInvalid`, tests |
| `MAX_ACTOR_ID_LENGTH` | 255 | `ActorContext` | — | `ActorContext.parse`, an IT asserting `registered_by` is that wide |
| `MIN_PAGE_SIZE`, `MAX_PAGE_SIZE` | 1, 100 | `SearchSubmissionsQuery` | unchanged | the compact constructor ([§7](#7-engineering-decision-maximum-page-size)) |

Each constant lives on the one type that owns the rule, so there is one place to change it and one
place a test points at. They are named `public static final` constants because Checkstyle
`noMagicNumbers` rejects the bare `64` and `255`. The two width constants are the schema's twin:
the migration comment and the IT cite them, so the column and the constant cannot drift apart
unnoticed.

### 15.5 The single definition of category Cc

`ControlCharacters` **stays in `shared/kernel`.** Its justification changed in this revision, and the
honest account is this.

It moved into the kernel originally so that `query/` and `domain/` could both reach it. `query/` no
longer exists, so that reason is gone. The rule would now fit in `domain/`, and it is worth asking
whether it should move back.

It should not, because the kernel also holds `ActorContext`, and `ActorContext.parse` validates the
caller's `actorId` against the same category ([§15.6](#156-the-caller-from-claims)). The kernel may
not depend on `domain/`, so if `ControlCharacters` moved to `domain/`, the caller type would either
have to lose its validating factory, which reopens the gap [§15.6](#156-the-caller-from-claims)
exists to close, or move out of the kernel, which means moving `ActorContext` too. Either is a
larger change than the one it avoids.

The cost of keeping it in the kernel is that the kernel holds one character-class fact that a
business rule also uses. That is a fact about Unicode, not a business rule, so it does not make the
kernel a home for domain logic. It stays at four types in the kernel, and that count does not
include a second home for the definition.

```java
// shared/kernel
public final class ControlCharacters {
    private ControlCharacters() { }

    public static boolean isControl(int codePoint);           // Character.getType(cp) == Character.CONTROL
    public static Optional<Found> firstIn(String text);       // first offender, or empty

    public record Found(int codePoint, int index) { }         // index counted in code points
}
```

Callers: `SubmissionNumber`, `ActorContext.parse` (for `actorId`) and `LogSafeText`. `NumberCriterion`
is no longer a caller, because it no longer exists.

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

### 15.7 The search filter reuses `SubmissionNumber`

`NumberCriterion` **is deleted.** It existed only because `query/` could not import `domain/`, so
it duplicated `SubmissionNumber`'s rule with a different name and a different meaning. Once the
read side is in `domain/`, the duplicate has no reason to exist.

The search filter is the same value object with a different reading of its failures. Its complete
mapping is in [§5.1](#51-the-query-record-and-the-filter-parse), and summarised here:

| Number filter | `SubmissionNumber.parse` | Filter |
|---|---|---|
| absent | — | not supplied, narrows nothing (BR-19) |
| present but empty | `Missing` | **not supplied (BR-31)** |
| contains a category-Cc character | `ControlCharacter` | **`MatchesNothing` (BR-30)** |
| more than 64 code points | `TooLong` | **`MatchesNothing` (BR-32)** |
| valid | success | the number, compared exactly (BR-07) |

The difference in meaning is the point, and it is why the failure is not a refusal on this side.
A registration with a bad number is a request the business cannot honour. A search with one asks
a question whose true answer is "none". The spec asks for an empty success (BR-30, BR-31), so the
same failure folds into a different outcome.

---

### 15.8 Storage fit, in one table

| Value object | Admits | The store | Fit |
|---|---|---|---|
| `SubmissionNumber` | 1 to 64 code points, no category Cc | `VARCHAR(64) COLLATE "C"`, `CHECK (length > 0)`, `CHECK` no control character | exact. U+0000 is stopped here, not by the driver |
| `InsuredLimit` | strictly positive, scale 2, any magnitude | `NUMERIC`, `CHECK (> 0)`, `CHECK (scale = 2)` | exact up to PostgreSQL's digit limit (Q5) |
| `ActorContext.actorId` | non-blank, up to 255 code points, no category Cc | `registered_by VARCHAR(255)` | exact; needs the width IT |
| `ActorContext.tenantId` | any canonical UUID | `UUID` | exact |
| the search number filter | any text | a bound parameter, compared with the `VARCHAR(64)` column | holds: a longer value is `MatchesNothing` (BR-32), a control character is `MatchesNothing` (BR-30), and the request-size limit bounds it in practice |

---

### 15.9 Enforcement

Three ArchUnit rules pin the principle, each to be broken on purpose once. They cover `domain/` alone,
because the read side is in it. `failOnEmptyShould` is `true`, so each must match something.

- **`raw_input_enters_only_through_parse`** — a *method* declared in a class in `..domain..` that has a parameter of type `String` or `BigDecimal` must be named `parse`. It is written over `methods()`, and ArchUnit's `methods()` excludes constructors, so a record's canonical constructor is never caught. That is how views (`SubmissionSummaryView`), the snapshot, the event, the error records and the search criteria hold `String` and `BigDecimal` without tripping it: they receive those values through a constructor and expose them through accessors, which take no parameter.
- **`commands_and_queries_carry_only_parsed_values`** — no *field* of a class whose simple name ends in `Command` or `Query` has raw type `String` or `BigDecimal`, and no field of `SubmissionFilter` or of a record nested in it has raw type `String`. That is the whole of its scope: commands, queries and the filter, which are the inputs. A view's name ends in `View`, the criteria's in `Criteria`, the snapshot's in `Snapshot`, so none of them is selected. The filter's `BigDecimal` and `Instant` bounds are allowed on purpose, because every value of those types is a legal comparison value; its number is an `SubmissionNumber`, so a `String` there would mean the parse was bypassed.

  The selector combines the two name tests as one predicate, `simpleNameEndingWith("Command").or(simpleNameEndingWith("Query"))`, passed to `areDeclaredInClassesThat(...)`, rather than as `.or()` on the fluent chain, which would widen the whole rule. *Domain objects hold no raw input fields* is now exactly what it says: input objects. Output records are out of its scope by design, and the reason is recorded in [§5.3](#53-views).
- **`guarded_types_have_no_public_constructor`** — a public class that is not a record, interface or
  enum, in `..domain..`, `..shared.kernel..` or `..adapters.access..`, and is not a `*UseCase` or an
  `OperationGate`, has no public constructor. A record cannot satisfy it, so an invariant guarded by a
  record is caught.

Tests, all pure and fast, with no use case and no fake in sight: `SubmissionNumberTest` (the table in
[§15.2](#152-submissionnumber), plus a supplementary character before the offender to prove the index
is in code points), `InsuredLimitTest`, `RegisterSubmissionCommandParseTest`, `ControlCharactersTest`,
`SubmissionFilterTest` (a category-Cc number is `MatchesNothing`; spaces only is `Narrowing`; `null`
is no criterion; `""` is no criterion; a contradictory range is still `Narrowing`), and two integration
tests asserting `SubmissionNumber.MAX_LENGTH` and `ActorContext.MAX_ACTOR_ID_LENGTH` equal the actual
column widths.

---

### 15.10 CQS rules, verbatim

These replace the query-package rules. Every rule discriminates by the public method each use case
exposes, never by a package and never by a name prefix. A name prefix is a hint to a reader, not a
property the build can check, and a prefix rule written with `.and()` followed by `.or()` matches
every class that starts with the prefix anywhere in the tree, because `.or()` applies to the whole
chain.

```java
// ---------------------------------------------------------------- CQS, without folders

/** A public method with this name. The name of a use case's one public method is what makes it read or write. */
private static DescribedPredicate<JavaClass> exposingPublicMethodNamed(String name) {
    return new DescribedPredicate<>("exposing a public method named '" + name + "'") {
        @Override
        public boolean test(JavaClass type) {
            return type.getMethods().stream().anyMatch(method ->
                    method.getName().equals(name)
                            && method.getModifiers().contains(JavaModifier.PUBLIC));
        }
    };
}

@ArchTest
static final ArchRule no_query_package_exists =
        noClasses()
                .should().resideInAPackage("..query..")
                .because("CQS is a separation of responsibility, not of folders. Read use cases live in "
                        + "domain/ beside the write side. A query/ package reintroduces the import ban "
                        + "that forced duplication and primitive obsession");

@ArchTest
static final ArchRule read_use_cases_do_not_depend_on_the_write_side =
        noClasses()
                .that().haveSimpleNameEndingWith("UseCase")
                .and(exposingPublicMethodNamed("ask"))
                .should().dependOnClassesThat()
                .haveSimpleName("Submission")
                .or().haveSimpleNameEndingWith("CommandRepository")
                .or().haveSimpleNameEndingWith("EventPublisher")
                .or().haveSimpleNameEndingWith("IdGenerator")
                .or().haveSimpleNameEndingWith("Command")
                .because("a read use case (ask) must not touch the aggregate, a command port, or a "
                        + "command: a read cannot change state, and the rule keeps it that way");

@ArchTest
static final ArchRule write_use_cases_do_not_depend_on_the_read_side =
        noClasses()
                .that().haveSimpleNameEndingWith("UseCase")
                .and(exposingPublicMethodNamed("execute"))
                .should().dependOnClassesThat()
                .haveSimpleNameEndingWith("QueryRepository")
                .or().haveSimpleNameEndingWith("View")
                .or().haveSimpleNameEndingWith("Query")
                .or().haveSimpleName("SubmissionFilter")
                .or().haveSimpleName("SubmissionSearchCriteria")
                .because("a write use case (execute) must not read: a write that depends on a query "
                        + "port or a view is deciding on data it did not load through its own port");

@ArchTest
static final ArchRule aggregate_does_not_depend_on_read_types =
        noClasses()
                .that().haveSimpleName("Submission")
                .should().dependOnClassesThat()
                .haveSimpleNameEndingWith("View")
                .or().haveSimpleNameEndingWith("Query")
                .or().haveSimpleName("SubmissionFilter")
                .or().haveSimpleName("SubmissionSearchCriteria")
                .because("the aggregate protects the write model; a projection must not shape it");

@ArchTest
static final ArchRule use_cases_expose_exactly_one_public_method_named_ask_or_execute =
        classes()
                .that().haveSimpleNameEndingWith("UseCase")
                .should(new ArchCondition<JavaClass>(
                        "expose exactly one public method, named 'ask' (taking a *Query) or "
                                + "'execute' (taking a *Command)") {
                    @Override
                    public void check(JavaClass useCase, ConditionEvents events) {
                        var publicMethods = useCase.getMethods().stream()
                                .filter(method -> method.getModifiers().contains(JavaModifier.PUBLIC))
                                .toList();
                        if (publicMethods.size() != 1) {
                            events.add(SimpleConditionEvent.violated(useCase,
                                    useCase.getName() + " must expose exactly one public method, but has "
                                            + publicMethods.stream().map(JavaMethod::getName).toList()));
                            return;
                        }
                        var method = publicMethods.getFirst();
                        var parameters = method.getRawParameterTypes();
                        var takesOne = parameters.size() == 1;
                        var conforms = switch (method.getName()) {
                            case "ask" -> takesOne && parameters.getFirst().getSimpleName().endsWith("Query");
                            case "execute" -> takesOne && parameters.getFirst().getSimpleName().endsWith("Command");
                            default -> false;
                        };
                        if (!conforms) {
                            events.add(SimpleConditionEvent.violated(useCase,
                                    useCase.getName() + "." + method.getName() + " must be 'ask(*Query)' or "
                                            + "'execute(*Command)'"));
                        }
                    }
                })
                .because("each UseCase is a single business operation with one reason to change, and its "
                        + "one public method declares whether it reads or writes");

@ArchTest
static final ArchRule query_controllers_depend_on_no_write_use_case =
        noClasses()
                .that().haveSimpleNameEndingWith("QueryResource")
                .should().dependOnClassesThat(exposingPublicMethodNamed("execute"))
                .because("a query endpoint may call only an ask use case; calling execute from it would "
                        + "let a read request change state");

@ArchTest
static final ArchRule command_controllers_depend_on_no_read_use_case =
        noClasses()
                .that().haveSimpleNameEndingWith("CommandResource")
                .should().dependOnClassesThat(exposingPublicMethodNamed("ask"))
                .because("a command endpoint may call only an execute use case; calling ask from it would "
                        + "let a write request depend on a read");
```

Four points about these rules, each of which the build can check.

- **The discriminator is the method.** `exposingPublicMethodNamed` reads the one public method that
  each use case already pins. A class named `GetSubmissionUseCase` that exposes `ask` is a read use
  case, and a class named `FindSomethingUseCase` that exposes `execute` is a write use case. The name
  is a hint for a reader and is never a condition the build checks.
- **`use_cases_expose_exactly_one_public_method_named_ask_or_execute` discriminates the other way
  round.** It fails a use case that exposes neither name, or both, or the right name with the wrong
  parameter. Without it, a class with no `ask` and no `execute` would pass both read and write rules
  vacuously.
- **The target predicate is where `.or()` belongs.** In the read and write rules the `.or()` chains
  sit inside `dependOnClassesThat()`, where they select the forbidden targets. The source is selected
  once, by `that(...)` and `.and(...)`. Nothing in the source selection uses `.or()`, so the prefix
  trap cannot arise.
- **`failOnEmptyShould` is `true`.** Each rule matches at least one class today:
  `SearchSubmissionsUseCase` for the read rule, `RegisterSubmissionUseCase` for the write rule,
  `SubmissionQueryResource` and `SubmissionCommandResource` for the controller rules, and
  `Submission` for the aggregate rule. `no_query_package_exists` matches every class in the tree.
  Each must be broken on purpose once, as `AGENTS.md` requires of any structural gate.

**Stated uncertainty.** The predicate-taking form `dependOnClassesThat(DescribedPredicate)` and the
`JavaMethod.getRawParameterTypes()` accessor are the two ArchUnit API calls here I have not compiled
against ArchUnit 1.5.1 in this session. They are the first things `@test-designer` should confirm, and
if either is absent the fallback is an `ArchCondition` on the source class that walks its
`getDirectDependenciesFromSelf()`.

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
| A number is unique within an organisation (BR-06, BR-09) | no — needs the store | the domain and its port |
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

public interface Permission {

    Result<AuthenticatedCaller, AccessRefusal> check(Optional<AuthenticatedCaller> caller);

    /** Search: any identified caller. No role is consulted (BR-16). Cannot refuse on role. */
    static Permission anyIdentifiedCaller();

    /** Register: exactly these roles, and nobody else. Fail-closed: a new role is not in the set. */
    static Permission rolesOnly(ActorRole first, ActorRole... others);
}

public final class OperationGate<C, R> {

    /** Public only so adapters/config can build it; ArchUnit forbids anyone else (§16.4). */
    public OperationGate(Permission permission, BiFunction<ActorContext, C, R> operation);

    public Result<Admission<C, R>, AccessRefusal> admit(Optional<AuthenticatedCaller> caller);
}

public final class Admission<C, R> {                  // no public constructor: only OperationGate mints one

    /** Runs the guarded operation for exactly the actor that was admitted. */
    public R run(C input);
}
```

**Two type parameters, as the product owner decided.** The gate's refusal type is `AccessRefusal`,
which both operations share. The previous revision carried a third type parameter so that search's
refusal type could be `CallerNotIdentified` alone, and the search `switch` could be proven to have one
case by the type. That cost was accepted by the product owner: search's `switch` has two arms, and the second is proven
unreachable by a test ([§5.2](#52-refusals-search-still-has-exactly-one)).

`Permission` is no longer generic, because nothing needs the refusal type to vary per permission. Each
permission returns `AccessRefusal`, and `anyIdentifiedCaller` returns only `CallerNotIdentified` in
practice. Nothing in the type system stops `anyIdentifiedCaller` from returning `PermissionDenied`,
which is why the contract test asserts it does not.

Checked, not asserted: this shape was compiled with `javac` 25 in a scratch directory in the previous
revision. The two-parameter form differs only by removing one type argument, and `@test-designer` should
compile it before relying on it.

- **Permissions are data, declared once.** The two `Permission` values are constants in
  `SubmissionConfiguration` ([§13](#13-wiring)). The evaluation logic lives exactly once, inside the two
  implementations, so there is no `if (role == ...)` in a controller, a use case or anywhere else.
  `rolesOnly` takes `first` plus `others`, so an empty set, a permission that silently denies everyone,
  cannot be written.
- **`Admission` is the proof, and the only door.** The controller never holds a number to the use
  case. It holds a gate; the gate gives it an `Admission` or a refusal; the `Admission` runs the
  operation. `run` supplies the *admitted* actor, so the adapter cannot substitute a different one.
- **No permission is evaluated inside a use case.** A use case is handed an `ActorContext` and uses its
  tenant and identity. It has nothing to re-check, because it is never reached by a refused caller.

---

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

The fifth row is the honest limit of a type-based design. `SubmissionNumber.parse` and
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
  `..adapters.config..` depends on a class in `..domain..` whose simple name ends in
  `UseCase`. *Because a second inbound path that injects a raw use case skips the permission check;
  the only handle it can be given is an `Admission`.* This is the rule that pins the bypass. To
  break it on purpose: inject `RegisterSubmissionUseCase` into `SubmissionCommandResource` and
  watch the build fail.
- **A2 — `gates_are_constructed_only_by_wiring`** — no class outside `..adapters.config..` calls an
  `OperationGate` constructor. *Because permissions are declared in one place; a gate built anywhere
  else is a permission nobody reviews.*
- **A3 — `admissions_are_minted_only_by_the_gate`** — no class other than `OperationGate` calls an
  `Admission` constructor. *Because an `Admission` is evidence that the permission was checked.*

Because the raw use case is also not a Spring bean ([§13](#13-wiring)), there is no bean for a
careless `@Autowired` to find. The domain's blindness is pinned by a rule that already exists:
`write_and_read_models_stay_pure_java` forbids `domain/` from touching anything in
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
`RegisterSubmissionUseCase` takes one, and nothing in `domain/` can name it.

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
  query. It cannot vary with the body, the number, this organisation's data or any other
  organisation's. It therefore cannot confirm that a record exists, in this tenant or another.
- It is a different case from the one the "`NotFound`, never `Forbidden`" rule protects, which is a
  lookup *by id* of a record that belongs to someone else. This feature has no lookup by id. When
  one arrives, another tenant's id answers `NotFound`, decided in the query side *after* the
  permission and never merged with it.
- Its body contains only the caller's own role.
- The order is part of the protection. Because the permission is judged before parsing and before
  the store, a Viewer — genuine or with stolen credentials — cannot use registration to probe the
  validation rules or, through a `409`, which numbers an organisation already holds. A refusal
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
   valid number and a **poisoned `BigDecimal`** — a subclass whose every method fails the test —
   and must receive `403` with the poison untouched. `BigDecimal` is not final and `String` is, so
   the poison goes in the amount. Its control: an Underwriter sending the same poisoned request
   *does* touch it, so the first case cannot pass vacuously. Around it: a Viewer with a control
   character in the number is told `403`, not `400`; the same Underwriter is told `400
   number-contains-control-character`; an empty caller with an invalid body is told `401`; a valid
   Underwriter request is `201` and the fake received exactly the admitted actor. For search: an
   empty caller with an unmatchable filter is `401` and the repository fake is untouched; a Viewer
   with a control character in the number filter is `200` with an empty page and the repository
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
- **Two type parameters, and a weaker search proof.** `OperationGate<C, R>` shares `AccessRefusal` between both operations, so search's `switch` has a second arm that is proven unreachable by a test rather than by the type ([§5.2](#52-refusals-search-still-has-exactly-one)).
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
   registration to learn the validation rules, and a `409` would tell them which numbers the
   organisation already holds.
3. **It contradicts the spec's order of judgement** — caller, role, number, amount, history — and
   a test that already pins it: a Viewer sending a control character is told about the role, not
   about the number.
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
| `shared.kernel.ActorRole` | **moved to `submission.adapters.access`**; `canDecide()` deleted |
| `shared.kernel.ControlCharacters` | **stays** (§15.5) |
| `shared.kernel.LogSafeText` | delegates the category test to `ControlCharacters` |
| `submission.domain.SubmissionNumber` | **new** value object; `parse` returns `SubmissionNumberError`; `MAX_LENGTH` |
| `submission.domain.SubmissionNumberError` | **new** sealed interface, three variants, shared by both sides |
| `submission.domain.InsuredLimit` | **new** value object |
| `submission.domain.RegisterSubmissionCommand` | raw components replaced by the two value objects; `parse` maps `SubmissionNumberError` to the three number variants |
| `submission.domain.RegisterSubmissionError` | loses `CallerNotIdentified` and `RoleMayNotRegister` (to `adapters.access`): nine variants become seven |
| `submission.domain.RegisterSubmissionUseCase` | `execute(ActorContext, RegisterSubmissionCommand)`; returns `Result<SubmissionId, NumberAlreadyUsed>` |
| `submission.domain.Submission` | `register` is total; validation helpers deleted; value-object fields; `reconstitute` returns `Optional` and re-parses; no `ActorRole` |
| `submission.domain.SearchSubmissionsQuery` | **moved from `query/`**; five raw filters replaced by one `SubmissionFilter`; page normalised |
| `submission.domain.SubmissionFilter` | **moved from `query/`**; `parse` folds `SubmissionNumberError` (§5.1) |
| `submission.domain.SearchSubmissionsUseCase` | **moved from `query/`**; `ask(ActorContext, SearchSubmissionsQuery)` returns `SubmissionPageView`; a `switch` over the filter |
| `submission.domain.SubmissionSearchCriteria` | **moved from `query/`**; `number` is an `SubmissionNumber` |
| `submission.domain.SubmissionSummaryView` | **moved from `query/`**; `SubmissionId` in place of `UUID`; the number and amount stay `String` and `BigDecimal` (§5.3) |
| `submission.domain.SubmissionPageView` | **moved from `query/`** |
| `submission.domain.SubmissionQueryRepository` | **moved from `query/`** |
| `submission.query.NumberCriterion` | **deleted** |
| `submission.query.SearchSubmissionsError` | **deleted** |
| `submission.query.package-info.java` | **deleted** |
| `submission.adapters.access.*` | **new:** `ActorRole` (moved), `AuthenticatedCaller`, `AccessRefusal`, `CallerNotIdentified`, `PermissionDenied`, `Permission` (non-generic), `OperationGate<C, R>`, `Admission<C, R>` |
| `submission.adapters.web.ActorContextResolver` | yields `Optional<AuthenticatedCaller>` |
| `submission.adapters.web.RegisterSubmissionRequest` | gains `toCommand()` |
| `submission.adapters.web.RegisterRefusal` | **new** union |
| `submission.adapters.web.SubmissionCommandResource` | the five-step flow; a nine-case flat `switch`; no `ActorContext.callWith` |
| `submission.adapters.web.SubmissionQueryResource` | the flow in §13.3; a two-arm `switch`; imports from `domain/` |
| `submission.adapters.persistence.SubmissionQuerySqlAdapter` | imports from `domain/`; unwraps `number.value()`; copies stored values into views without parsing (§9.4) |
| `submission.adapters.persistence.SubmissionPersistenceMapper` | handles `Optional` from `reconstitute` |
| `submission.adapters.config.SubmissionConfiguration` | builds the two gates; declares the permission table; raw use cases are no longer beans |
| migrations, SQL | none |

### 18.2 Tests

Counted from the tree in the previous revision. Recount at implementation: the counts are the design's
best figure, not a build result.

- **Moved from `query/` to `domain/`:** `SearchSubmissionsUseCaseTest`, `SubmissionFilterTest`,
  `SubmissionQueryRecordsTest`, `SubmissionSearchAdversarialTest`, and `InMemorySubmissionQueryRepository`
  (a test fake, which is `domain/`'s concern and not an adapter's).
- **`new ActorContext(...)`: 18 sites in 6 files** become fixture calls built on `parse`; the
  `ActorRole` import changes package in every file that uses it.
- **`new RegisterSubmissionCommand(...)`: 10 sites in 4 files** become `RegisterSubmissionCommand.parse`
  or value-object fixtures.
- **`new SearchSubmissionsQuery(...)`: 18 sites in 4 files** build their filter through
  `SubmissionFilter.parse`.
- **`NumberCriterion` and `SearchSubmissionsError` appear in the query tests**; those assertions move
  to `SubmissionFilterTest` and to the gate tests.
- **`CallerNotIdentified` is asserted in 4 files**; those assertions move to the gate tests.
- **The role tests** (`RegisterSubmissionUseCaseTest`'s role group and its viewer-before-control-character
  case) can no longer be written against the use case, which is the point. They move to
  `PermissionMatrixTest`, `OperationGateTest` and the edge order tests.
- **The validation-order tests** move to `SubmissionNumberTest`, `InsuredLimitTest` and
  `RegisterSubmissionCommandParseTest`: pure, with no use case and no fake.
- **`ActorContextTest`:** the `canDecide` and role assertions leave, the parse tests arrive, the
  scoped-value tests stay (R5).
- **`NumberLogInjectionTest`:** builds raw commands and queries today; keep it for the raw holders
  (snapshot, event, error, views).
- **`ArchitectureRulesTest`:** see §18.4.
- **Integration tests** that build commands through the register use case go through `parse`.
- **Every file that imports `com.gs5.underwriting.submission.query.*`** is rewritten to import from
  `domain`. The count is taken at implementation with `grep -rl "submission.query" src`.
- **New:** gate, parse, edge-order and catalogue tests, `ControlCharactersTest`, `SubmissionFilterTest`
  (now including the BR-30 and BR-31 cases), the two width ITs, and the CQS rules in §15.10.

### 18.3 Invariants

| Invariant | Before | After |
|---|---|---|
| BR-01 caller identified | each use case, `fromOptional(ActorContext.current())` | `OperationGate.admit` |
| BR-02, BR-03 role may register | `Submission.register` | `Permission.rolesOnly`, declared in config |
| BR-16 every role may search | the search use case never read a role | `Permission.anyIdentifiedCaller`; no role consulted |
| BR-04, BR-05, BR-29 | `Submission.register` | `SubmissionNumber.parse`, mapped into the register error |
| BR-10, BR-11, BR-12 | `Submission.register` | `InsuredLimit.parse` |
| BR-30 | the filter reached the store and answered `500` | `SubmissionFilter.parse` maps `ControlCharacter` to `MatchesNothing` |
| BR-31 | not specified in code | `SubmissionFilter.parse` maps `Missing` to not supplied |
| BR-32 | not specified in code | `SubmissionFilter.parse` maps `TooLong` to `MatchesNothing` |
| BR-06, BR-09 | the store, via a unique index | unchanged |
| BR-15 ownership | the aggregate | unchanged |
| BR-17 tenant isolation | the use case passes `actor.tenantId()` | unchanged |
| BR-22, BR-23 masking | per-record `toString` | per-record, and value objects mask themselves |
| a caller's identity is well-formed | nothing | `ActorContext.parse`, `AuthenticatedCaller.parse` |
| read and write are separated | by the `query/` package | by the CQS ArchUnit rules (§15.10) |

### 18.4 Documents and rules outside this design

Every place that prescribes the `query/` package, or the read side as a package, found by searching
`.github/`, `AGENTS.md`, `docs/specs/` and `src/test/` for the word `query`. Each line is listed with
what it says now and what it must say.

| File | Line(s) | Says now | Must say |
|---|---|---|---|
| `src/test/.../ArchitectureRulesTest.java` | 45 | comment: `query/` reading a constant in `domain/` is invisible | comment about `domain/` alone |
| same | 53, 65 | `write_and_read_models_stay_pure_java` covers `..domain..` and `..query..` | covers `..domain..` only |
| same | 85–94 | `read_model_does_not_see_write_model`, `write_model_does_not_see_read_model` | **delete both**; replaced by §15.10 |
| same | 102–104 | comment about `query/` and `adapters/web/` | delete the stale comment |
| same | 112–120 | `command_controllers_touch_only_the_write_side`, `query_controllers_touch_only_the_read_side`, on `..query..` | **replace** with `query_controllers_depend_on_no_write_use_case` and `command_controllers_depend_on_no_read_use_case` (§15.10) |
| same | 157–176 | `use_cases_have_exactly_one_public_method_named_for_their_side`, which infers `ask` from a package ending in `.query` | **replace** with `use_cases_expose_exactly_one_public_method_named_ask_or_execute` (§15.10) |
| same | 126 | `commands_and_queries_are_standalone_records` | unchanged |
| `.github/copilot-instructions.md` | 50 | bans Spring, Jackson and Kafka imports in `domain/` or `query/` | `domain/` |
| same | 65 | layout tree: `query/` READ side | remove; note that read use cases are in `domain/` |
| same | 69 | `domain/` and `query/` may depend only on `java.*`, kernel, jspecify | `domain/` only |
| same | 84 | read side in `query/`: `SearchSubmissionsUseCase.ask(...)` | read side in `domain/`, distinguished by `ask` |
| `.github/instructions/domain-cqrs-purity.instructions.md` | 2–3 | description and `applyTo: "**/{domain,query}/**/*.java"` | `applyTo: "**/domain/**/*.java"`; description without `query/` |
| same | 95–101 | section "`query/` is a separate world": `query/` cannot import `domain/`, a `*UseCase` validates and returns a projection | **delete the section**; replace with the CQS rule: discriminated by `ask`/`execute` |
| same | 108 | `*UseCase` class with one public method, `execute` in `domain/`, `ask` in `query/` | `execute` for a write, `ask` for a read, both in `domain/` |
| `.github/instructions/java25-functional-style.instructions.md` | 56 | side effects in adapters, not in `domain/` or `query/` | `domain/` |
| `.github/instructions/testing-tdd.instructions.md` | 19 | no Mockito for `domain/` or `query/` | `domain/` |
| `.github/instructions/security-owasp-java.instructions.md` | 84 | adapter tokens never leak into domain or query interfaces | `domain/` |
| `.github/skills/hexagonal-cqrs-slice/SKILL.md` | 3 | description: "a separate query package" | remove the phrase |
| same | 29–31 | layout tree: `query/` READ SIDE, `<Query>UseCase.java` | read use cases in `domain/` |
| same | 188–189 | checklist: `domain/` and `query/` import nothing from Spring; `query/` does not import `domain/` | `domain/` imports nothing from Spring; a read use case does not depend on the write side (§15.10) |
| `.github/skills/spec-to-plan/SKILL.md` | 50, 77 | "its own `*UseCase` class with a single public `ask(Query)`" | still true; add "discriminated by `ask`/`execute`, not by package" |
| `.github/agents/architect.agent.md` | 31–32 | `domain/` and `query/` depend only on…; `query/` does not import `domain/` | `domain/` depends only on…; read and write in `domain/` obey §15.10 |
| `.github/agents/code-reviewer.agent.md` | 19 | `[BLOCKER]` imports between `domain/` and `query/` | `[BLOCKER]` a read use case depending on the write side, or the reverse (§15.10) |
| `.github/agents/developer.agent.md` | 49 | no `@Service` or `@Component` in `domain/` or `query/` | `domain/` |
| `.github/agents/test-designer.agent.md` | 16–17 | allowed in `src/main/java/**/{domain,query}/`: `*Command`, `*Query`, `*View`, `*Snapshot`, `*Id` | `src/main/java/**/domain/` |
| same | 38 | "every command, every error variant and every query" | unchanged; the word is a concept |
| same | 55 | fakes for ports in `domain/` and `query/` | `domain/` |
| `AGENTS.md` | 45 | layout tree: `<module>/ domain/ · query/ · adapters/` | `domain/ · adapters/` |
| same | 53 | "`query/` is the read side returning projection records and cannot see `domain/`" | "read use cases live in `domain/` and are distinguished from write use cases by `ask` and `execute`" |
| same | 66 | `@test-designer` stubs in `domain/`+`query/` | `domain/` |
| `.github/copilot-instructions.md` and `AGENTS.md` | "eight agents" | unchanged | unchanged |

**Not affected:** `.github/agents/business-analyst.agent.md` ("Query catalogue") and the specification
`docs/specs/submission-registration-spec.md` ("Query 1 — Search Submissions") use the word as a business
term. `security-owasp-java.instructions.md` line 17 and the Spring Data `@Query` annotation are
unrelated. `docs/workflow/` was not in the search above and is to be checked at the next orchestrator
update.

---

## 19. Approval

Approval is the second write operation of this module, and this section is its design. It was
specified on 2026-11-03 by a product-owner decision that reverses the earlier statement that
approval is out of scope. Its business rules are `BR-33` to `BR-43` and its refusal list is exactly
five reasons. The implementation exists at commit `5bf01b3`, and the specification at `2ef27b9`.
Where the implementation differs from this section, the difference is listed in
[§19.11](#1911-where-the-implementation-diverges-required-changes) as a change the developer must
make. Nothing in this section is written against the implementation's behaviour.

It follows the revision 4 model without exception. Approval is a write use case in `domain/`, with
one public method, `execute(ActorContext, ApproveSubmissionCommand)`. The access gate decides who may
call it before any input is parsed. The submission number is parsed at the edge with the same parse-don't-validate rule as every
other input, and the internal identity is never part of the request.

### 19.1 The order at the edge

```
POST /api/submissions/{number}/approve     {number} is a String. The framework does not convert it.
  1. caller    = actorContextResolver.resolve()               Optional<AuthenticatedCaller>
  2. admission = approvalGate.admit(caller)                   401 (BR-01) or 403 (BR-33) end here
  3. command   = ApproveSubmissionCommand.parse(number)       a text that cannot be a submission number
                                                              ends here as 404 (BR-35)
  4. result    = admission.run(command)                       404, 403 or 409, or 200
```

Three properties of this order are the point, and each is checked by a test in [§19.12](#1912-tests):

- **A malformed number cannot produce a framework `400`.** The path variable is declared `String`, so
  Spring performs no conversion. A Viewer who names an empty or control-character number receives `403`,
  because step 3 runs only after step 2 has admitted the caller.
- **Nothing about the submission is revealed before the role is judged.** Step 2 consults the caller and
  the operation alone. The submission is not looked at until step 4 (BR-33, BR-34).
- **The internal identity never leaves the service.** The path carries the submission number, and the
  response, the problem details and the events carry it too. The UUID is the key of the row the use case
  finds, and no type of this operation exposes it (spec, glossary: *Submission Number*).

### 19.2 The command and its value objects

```java
/** Canonical UUID text, for the caller's tenant claim only. Shared with ActorContext. */
public final class CanonicalUuid {                              // shared/kernel
    public static Optional<UUID> parse(@Nullable String text);
}

/** The submission number is a value object of the submission module (§15.2), parsed as at registration. */
public final class SubmissionNumber { /* §15.2 */ }

public record ApproveSubmissionCommand(SubmissionNumber submissionNumber) {
    /**
     * Any text that is not a submission number names no submission, so every parse failure becomes
     * the one refusal an unknown number gets (BR-35). The three causes stay indistinguishable.
     */
    public static Result<ApproveSubmissionCommand, ApproveSubmissionError> parse(String rawNumber);
}
```

- **`CanonicalUuid` is no longer used for the submission.** It stays for the tenant claim in
  `ActorContext.parse`, where the format is a business requirement of the identity source.
- **The submission number is parsed by the rule registration uses** (BR-04, BR-05, BR-29), through
  `SubmissionNumber.parse`. A number that could never have been registered, for example one containing a
  TAB, is refused as not found. No UUID parse, and no UUID library, is needed for this operation. The
  question of lenient UUID text (`UUID.fromString("1-1-1-1-1")`) does not arise, because no UUID is read.
- **The command has one field.** The approving caller, the moment and the organisation come from the
  admitted actor and the clock (BR-37). The command cannot carry them, so a caller cannot supply them.
- **The use case finds the submission by its number within the caller's organisation**
  (`SubmissionCommandRepository.findOwnByNumber`). The row's internal id is then used for the approval
  record and never returned.

### 19.3 The refusal types

```java
/** Business refusals, produced by the domain. None carries a value, so none can carry the identity (BR-43). */
public sealed interface ApproveSubmissionError {
    record SubmissionNotFound() implements ApproveSubmissionError { }              // BR-35
    record ApproverRegisteredSubmission() implements ApproveSubmissionError { }   // BR-41
    record SubmissionAlreadyApproved() implements ApproveSubmissionError { }      // BR-36, BR-39
}

// adapters/access — shared with registration, unchanged
public sealed interface AccessRefusal permits CallerNotIdentified, PermissionDenied { }

// adapters/web — the caller-facing union, as RegisterRefusal is for registration
sealed interface ApproveRefusal {
    record Access(AccessRefusal refusal) implements ApproveRefusal { }
    record Rejected(ApproveSubmissionError error) implements ApproveRefusal { }
}
```

The five spec reasons map as follows. The layer column records which part of the design raises each
one, because the spec's list is the caller's contract and does not name the layer.

| # | Spec refusal | Layer | Produced by | Rule | HTTP | Problem type |
|---|---|---|---|---|---|---|
| 1 | Caller not identified | adapter | `ActorContextResolver` empty, so `OperationGate.admit` | BR-01 | 401 | `caller-not-identified` |
| 2 | Role may not approve | adapter | `Permission.approval`, as `PermissionDenied(role)` | BR-33 | 403 | `permission-denied` |
| 3 | Submission not found | domain | `ApproveSubmissionCommand.parse` for a text that is not a number, and the use case for an unknown or other-organisation one | BR-35 | 404 | `submission-not-found` |
| 4 | Approver registered this submission | domain | `Submission.approvableBy`, inside the use case | BR-41 | 403 | `approver-registered-submission` |
| 5 | Submission already approved | domain | the approval insert's conflict, inside the use case | BR-36, BR-39 | 409 | `submission-already-approved` |

**Three rules hold across the table, and each is tested.**

- **Cases 3 and 4 are never distinguishable from the caller's side in a way that leaks.** A malformed
  identity, an unknown one and another organisation's one all produce the same `SubmissionNotFound()`,
  with the same status, the same problem type and the same text. No variant carries the identity, so the
  malformed case cannot echo its raw input.
- **Case 4 is `403`, not `404`.** The caller is known and admitted, and has been found to be the
  registrant, so the refusal concerns the caller's own relation to a submission they can already see. A
  `404` would be false: they can see it, since search shows it to them. The spec names no status, so
  the choice is recorded as an engineering decision, and [§19.13](#1913-questions-for-the-product-owner)
  asks the product owner to confirm it.
- **Case 5 is `409`,** as the registration duplicate is. The text is constant and does not name the
  submission, because the spec's refusal states nothing about the submission and BR-43 reveals no more
  than the reason.

**`ApproveRefusal` is the one place the five are folded,** in a single flat `switch` with no `default`,
over the nested record patterns `Access(...)` and `Rejected(...)`. A sixth reason added to either
hierarchy fails to compile at that switch, which is the property the registration fold already has. The
implementation folds the domain errors and the access refusals in two separate switches, and that is
listed as a change.

### 19.4 The check order, and which part enforces each step

| Step | Judged | Enforced by | Refusal, if it fails |
|---|---|---|---|
| 1 | caller identified | `OperationGate.admit`, through the resolver | `CallerNotIdentified` |
| 2 | role permits approving | `OperationGate.admit`, through `Permission.approval` | `PermissionDenied` |
| 3 | the number is in a valid form | `ApproveSubmissionCommand.parse` | `SubmissionNotFound` |
| 4 | submission exists in the caller's own organisation | the use case: `findOwnByNumber(tenantId, number)` | `SubmissionNotFound` |
| 5 | caller did not register it | the use case: `Submission.approvableBy(actor)` | `ApproverRegisteredSubmission` |
| 6 | not already approved | the use case: the approval insert | `SubmissionAlreadyApproved` |

Steps 1 and 2 are the gate's, and steps 3 to 6 are the domain's. No layer repeats another's check.
The use case does not read the role, because it has none to read, and the gate does not read the
registrant, because the gate does not see the submission.

**Why the self-approval check comes before the already-approved check (BR-41, BR-34).** Step 5
depends only on the caller and the registration, and step 6 depends on the approval store. A caller
who may never approve this submission is therefore refused the same way whether or not somebody has
already approved it. The answer to "may this caller approve this?" does not change with the store's
state, and that is the sense in which the spec says the self-check "depends only on who the caller is
and who registered the submission". The reverse order would make the registrant's refusal depend on
whether another approval had happened, which is the state the refusal is supposed to keep separate
from the identity.

**The use case, as it should read.**

```java
public Result<SubmissionId, ApproveSubmissionError> execute(ActorContext actor,
                                                            ApproveSubmissionCommand command) {
    var approvedAt = clock.instant();
    return Result.fromOptional(
                    commandRepository.findOwnByNumber(actor.tenantId(), command.submissionNumber())
                            .flatMap(Submission::reconstitute),         // Optional — §4.2, see note
                    ApproveSubmissionError.SubmissionNotFound::new)
            .flatMap(submission -> submission.approvableBy(actor))       // step 5: BR-41
            .flatMap(submission -> approvalRepository
                    .approve(new SubmissionApproval(submission.id(), actor.tenantId(),
                            actor.actorId(), approvedAt))                // step 6: BR-36, BR-39
                    .<ApproveSubmissionError>mapError(error -> error))
            .peek(id -> eventPublisher.publish(new SubmissionDomainEvent.SubmissionApproved(
                    idGenerator.nextEventId(), id, actor.tenantId(), actor.actorId(), approvedAt)));
}
```

Four points about it:

- **There is no `isApproved` read.** The implementation checks `isApproved` and then inserts. That
  is a read followed by a write, which races, and the insert's conflict already settles the race. The
  check before the insert is therefore a second path to keep correct and adds nothing. Step 6 is the
  insert alone, and step 5 precedes it, so the spec's order is preserved. The implementation's
  `isApproved` method is removed ([§19.11](#1911-where-the-implementation-diverges-required-changes)).
- **`approvableBy` is on the aggregate.** The self-check compares a fact the aggregate holds, its
  `registeredBy`, with the caller. That is a rule over data, so it stays in the domain (BR-41), and it
  is a method on `Submission`, the one place a rule over a submission's own fields belongs.
- **The event id comes from the id port,** the same port registration uses, so a test pins it. The
  implementation calls `UUID.randomUUID()` inside the use case, which is a second source of randomness
  in `domain/` ([§19.11](#1911-where-the-implementation-diverges-required-changes)).
- **`reconstitute` returns `Optional`,** as §4.2 states. An empty result is mapped to `SubmissionNotFound`
  here, and that is one place where a storage invariant failure becomes a refusal. The same schema
  argument as [§9.4](#94-query-adapter) makes the branch unreachable for every row the database can hold,
  so it is recorded as a decision, not a gap ([§19.13](#1913-questions-for-the-product-owner), Q14).

### 19.5 The approval state

**Decision: approval is a fact recorded beside the submission, not a column changed on it.**

- The aggregate has **no state field**. `Submission` keeps the registered facts exactly as they were
  written, and BR-38 holds by construction: nothing in this section updates the `submissions` row.
- The state is a derived value, `SubmissionState { REGISTERED, APPROVED }`, which is **`APPROVED` if and
  only if a row exists in `submission_approvals`** for the submission. The spec names exactly these two
  values ("No other state exists"), so the enum has exactly two constants.
- The table has an `INSERT` path and no `UPDATE` or `DELETE` path, so the approval, once recorded, cannot
  change. That is BR-36, and it is enforced by the absence of a port method that could express anything
  else ([§19.10](#1910-architecture-rules-for-approval), rule A).

**Why not a column on `submissions`.** A column would make approval an `UPDATE` on the row whose
registered facts BR-38 says must not change. The row would then have two kinds of write, which is the
shape of mistake the immutable registered row exists to prevent. The separate row gives the state its
own write path, and the cost is a join in search, which is cheap on the primary key
([§19.9](#199-the-search-change)).

**The transition.** `REGISTERED → APPROVED`, once, and nothing else. There is no return to
`REGISTERED`, no second approval, and no state beyond the two. The transition is enforced in two
places that agree: the table's primary key, which settles the race (§19.6), and the absence of any
un-approve operation.

**The approval record.** A domain record, not an aggregate, because the approval has no behaviour of its
own:

```java
public record SubmissionApproval(SubmissionId submissionId, UUID tenantId,
                                 String approvedBy, Instant approvedAt) {
    /** BR-42 and OPEN-01: the approver is masked in a log; the record still carries it for the store. */
    @Override public String toString();   // approvedBy=***
}
```

It holds a `String` `approvedBy` and not an `ActorContext`, because the store needs the text and must
not depend on the caller's type.

### 19.6 The race (BR-39) and how the store settles it

```sql
INSERT INTO submission_approvals (submission_id, tenant_id, approved_by, approved_at, version)
VALUES (:submissionId, :tenantId, :approvedBy, :approvedAt, 0)
ON CONFLICT (submission_id) DO NOTHING
```

The adapter returns `Result.success(id)` when one row was inserted, and
`Result.failure(SubmissionAlreadyApproved())` when none was.

Two approvals of one submission race as follows. Both pass the self-check, since the check reads only
the registration. Both reach the insert. The primary key admits exactly one, which gets a row count of
`1` and is recorded. The other gets `0` and is refused as `SubmissionAlreadyApproved` (BR-39). Both are
never recorded, because the primary key is on `submission_id` alone.

- **The event is published for the winner only.** `peek` runs on the success branch, so the loser
  announces nothing (BR-40).
- **The conflict is a row count, not an exception.** This is the same idiom as registration's duplicate
  (§9.1), and for the same reason: a caught `DuplicateKeyException` inside a transaction can abort it.
  The approval is one statement, so no transaction is open to abort, but the idiom is kept so that the
  two write paths read the same.
- **The primary key also makes the approval once-only across tenants,** which is correct: a submission
  id belongs to one tenant, and the composite foreign key in [§19.8](#198-schema-v3) makes that
  consistent.

### 19.7 The event `SubmissionApproved` (BR-40)

```java
record SubmissionApproved(UUID eventId, SubmissionNumber submissionNumber, UUID tenantId,
                          String approvedBy, Instant occurredAt) implements SubmissionDomainEvent {
    @Override public String toString();   // approvedBy=***; no insured limit exists in this record
}
```

- **Carries what BR-40 and §5 name:** the organisation, the submission number, the approving caller's
  identity and the moment. **It does not carry the insured limit,** because the spec states that the
  limit "is not carried" by this event, and the record has no field for it.
- **Raised only on success,** through `peek` (§19.6).
- **Identity from the id port:** `idGenerator.nextEventId()`, as for registration. The implementation's
  `UUID.randomUUID()` is listed in [§19.11](#1911-where-the-implementation-diverges-required-changes).
- **Partition key, if a transport is chosen later:** the tenant and the submission number, never the internal identity (BR-47), for `SubmissionRegistered` too
  ([§12.1](#121-deferred-adapter-decision-how-the-event-is-published)). The transport is still deferred.
- **Masking (BR-42).** `toString` prints `approvedBy=***`. The accessors still carry the value, because a
  recipient needs it (BR-42 is a rule about logs, as BR-22 is).

### 19.8 Schema: V3

`V2__create_submissions.sql` is unchanged by this section. This is the approval migration as the target state. Since revision 6 it is reached by V4 ([§20.4](#204-the-migration-v4)), and V3 is restored to its reviewed content, so the three faults below are corrected in V4, not in V3 under the storage-fit rules of [§15.8](#158-storage-fit-in-one-table).

```sql
-- V3: approval. Edited in place only if V3 has never been applied to any database. Otherwise V4.
ALTER TABLE submissions
    ADD CONSTRAINT uq_submissions_id_tenant UNIQUE (id, tenant_id);   -- a foreign-key target must be unique

CREATE TABLE submission_approvals
(
    submission_id UUID PRIMARY KEY,                       -- one approval per submission (BR-36, BR-39)
    tenant_id     UUID         NOT NULL,
    approved_by   VARCHAR(255) NOT NULL,                  -- an ActorContext.actorId: ≤255 code points, no Cc
    approved_at   TIMESTAMPTZ  NOT NULL,                  -- the clock, never the caller (BR-37)
    version       BIGINT       NOT NULL,                  -- as in V1 and V2: the mapper must not drop it

    CONSTRAINT fk_submission_approvals_submission         -- the approval's tenant IS the submission's tenant
        FOREIGN KEY (submission_id, tenant_id)
        REFERENCES submissions (id, tenant_id)
);
```

**Reviewed against the storage-fit rules of §15.8, and the three faults this corrects:**

- **The committed foreign key was single-column** (`FOREIGN KEY (submission_id)`). It proved the
  submission exists but not that the approval's `tenant_id` matches the submission's. The application
  checks the tenant first, so the state is correct today, but the database would accept a mismatched
  row from any future write path. The composite key makes the rule a constraint rather than a
  convention. Its target needs a unique constraint on `(id, tenant_id)`, which `submissions` does not
  have, so the migration adds one. This is the reason for the `ALTER TABLE` at the top.
- **The committed foreign key cascaded on delete** (`ON DELETE CASCADE`). A submission is never deleted
  in this feature (the scope excludes it), so cascade is a deletion path that nobody should use. The
  default `NO ACTION` is kept: a submission with an approval cannot be deleted, which is the behaviour the
  spec's immutability implies.
- **The committed unique index on `(tenant_id, submission_id)` is redundant.** `submission_id` is the
  primary key, so every uniqueness fact it could state is already stated. It is removed.

The remaining columns fit the value objects exactly. `approved_by` is the one `actorId`, bounded at 255
code points by the same parse that bounds `registered_by`, and free of control characters for the same
reason. `approved_at` is an `Instant` from the clock. `submission_id` is a canonical UUID, and the
primary key is the one-approval rule.

### 19.9 The search change

**Decision: the summary read model carries the state, and the search query fills it.** BR-17 and the
spec's section 4 state that each returned submission shows its state, visible only within the caller's
organisation.

```java
public record SubmissionSummaryView(String submissionNumber,
                                    BigDecimal insuredLimit,
                                    String registeredBy,
                                    Instant registeredAt,
                                    SubmissionState state) { /* toString as before; state is not masked */ }

public enum SubmissionState { REGISTERED, APPROVED }
```

`state` is not sensitive, so `toString` prints it. The insured limit and the caller stay masked.

The `findPage` statement gains a `LEFT JOIN`. `countMatching` is **unchanged**, because the spec adds no
state filter, so the count does not depend on state:

```sql
-- Since revision 6 the statement selects the view of [§20.3](#203-the-latest-state-view-the-strategy):
SELECT submission_number, insured_limit, registered_by, registered_at, approved
FROM submission_current
WHERE tenant_id = :tenantId
  -- the same predicates as §6, on the view's columns
ORDER BY registered_at DESC, submission_id DESC
LIMIT :limit OFFSET :offset
```

- **The projection record gains `boolean approved`,** and the adapter maps it to `SubmissionState`. A
  primitive in an adapter projection is correct: the projection is a row, not a domain value.
- **The join is on the primary key of `submission_approvals`,** so it is a single index probe per row. The
  row count stays bounded by `LIMIT`, so the join adds no per-page cost that grows with the table.
- **The tenant predicate appears in the join as well as in `WHERE`.** The composite foreign key makes the
  two equal, so the join's copy is redundant. It is kept because it states the rule where the reader is
  looking, and it costs nothing.
- **Filtering by state is not added.** The spec specifies that state is shown and says nothing about
  filtering by it. Adding a filter would invent a criterion, so it is a question ([§19.13](#1913-questions-for-the-product-owner)).

### 19.10 Architecture rules for approval

These are the rules that apply to approval, in addition to the CQS rules of [§15.10](#1510-cqs-rules-verbatim)
and the access rules of [§16.4](#164-bypass-is-a-build-failure). Each matches at least one class, so
`failOnEmptyShould` holds, and each must be broken on purpose once.

**Rule A: no operation reverses or rewrites a registered or approved fact.**

```java
@ArchTest
static final ArchRule no_operation_reverses_or_rewrites_a_recorded_fact =
        noMethods()
                .that().areDeclaredInClassesThat().resideInAPackage("..domain..")
                .should().haveNameMatching(
                        "(?i).*(unapprove|revoke|withdraw|reverse|cancel|amend|delete|update|remove).*")
                .because("an approval is final (BR-36) and a registered fact never changes (BR-38); "
                        + "an operation that reverses or rewrites either must not exist, because a port "
                        + "method is the only way a write can happen");
```

This pins the immutability as structure. It matches by name, which is a weaker proof than the type, and
the rule says so in its own name: it guards the common ways such a method gets written, and a reviewer
must still read a method that avoids the words.

**Rule B: the approval use case is a write, and depends on no read type.** The general rule
`write_use_cases_do_not_depend_on_the_read_side` already matches `ApproveSubmissionUseCase`, because it
exposes `execute`. This explicit rule names it, so a reader of the rules sees approval covered:

```java
@ArchTest
static final ArchRule approval_use_case_depends_on_no_read_type =
        noClasses()
                .that().haveSimpleName("ApproveSubmissionUseCase")
                .should().dependOnClassesThat()
                .haveSimpleNameEndingWith("QueryRepository")
                .or().haveSimpleNameEndingWith("View")
                .or().haveSimpleNameEndingWith("Query")
                .or().haveSimpleName("SubmissionFilter")
                .or().haveSimpleName("SubmissionSearchCriteria")
                .because("approval is a write (execute): it decides on the aggregate through its own port, "
                        + "and a read type in its dependencies would mean it decides on loaded projections");
```

**Rule C: only the approval use case announces an approval.**

```java
@ArchTest
static final ArchRule only_the_approval_use_case_announces_an_approval =
        noClasses()
                .that().doNotHaveSimpleName("ApproveSubmissionUseCase")
                .should().callConstructor(SubmissionDomainEvent.SubmissionApproved.class)
                .because("BR-40: an approval is announced exactly when an approval succeeds, and the single place "
                        + "that knows it succeeded is the use case, after the insert");
```

**Rule D: the approval permission is built only in configuration,** which is the existing rule
`gates_are_constructed_only_by_wiring` ([§16.4](#164-bypass-is-a-build-failure), A2). Nothing new is
written, but approval is named here so that the rule is seen to cover it.

**Rule E: the approval's raw input is parsed in `domain/`,** which is the existing rule
`raw_input_enters_only_through_parse`. `ApproveSubmissionCommand.parse(String)` and
`SubmissionId.parse(String)` are both named `parse`, so both comply, and `CanonicalUuid.parse` in the
kernel is outside the rule's scope and is the only place the format is decided.

### 19.11 Where the implementation diverges: required changes

> **Revision 5 (submission number).** The path now carries the submission number, not the internal UUID, so D1, D2, D5 and D6 are resolved by the design in §19.1 and §19.2: no UUID is parsed for the submission, `SubmissionNotFound` is fieldless, and the not-found body is constant. The table below is the developer's list against revision 5. The implementation at `5bf01b3` still predates this decision.

Every item is a change the developer makes. The implementation is at `5bf01b3` and HEAD. Each item cites
the design section it must match. Severity follows the review scale in `architect.agent.md`.

| # | Divergence | Where | Required change | Severity |
|---|---|---|---|---|
| D1 | The identity is converted before the gate. `@PathVariable UUID id` makes Spring parse it, and a malformed value is a framework `400`. | `SubmissionApprovalResource.approve` | Declare `@PathVariable String id`. Admit first, then `ApproveSubmissionCommand.parse(id)`, inside the admitted branch. A test pins a Viewer naming `not-a-uuid` to `403` ([§19.12](#1912-tests)). | **BLOCKER** |
| D2 | Spring's `UUID` converter is lenient. `1-1-1-1-1` parses to a real UUID, which may name an existing submission. | the same path conversion | Fixed by D1 together with `CanonicalUuid.parse` ([§19.2](#192-the-command-and-its-value-objects)). Without `CanonicalUuid`, D1 still lets lenient text reach `findOwn`. | **BLOCKER** |
| D3 | The self-approval check is absent. | `ApproveSubmissionUseCase.execute` | Add `Submission.approvableBy(actor)` returning `ApproverRegisteredSubmission` when `registeredBy` equals the caller's identity. Call it after the lookup and before the insert ([§19.4](#194-the-check-order-and-which-part-enforces-each-step)). | **BLOCKER** (BR-41) |
| D4 | `ApproveSubmissionError` has two variants, not three: `ApproverRegisteredSubmission` is missing. | `ApproveSubmissionError.java` | Add the variant, and the mapping to `403` with type `approver-registered-submission`. | CRITICAL |
| D5 | `SubmissionNotFound` carries a `SubmissionId`. A malformed number has none to carry, so the variant cannot represent the spec's case. | `ApproveSubmissionError.java`, the use case, the controller | Make `SubmissionNotFound` fieldless, as in §19.3. The three cases are then indistinguishable by construction. | CRITICAL |
| D6 | The not-found problem echoes the identity: `"Submission " + submissionId.value() + " was not found."`. For a text that is not a number that is the raw path text, which is reflected untrusted input. | `SubmissionApprovalResource.refused` | Use constant text with no echo, for every case (§19.3). | CRITICAL |
| D7 | The event identity is `UUID.randomUUID()` inside the use case, a second source of randomness in `domain/`. | `ApproveSubmissionUseCase.execute` | Take `SubmissionIdGenerator` in the constructor and call `nextEventId()`. | WARNING |
| D8 | The approval state is absent from search. The summary view, its projection, and `findPage` have no state. | `SubmissionSummaryView`, `SubmissionSummaryJdbcProjection`, `SubmissionQueryJdbcRepository` | Add `SubmissionState`, the `approved` projection, the `LEFT JOIN` of [§19.9](#199-the-search-change). `countMatching` is unchanged. Spec §4 requires it. | CRITICAL |
| D9 | V3's foreign key is single-column, cascades on delete, and the unique index is redundant. | `V3__create_submission_approvals.sql` | Replace with [§19.8](#198-schema-v3): composite foreign key, unique `(id, tenant_id)` on `submissions`, no cascade, no redundant index. Verify first that V3 is not applied anywhere. | CRITICAL |
| D10 | The approval refusals are folded in two switches, with no union. | `SubmissionApprovalResource.refused`, `refusedAccess` | Add `ApproveRefusal` and one flat exhaustive switch over it, as registration does (§19.3). | WARNING |
| D11 | `isApproved` is a read before the insert: a second path to keep correct, and it races. | `SubmissionApprovalRepository`, its adapter, the use case | Remove `isApproved`. The insert's conflict is the already-approved answer ([§19.4](#194-the-check-order-and-which-part-enforces-each-step)). | WARNING |
| D12 | `Submission.reconstitute` does not re-parse the stored values, and it returns `Submission`, not `Optional`. §4.2 requires both. | `Submission.reconstitute` | Re-parse with `SubmissionNumber.parse` and `InsuredLimit.parse`, return `Optional<Submission>`, and map empty in the approval use case as [§19.4](#194-the-check-order-and-which-part-enforces-each-step) states. | WARNING |
| D13 | The role rule is `ActorRole.canDecide()`, shared by registration and approval, and the design deleted that method in revision 3. | `ActorRole.java`, `SubmissionConfiguration` | Either keep `canDecide()` and record it as the business role set, which the product owner confirmed for both operations, or replace it with an explicit two-role set in configuration code. The set must not be configurable at runtime, since BR-33 is a business rule. A test pins the set to exactly the two approving roles. | WARNING |
| D14 | The `AccessPolicy` seam is in the implementation and absent from the design. | `AccessPolicy.java` | Accept it as the named form of the permission data, with D13's fixed set behind it. The design's permission table ([§13](#13-wiring)) and the seam must name the same two roles. | WARNING |
| D15 | The tests deleted in `5bf01b3` from registration are not restored, by the product owner's decision. | `RegisterSubmissionUseCaseTest` | Not restored. The test designer judges the coverage, and [§19.12](#1912-tests) lists what approval needs. | (decision) |
| D16 | The search selects the internal identity and the projection maps it. The view does not need it, and no response may carry it (BR-47). | `SubmissionQueryJdbcRepository`, `SubmissionSummaryJdbcProjection` | Remove `id AS submission_id` from the response query; keep `submission_id` only for the `ORDER BY` tie-break inside the view ([§20.3](#203-the-latest-state-view-the-strategy)). | CRITICAL (BR-47) |
| D17 | The design says the `201` body carries the id. The implementation returns the number, which is right. | design §13.2 | Corrected in the design ([§20.7](#207-submission-number-consistency-check)). No code change. | WARNING (design text) |
| D18 | The design's partition key is the aggregate id, which is the internal identity and must not reach a transport. | design §12.1, §19.7 | Corrected to the tenant and the number ([§20.7](#207-submission-number-consistency-check)). | WARNING (design text) |
| D19 | The migration history does not match the reviewed contents. `V2` was changed in place after its review (`566b76e`), and `V3` was changed in `44bc3de`. A database that applied the reviewed content would refuse to start against HEAD. | `V2`, `V3` | Restore `V2` and `V3` to the reviewed contents, after the check of [§20.4](#204-the-migration-v4); add V4 ([§20.4](#204-the-migration-v4)). | CRITICAL |
| D20 | The design's approval lookup is by internal identity. The spec says approval names the submission by its number, and the implementation already looks up by number. | design §19.4 | Corrected in the design. No code change. | WARNING (design text) |

Two further points. The implementation's `SubmissionApproved` record and `toString` already satisfy
BR-42 for the event, and the approval record has no `toString` yet, which the developer must add
([§19.5](#195-the-approval-state)). And the approval port returns a `Result` whose error type is
`ApproveSubmissionError`; the design narrows it to the one variant the insert can produce, as the
registration port does, so the adapter cannot fabricate another refusal.

### 19.12 Tests

Written by `@test-designer` at the red stage, in the same style as the registration tests. The use-case
tests use the in-memory fakes; the gate and the edge tests need no Spring; the database tests are
integration tests with a real PostgreSQL.

- **The check order, pinned.** A table over the cases in the spec's worked examples: no caller, a Viewer
  naming another organisation's submission, an underwriter naming an unknown one, an underwriter naming
  their own, an underwriter naming someone else's approved submission, and an underwriter naming someone
  else's unapproved one. Each row asserts the one refusal, or the success.
- **Self-approval before already-approved (BR-41).** A registrant re-approving a submission that is
  already approved by another caller receives `ApproverRegisteredSubmission`, not `SubmissionAlreadyApproved`.
  This pins the order rationale of §19.4.
- **Self-approval is refused and records nothing.** After the refusal, the fake approval store holds no row,
  and the fake publisher received no event.
- **The malformed number (D1, D2, D5, D6).** Over the MVC layer with a stub resolver: a Viewer naming
  `not-a-uuid` receives `403`; an underwriter naming `not-a-uuid` receives `404` with the same body as an
  unknown submission; `1-1-1-1-1`, which lenient parsing would accept, receives `404`; the body contains no
  echo of the path text.
- **Cross-organisation is the same as unknown (BR-35).** An underwriter naming another organisation's
  submission receives the same `404` body as an unknown one. The two bodies are compared byte for byte.
- **The race (BR-39).** Two approvals of one submission against an approval store whose insert honours a
  primary key. Exactly one success, exactly one `SubmissionAlreadyApproved`, exactly one event.
- **The event (BR-40, BR-42).** The event carries the approver, the moment and no insured limit; it is
  published once on success and never on refusal; its `toString` does not contain the approver's identity;
  the approval record's `toString` does not either.
- **The event id is pinned** through the fixed id generator (D7).
- **The approval roles (D13).** The permission admits exactly `UNDERWRITER` and `SENIOR_UNDERWRITER`, and
  refuses `VIEWER`, by a matrix that covers every value of `ActorRole`.
- **Search shows state (BR-17, D8).** A search returns `REGISTERED` for an unapproved row and `APPROVED` for
  an approved one, both in the caller's own organisation. A search by another organisation shows neither.
  The count is unchanged by state.
- **Schema, integration (D9).** A row in `submission_approvals` whose `tenant_id` differs from its
  submission's is rejected by the database. A second approval of one submission is refused by the
  primary key. Deleting an approved submission is refused rather than cascaded.
- **The race, integration.** Two concurrent approvals against real PostgreSQL: exactly one row.
- **The write-side purity rules** of §19.10, each broken on purpose once.

### 19.13 Questions for the product owner

- **Q10. Is `403` the right status for "approver registered this submission"?** The spec names the
  refusal and gives no status. The design uses `403`, because the caller is known and admitted, and the
  refusal concerns their own relation to a submission they can see. The alternative is `409`, as for an
  already-approved submission. This is an engineering choice, not a business rule, and the product owner
  is asked only to confirm it does not carry a business meaning.
- **Q11. Should search filter by state?** The spec shows state and specifies no state filter, so the design
  adds none. If a filter is wanted, it is a new criterion and a new BR.
- **Q12. Is there a logging requirement for approvals beyond BR-42?** BR-42 says an approver's identity is
  absent from the log entry, and "any operation by an approver is logged". The repository writes no log
  lines of its own, and the spec does not say what an approval log must contain or where it goes. The
  design satisfies BR-42 wherever a value is rendered, and it does not design a logging facility.
- **Q13. Must a refused approval be recorded for audit?** The spec says a refusal "records nothing". This
  section follows that. If audit needs refusals recorded, that is a new business fact, and it affects BR-43.
- **Q14. A stored row that fails to parse is reported as not found (§19.4).** The schema makes this
  unreachable for every row the database can hold, as §9.4 argues. The product owner is told the branch
  exists, so that a future change to the schema's constraints is seen to affect it.

### 19.14 What this section changes elsewhere in the design

- §1 scope and §14 "Not doing" are amended in place: approval is in scope, and the statements that it is
  not are withdrawn.
- §5.3 gains the state on the view; §6 is amended by the `LEFT JOIN` of §19.9.
- §8 gains a pointer to V3, which is specified here.
- §10 masking gains the approver, and §12 gains `SubmissionApproved`.
- §18 is the revision-4 change list for registration and search. The approval changes are the developer's
  list in §19.11.

---

## 20. The record: append-only facts, and the latest-state view

Revision 6, decided by the product owner as model A. A recorded fact is never changed or removed
(`BR-44`). A change of state is a new fact appended to the record. A view shows only the most
current state of each submission (`BR-45`), and that state is the latest fact recorded (`BR-46`). This
section is the design for that model. It amends [§6](#6-sql-shape), [§8](#8-schema),
[§9.3](#93-command-adapter), [§12.1](#121-deferred-adapter-decision-how-the-event-is-published),
[§13.2](#132-the-register-request-step-by-step), [§19.4](#194-the-check-order-and-which-part-enforces-each-step),
[§19.7](#197-the-event-submissionapproved-br-40), [§19.8](#198-schema-v3),
[§19.9](#199-the-search-change) and [§19.10](#1910-architecture-rules-for-approval), each of which
says so where it changed.

### 20.1 The model

There are **two fact tables**, one per kind of recorded fact:

| Fact | Table | Keyed by | Carries |
|---|---|---|---|
| registration (`BR-15`) | `submissions` | internal `id` (primary key), `(tenant_id, submission_number)` unique (`BR-06`) | the number, the insured limit, the registering caller, the registration moment |
| approval (`BR-37`) | `submission_approvals` | `submission_id` (primary key, `BR-36`, `BR-39`) | the approving caller, the moment of approval |

They are kept separate because the two kinds carry disjoint payloads. A single table would hold
nullable columns for every kind, and the storage-fit rules of [§15.8](#158-storage-fit-in-one-table)
would have to be stated per row. Two tables keep each fact's columns exact.

**The outbox is not a fact table.** `outbox_events` is updated by the relay (`processed_at`), which is
correct for an outbox. Nothing in this section applies to it, and [§20.5](#205-architecture-rules-and-how-the-rule-is-checked)
scopes the rule so that it does not reach the relay.

**No other table holds a state or an approval.** The current state of a submission is derived from these
two tables and is never stored.

### 20.2 Append-only: how the database enforces it

Three controls, each able to fail without the others, and all three are recommended:

| Control | Where it lives | What it stops | What it cannot stop |
|---|---|---|---|
| **Code and build** | the repositories hold only `INSERT` and `SELECT`; ArchUnit rule of [§20.5](#205-architecture-rules-and-how-the-rule-is-checked) | a change written by the service | a change written by a person with database access |
| **Grants** (primary) | the service role has `SELECT, INSERT` on the two fact tables and nothing else; applied by the deployment, not by a migration | any change made through the service's role | the table owner, and any role granted more |
| **Trigger** (backstop) | V4, statement-level `BEFORE UPDATE OR DELETE OR TRUNCATE` on both fact tables, raising an error | any change by any role other than a superuser, including the owner | the owner or a superuser disabling the trigger, which is visible in the migration history |

**Why the trigger is statement-level.** A row-level trigger does not fire for an `UPDATE` that matches
no row, so an attempted change that touches nothing would pass silently. A statement-level trigger fires
once per statement regardless of the row count, so the attempt itself is refused.

**Why grants are primary.** The grant is the least-privilege statement: the service's role cannot change a
fact, whatever its code does. It cannot be in the migration, because the role's name belongs to the
environment, not to the repository. It is a deployment step, and the integration test of
[§20.5](#205-architecture-rules-and-how-the-rule-is-checked) proves it.

**What the table owner can do.** The owner is the Flyway user. It can drop a trigger, so the trigger is a
control against the service and against mistakes, not against an operator who controls migrations. An
operator who drops the trigger changes the migration history, which is the place a review looks.

The `security-owasp-java.instructions.md` line that gives "the service's role `SELECT/INSERT/UPDATE` on its
own tables" is therefore wrong for the two fact tables, and [§20.6](#206-changes-outside-the-design) lists
it.

### 20.3 The latest-state view: the strategy

Three strategies answer "the most current state of each submission":

| Strategy | Shape | Correct under `BR-46`? | Tenant predicate | Cost |
|---|---|---|---|---|
| `DISTINCT ON (submission_id) … ORDER BY submission_id, occurred_at DESC` over the union of both fact tables | one row per submission, latest of its facts | yes, for any number of facts per kind | in each branch of the union and in the outer `WHERE` | reads every fact of every matching submission and sorts them, on each query |
| `ROW_NUMBER() OVER (PARTITION BY submission_id ORDER BY occurred_at DESC) = 1` over the same union | identical result to `DISTINCT ON` | yes, for any number of facts per kind | as above | the same sort, plus a window pass |
| `LEFT JOIN` of the registration to its approval on the approval's primary key | one row per registration, with `approved` set if an approval row exists | yes, **because `BR-46` allows at most one approval** and the primary key enforces it | in the join condition and in `WHERE` | one primary-key probe per registration row, no sort beyond the page |

**The choice: the `LEFT JOIN` on the approval's primary key, in a database view.**

**The argument.** `BR-46` fixes two facts: a submission has at most one registration and at most one
approval, and the state is the approval if one exists, otherwise the registration. The primary key of
`submission_approvals` makes the first half a constraint. Under that constraint the join returns exactly
one row per submission, so it cannot duplicate a submission or drop one. `BR-45` then holds by
construction: the row shows `approved` or nothing, and an earlier state is not a column of the view at
all. The join is also the cheapest of the three, because it probes a primary key once per row of the
page and never sorts the whole matching set.

**The tenant rule (`BR-17`).** The tenant predicate appears in the join condition
(`a.tenant_id = s.tenant_id`) and in the `WHERE` (`s.tenant_id = :tenantId`). The composite foreign key of
[§20.4](#204-the-migration-v4) makes the two equal in the database, so the join cannot pair an approval with a
registration of another organisation. The predicate appears in each place the rule could be broken, and
the `DISTINCT ON` and window forms would need it in every branch of a union, which is where a branch is
most easily forgotten.

**The trade-off accepted.** The join is correct only while `BR-46` holds. If the business ever allowed two
approvals, the join would become wrong, and the replacement would be a `LATERAL` subquery ordered by
`occurred_at` with `LIMIT 1`. That change is a business change, so the design does not pre-empt it. The
`DISTINCT ON` form would survive such a change without edit, and that is the price of the join's cost
advantage: the join states `BR-46` in its shape, and a future reader must see it there.

**Why a database view and not a Java mapping.** `BR-45` says "search and every other way of reading a
submission" obeys the latest-state rule. A view makes the rule one definition in the database, which every
read statement selects from. A rule kept in one Java statement would have to be copied into each read
path, and a copy is the way a later read path shows an earlier state as current. The view carries
`tenant_id`, so each reader still applies the tenant predicate; the view does not enforce it, and the
ArchUnit rule of [§20.5](#205-architecture-rules-and-how-the-rule-is-checked) requires every read repository
to name `tenant_id`.

**The view.** It is created in V4 ([§20.4](#204-the-migration-v4)):

```sql
CREATE VIEW submission_current AS
SELECT s.tenant_id,
       s.id              AS submission_id,   -- internal: the ORDER BY tie-break only; never mapped to a response
       s.submission_number,
       s.insured_limit,
       s.registered_by,
       s.registered_at,
       (a.submission_id IS NOT NULL) AS approved
FROM submissions s
LEFT JOIN submission_approvals a
       ON a.submission_id = s.id
      AND a.tenant_id     = s.tenant_id;
```

The search statement of [§19.9](#199-the-search-change) reads this view, with the same predicates as before,
`ORDER BY registered_at DESC, submission_id DESC`, and the same `LIMIT`/`OFFSET`. The count reads the view
with the same predicates and `COUNT(*)`. `submission_id` stays in the view only for the tie-break that makes
the order total (`BR-28`). The projection record does not map it, so it reaches no response.

**The read path must not reach the internal identity.** The current search statement selects
`s.id AS submission_id` and the projection maps it. The view does not need it for the response, and it is
removed from the projection (divergence D16 of [§19.11](#1911-where-the-implementation-diverges-required-changes)).

### 20.4 The migration: V4

**Versioning.** `V1`, `V2` and `V3` are not edited by this design. A new versioned migration is the only way
to change a database that may already have applied them. `V4__record_append_only_and_current_state.sql` is
the single migration this section adds.

**The reviewed contents are the reference.** The repository's history shows `V2` changed in place several times
after the review that approved it (`566b76e`), and `V3` changed in `44bc3de` after `5bf01b3` introduced it. A
database that applied the reviewed content has a Flyway checksum that the current file no longer matches.
Flyway refuses to start against such a database, and editing the file again does not repair it. The plan
therefore depends on which content each database applied, and that is established before V4 runs:

| What the database applied | Action | Result |
|---|---|---|
| `V2` from `566b76e` and `V3` from `5bf01b3`, or no database at all | restore `V2` and `V3` to those exact contents, then add V4 | a clean history; V4 applies |
| `V2` or `V3` from HEAD, which is not in the reviewed history | a human checks the live schema against the reviewed contents; if it matches, `flyway repair` after the check; if not, a decision with the product owner ([§20.8](#208-questions-for-the-product-owner), Q15) | the file in git matches the database, or the database is repaired deliberately |

The restore is not an edit of V2 or V3 in the sense this section forbids. It puts back the bytes that were
reviewed, so the history matches a database that already exists. The developer performs it once, and
[§20.6](#206-changes-outside-the-design) names it.

**What V4 contains, with the reviewed `V2` and `V3` as its starting point.** The column in the reviewed
`V2` is `external_ref`, and HEAD's `V2` renames it to `submission_number`. V4 performs the rename, so the
name the spec uses (`BR-47`) exists on every database, whichever content it applied.

```sql
-- V4: the record is append-only (BR-44), and the latest-state view (BR-45, BR-46).
-- Changes no row. Assumes V2 and V3 are the reviewed contents (566b76e, 5bf01b3).

-- 1. The name the spec uses. Metadata only: no row is rewritten.
ALTER TABLE submissions RENAME COLUMN external_ref TO submission_number;

-- 2. The approval references the registration on both keys, without cascade.
--    The single-column cascading key of V3 is replaced. The redundant index of V3 is dropped.
ALTER TABLE submission_approvals DROP CONSTRAINT fk_submission_approvals_submission;
DROP INDEX idx_submission_approvals_tenant_submission;
ALTER TABLE submissions ADD CONSTRAINT uq_submissions_id_tenant UNIQUE (id, tenant_id);
ALTER TABLE submission_approvals
    ADD CONSTRAINT fk_submission_approvals_submission
    FOREIGN KEY (submission_id, tenant_id) REFERENCES submissions (id, tenant_id) NOT VALID;
ALTER TABLE submission_approvals VALIDATE CONSTRAINT fk_submission_approvals_submission;

-- 3. Append-only: any UPDATE, DELETE or TRUNCATE on a fact table is refused, statement by statement.
CREATE FUNCTION refuse_change_to_recorded_fact() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'recorded facts are append-only (BR-44): % on % refused', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'insufficient_privilege';
    RETURN NULL;
END;
$$;

CREATE TRIGGER submissions_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON submissions
    FOR EACH STATEMENT EXECUTE FUNCTION refuse_change_to_recorded_fact();

CREATE TRIGGER submission_approvals_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON submission_approvals
    FOR EACH STATEMENT EXECUTE FUNCTION refuse_change_to_recorded_fact();

-- 4. The latest-state view (§20.3).
CREATE VIEW submission_current AS
SELECT s.tenant_id,
       s.id AS submission_id,
       s.submission_number,
       s.insured_limit,
       s.registered_by,
       s.registered_at,
       (a.submission_id IS NOT NULL) AS approved
FROM submissions s
LEFT JOIN submission_approvals a
       ON a.submission_id = s.id
      AND a.tenant_id     = s.tenant_id;
```

**Why V4 does not rewrite existing rows.** Every statement is metadata or a read:

- `RENAME COLUMN` changes the catalogue. No row is read or written.
- `DROP CONSTRAINT`, `DROP INDEX` and `ADD CONSTRAINT … UNIQUE` change the catalogue. The unique constraint
  on `(id, tenant_id)` is built from a column that is already unique by its primary key, so it cannot fail.
- `ADD CONSTRAINT … FOREIGN KEY … NOT VALID` changes the catalogue and does not look at existing rows.
- `VALIDATE CONSTRAINT` reads every existing approval once to confirm that its tenant is its submission's
  tenant. It writes nothing. If a row disagrees, the migration **stops** and the row is left as it is. Repair
  would be a change to a recorded fact, which `BR-44` forbids, so a human decides what the row should mean.
- `CREATE FUNCTION`, `CREATE TRIGGER` and `CREATE VIEW` add objects. No row is touched.
- No column is added, no default is set, and no type changes, so no table is rewritten.

**Locks.** `RENAME` takes a brief exclusive lock on `submissions`. `ADD CONSTRAINT … NOT VALID` and
`CREATE TRIGGER` take a lock that blocks writes, not reads, for their duration. `VALIDATE CONSTRAINT`
blocks neither reads nor writes. The whole migration is short, and it should run outside peak
registration hours.

**What V4 does not do.** It adds no index. The join uses the primary key of `submission_approvals` and the
unique `(id, tenant_id)`, which exists. The search's predicates on `submissions` are served by the indexes
that V2 already creates, as [§8.2](#82-indexes-which-index-serves-which-filter) states.

**Grants are not in V4.** The service role's grants are a deployment step ([§20.2](#202-append-only-how-the-database-enforces-it)),
because the role's name is not in the repository.

### 20.5 Architecture rules, and how the rule is checked

**No existing rule fails with the new model.** The new repository methods are plain `INSERT` and `SELECT`,
the view is read through the existing projection path, and the new value `SubmissionState` is an enum. Each
existing rule of [§15.10](#1510-cqs-rules-verbatim) and [§19.10](#1910-architecture-rules-for-approval) was
checked against the design. The one that gives false comfort is rule A of [§19.10](#1910-architecture-rules-for-approval),
`no_operation_reverses_or_rewrites_a_recorded_fact`. It is name-based, so it passes today because no method is
named `update`, not because no operation can change a fact. It stays for `domain/`, and the rule below is the one
that pins the persistence layer.

**The rule, verbatim.** It reads the SQL of each `@Query` on the submission repositories:

```java
@ArchTest
static final ArchRule submission_repositories_issue_only_inserts_and_selects =
        methods().that().areDeclaredInClassesThat().resideInAPackage("..adapters.persistence..")
                .and().areDeclaredInClassesThat().haveSimpleNameStartingWith("Submission")
                .and().areDeclaredInClassesThat().haveSimpleNameEndingWith("JdbcRepository")
                .and().areAnnotatedWith(Query.class)
                .should(new ArchCondition<JavaMethod>(
                        "issue only INSERT or SELECT, never UPDATE, DELETE or TRUNCATE") {
                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        var sql = method.getAnnotationOfType(Query.class.getName())
                                .get("value").map(String::valueOf).orElse("");
                        var changesRecord = Pattern.compile(
                                "(?i)\\b(UPDATE|DELETE|TRUNCATE)\\b").matcher(sql).find();
                        if (changesRecord) {
                            events.add(SimpleConditionEvent.violated(method,
                                    method.getFullName() + " changes a recorded fact, which BR-44 forbids"));
                        }
                    }
                })
                .because("recorded facts are append-only (BR-44). The service's persistence never changes a row "
                        + "of a submission table; a change of state is an INSERT into its own fact table");
```

**Why the scope is by name.** `haveSimpleNameStartingWith("Submission")` selects the submission repositories
and excludes the outbox relay, whose `UPDATE` on `processed_at` is correct for an outbox. A new submission
repository that does not start with `Submission` escapes the rule, and the next reviewer must notice that.
This is a known limit, recorded here rather than hidden.

**Why this, and not a grep.** A grep over the source reads the text of a file. It does not see a statement
built at run time, and it is not in the build, so a developer can run it or not. The ArchUnit rule reads the
annotation value the framework will execute, and it fails `./mvnw test`.

**The runtime proof: an integration test.** The rule checks the source. The database proves the other half.
An integration test, against real PostgreSQL, does the following, once as the service's role and once as the
owner:

- `UPDATE` and `DELETE` on each fact table fail, including an `UPDATE` that matches no row.
- `TRUNCATE` on each fact table fails.
- A registration and an approval, both inserted, leave the view showing `approved = true` for that submission
  and one row per submission.

The test that fails when the grant is missing is the one that runs as the service's role. A test run only as
the owner would pass whether or not the grant exists.

**The view's tenant rule is not checked by the rule above.** That rule reads the shape of the SQL, not whether a predicate is present. A second rule would require the token `tenant_id` in every statement that names `submission_current`, and it is added with the view. Until it is, the tenant predicate is a review item for every new read statement, and the integration test of this section is the only automated check.

---

### 20.6 Changes outside the design

| File | Line or section | Change |
|---|---|---|
| `.github/instructions/security-owasp-java.instructions.md` | "the service's role gets `SELECT/INSERT/UPDATE` on its own tables" | `SELECT, INSERT` on the fact tables; `SELECT, INSERT, UPDATE` on the outbox only |
| `src/main/resources/db/migration/V2__create_submissions.sql` | HEAD content | restore the reviewed content (`566b76e`) if the database check of [§20.4](#204-the-migration-v4) says so, and not otherwise. The developer does not edit it on a guess |
| `src/main/resources/db/migration/V3__create_submission_approvals.sql` | HEAD content | restore the content of `5bf01b3`, the content the reviewed history contains. The composite key moves to V4 |
| `src/main/resources/db/migration/V4__record_append_only_and_current_state.sql` | new | the migration of [§20.4](#204-the-migration-v4) |
| `docs/adr/submission-registration-design.md` §19.8 | "Edited in place only because V3 was never applied" | withdrawn: that claim was not verified and the history contradicts it |
| `ArchitectureRulesTest.java` | new | `submission_repositories_issue_only_inserts_and_selects` |
| integration test tree | new | the append-only and view test of [§20.5](#205-architecture-rules-and-how-the-rule-is-checked), as the service's role and as the owner |
| deployment | new step | the grants on the fact tables for the service's role |

### 20.7 Submission number: consistency check

**Every fact, view, event and response is keyed by the submission number, and the internal identity is used
only inside the service.** The check, item by item:

| Surface | Identifies the submission by | Internal id present? | Status |
|---|---|---|---|
| `submissions`, `submission_approvals` (storage) | `submission_number` (unique per tenant), `id` (primary key) | yes, as the key | consistent: the key is internal and allowed (`BR-47`: "used only inside this service") |
| `submission_current` (view) | `submission_number` | yes, for the tie-break only | consistent with [§20.3](#203-the-latest-state-view-the-strategy): not mapped to any response |
| search response, `SubmissionSummaryView` | `submissionNumber` | no | consistent in the design; the current search projection selects the internal id (D16) |
| `SubmissionRegistered` event | `submissionNumber` | no | consistent (HEAD design) |
| `SubmissionApproved` event | `submissionNumber` | no | consistent (HEAD design) |
| approval path `POST …/{number}/approve` | the number | no | consistent; the implementation looks up by number |
| registration response, `201` body | the number | no | the design says "carries the id" in [§13.2](#132-the-register-request-step-by-step), which is wrong; the implementation returns the number (D17) |
| `Location` of `201` | the number | no | consistent with the implementation |
| partition key, if a transport is chosen | the internal id in [§12.1](#121-deferred-adapter-decision-how-the-event-is-published) and [§19.7](#197-the-event-submissionapproved-br-40) | **yes, through the design's own rule** | **mismatch**: a key derived from the internal id would put it into the transport. It must be the tenant and the number (D18) |
| approval lookup, [§19.4](#194-the-check-order-and-which-part-enforces-each-step) | `findOwn(tenantId, id)` | yes | **mismatch**: the spec says approval names the submission by its number. The implementation uses `findOwnByNumber`; the design text is corrected |
| logs | the number, which `BR-23` allows | no | consistent |

**The mismatches the design must correct,** all in sections this revision amends: the partition key (§12.1 and
§19.7), the registration response in §13.2, and the approval lookup in §19.4. The implementation is already right
on the approval lookup and on the registration response. The search projection and the design's partition-key
rule are the two places that need a change in the code or the text.

**Naming.** The reviewed `V2` names the column `external_ref`, and the spec's term is "submission number". V4
renames the column. Until V4 has run on a database, its code and its name disagree, which is why the rename and
the code change must ship together.

### 20.8 Questions for the product owner

- **Q15. Which content have the databases applied?** The check in [§20.4](#204-the-migration-v4) needs the Flyway
  history of each environment. If no environment outside a developer's machine has applied `V2` or `V3`, the
  plan is a restore and V4. If one has, the answer depends on whether its live schema matches the reviewed
  contents, and that is a decision on the data, not only on the file.
- **Q16. Is any data removal in scope?** `BR-44` forbids removing a recorded fact. The specification leaves
  retention out of scope, as it did before. A removal request for an approver's identity would meet an
  append-only table. Confirm that no deletion, anonymisation or retention job is wanted in this feature.
- **Q17. Can a caller read the history of a submission?** `BR-45` says an earlier state "remains on record" and
  is never shown as current. The specification does not say whether a caller may read it, or only audit may.
  This section adds no endpoint for history, and a history endpoint would need its own rule.

---

## 21. Revision 7 (2026-11-08): allowed characters, V4, and the review blockers

Product owner decisions of 2026-11-08, binding: a submission number has no special characters
(`BR-29` rewritten); write V4; no separate migration role (R-03 accepted while the service is not
in production); adapters are public, so tests may drive them from outside their package (G1).
Q15 is answered by "not in production": no database has to be preserved, V2 and V3 stay as they
are at `44bc3de`, and V4 is the next migration on top of them.

### 21.1 BR-29: the allowed set

`SubmissionNumber.parse` keeps its order (presence, length, content). Content is now an allow
list, `A`-`Z`, `a`-`z`, `0`-`9`, `-`, checked code point by code point. The first other code
point is reported:

- `SubmissionNumberError.CharacterNotAllowed(int codePoint, int index)` replaces `ControlCharacter`;
- `RegisterSubmissionError.NumberContainsCharacterNotAllowed(int codePoint, int index)` replaces
  `NumberContainsControlCharacter`; HTTP 400, problem type
  `urn:problem-type:submission:number-contains-character-not-allowed`;
- the search filter maps `CharacterNotAllowed` to `MatchesNothing` (BR-30).

Every allowed character is ASCII, so a code point is a character and `VARCHAR(64)` counts the
same thing; BR-05 does not change. `ControlCharacters` stays in the kernel for actor ids.

### 21.2 R-01: the number in the path

With the allow list, no registrable number contains `;`, `/`, `%` or `.`. That alone does not fix
R-01: Spring strips `;...` from `{number}` before the resource sees it, so `REF;1` would still
arrive as `REF`. The approval resource therefore reads the number from the raw request URI, the
segment between `/api/submissions/` and `/approve`, **not** decoded, and parses that. No
allowed character ever needs percent-encoding, so a `%` is itself refused by `BR-29`, and an invalid
escape cannot fail as a 500. `REF;1`
then fails `BR-29` and is refused as not found (`BR-48`), with the one constant 404 body. A path
with an extra `/` matches no mapping and gets the framework's 404; no registrable number can
produce it.

### 21.3 V4

`V4__allowed_characters_append_only_and_current_state.sql`:

1. replace `ck_submissions_submission_number_no_control_chars` by
   `ck_submissions_submission_number_allowed_chars CHECK (submission_number ~ '^[A-Za-z0-9-]+$')`;
2. `refuse_change_to_recorded_fact()` and statement-level `BEFORE UPDATE OR DELETE OR TRUNCATE`
   triggers on `submissions` and `submission_approvals` (BR-44). The service role owns the tables
   (R-03 accepted), so an owner can still disable a trigger; the trigger stops every ordinary
   statement, which is what BR-44 asks of the service;
3. `submission_current`: one row per submission with `tenant_id`, `submission_number`,
   `insured_limit`, `registered_by`, `registered_at`, `approved`. No internal id (BR-47, D16).

Search reads `submission_current` only. The tie-break of the order becomes
`submission_number DESC`, unique within a tenant: BR-28 needs a total order, and BR-24 decides only
the newest-first part.

Test teardown can no longer `TRUNCATE` or `DELETE` the fact tables. Tests clear them as the owner
by disabling the triggers for the clean-up only (`FactTables.clear` in the test tree). That helper
is also the executable record that R-03 is a real, accepted gap.

### 21.4 ArchUnit

- Rule C no longer depends on the constructor signature: only `ApproveSubmissionUseCase` may call
  any constructor of `SubmissionApproved`.
- New: `submission_repositories_issue_only_inserts_and_selects`. No `@Query` in
  `submission.adapters.persistence` contains `UPDATE`, `DELETE` or `TRUNCATE`.

### 21.5 Public adapters (G1)

`ActorContextResolver` (with `resolve()`), the three resources and the three SQL adapters become
`public` classes. The resources get public constructors. JDBC entities, the projection, the mapper
and the Spring Data repositories stay package-private: they are the adapters' internals, and the
Test contract still excludes them.

## Test contract

Design mode, written against the code at HEAD (`bedd141`). This is the only surface a test may use.
Every name below exists in `src/main` today and can be found with grep. Where the code differs from
what an earlier section of this design intends, this section states **the code**, because tests
compile against the code, and lists the difference under
[Divergences from design](#divergences-from-design). Nothing here is a change request to the code.

A test enters and observes the module only through what is listed here. If a scenario cannot be
expressed through it, the contract is missing something: report it, and do not reach inside.
Package names below are relative to `com.gs5.underwriting`.

### TC1. Types every test may use

| Type | Package | Contract surface |
|---|---|---|
| `Result<T, E>` | `shared.kernel` | `Result.success`, `Result.failure`, `Result.fromOptional`, the records `Success(T value)` and `Failure(E error)`, and every public default method (`isSuccess`, `isFailure`, `map`, `flatMap`, `mapError`, `fold`, `peek`, `toOptional`, `errorToOptional`, ...) |
| `ActorContext` | `shared.kernel` | `record ActorContext(UUID tenantId, String actorId, ActorRole role)`; `static Optional<ActorContext> parse(@Nullable String tenantId, @Nullable String actorId, @Nullable ActorRole role)`; `MAX_ACTOR_ID_LENGTH = 255` |
| `ActorRole` | `shared.kernel` | `VIEWER`, `UNDERWRITER`, `SENIOR_UNDERWRITER`; `boolean canDecide()` |
| `AuthenticatedCaller` | `submission.adapters.access` | `static Optional<AuthenticatedCaller> parse(@Nullable String tenantId, @Nullable String actorId, @Nullable String role)`; `ActorContext actor()`; `ActorRole role()` |
| `AccessRefusal` | `submission.adapters.access` | sealed: `CallerNotIdentified()`, `PermissionDenied(ActorRole role)` |
| `AccessPolicy` | `submission.adapters.access` | `boolean mayRegister(ActorRole role)`, `boolean mayApprove(ActorRole role)`, `Optional<String> tenantIdFrom(Map<String, String> identityClaims)` |
| `Permission` | `submission.adapters.access` | `Result<AuthenticatedCaller, AccessRefusal> check(Optional<AuthenticatedCaller> caller)`; `static anyIdentifiedCaller()`, `static registration(AccessPolicy policy)`, `static approval(AccessPolicy policy)` |
| `OperationGate<C, R>` | `submission.adapters.access` | `new OperationGate<>(Permission permission, BiFunction<ActorContext, C, R> operation)`; `Result<Admission<C, R>, AccessRefusal> admit(Optional<AuthenticatedCaller> caller)` |
| `Admission<C, R>` | `submission.adapters.access` | `R run(C input)`; no public constructor |
| `SubmissionNumber` | `submission.domain` | `static Result<SubmissionNumber, SubmissionNumberError> parse(@Nullable String text)`; `String value()`; `MAX_LENGTH = 64`; value equality |
| `SubmissionNumberError` | `submission.domain` | sealed: `Missing()`, `TooLong(int maxLength, int actualLength)`, `ControlCharacter(int codePoint, int index)` |
| `InsuredLimit` | `submission.domain` | `static Result<InsuredLimit, RegisterSubmissionError> parse(@Nullable BigDecimal amount)`; `BigDecimal amount()`; `REQUIRED_SCALE = 2`; value equality |
| `SubmissionId` | `submission.domain` | `record SubmissionId(UUID value)` |
| `SubmissionSnapshot` | `submission.domain` | `record SubmissionSnapshot(UUID id, UUID tenantId, String submissionNumber, BigDecimal insuredLimit, String registeredBy, Instant registeredAt, @Nullable Long version)`: what a command-repository fake stores and returns |
| `SubmissionState` | `submission.domain` | `REGISTERED`, `APPROVED` |
| `SubmissionDomainEvent` | `submission.domain` | sealed; `UUID eventId()`, `SubmissionNumber submissionNumber()`, `UUID tenantId()`, `Instant occurredAt()`; records `SubmissionRegistered(UUID eventId, SubmissionNumber submissionNumber, UUID tenantId, BigDecimal insuredLimit, String registeredBy, Instant occurredAt)` and `SubmissionApproved(UUID eventId, SubmissionNumber submissionNumber, UUID tenantId, String approvedBy, Instant occurredAt)` |

**Masking is a contract property, the wording is not (BR-22, BR-42, OPEN-01).** For `ActorContext`,
`InsuredLimit`, `RegisterSubmissionCommand`, `SearchSubmissionsQuery`, `SubmissionFilter.Narrowing`,
`SubmissionSearchCriteria`, `SubmissionSnapshot`, `SubmissionSummaryView`, `SubmissionRegistered` and
`SubmissionApproved`, a test may assert that `toString()` contains neither the insured limit (nor an
insured-limit bound) nor the caller's `actorId` / `approvedBy`. The rest of the `toString()` text is
not contract.

### TC2. Register a submission (BR-01 to BR-15, BR-29)

**Use case entry point.**

| | |
|---|---|
| Class | `submission.domain.RegisterSubmissionUseCase` |
| Constructor | `RegisterSubmissionUseCase(SubmissionCommandRepository repository, SubmissionEventPublisher eventPublisher, SubmissionIdGenerator idGenerator, Clock clock)` |
| Method | `Result<SubmissionId, RegisterSubmissionError> execute(ActorContext actor, RegisterSubmissionCommand command)` |
| Command | `record RegisterSubmissionCommand(SubmissionNumber submissionNumber, InsuredLimit insuredLimit)`; factory `static Result<RegisterSubmissionCommand, RegisterSubmissionError> parse(@Nullable String submissionNumber, @Nullable BigDecimal insuredLimit)` |
| Success | `Success(SubmissionId)`, the id the repository port returned for the inserted snapshot |
| Event | on success only, exactly one `SubmissionRegistered` handed to `SubmissionEventPublisher.publish`: `eventId` is a value returned by `nextEventId()`, then the command's number, `actor.tenantId()`, the command's amount, `actor.actorId()`, and `occurredAt` = the clock's instant. Nothing is published on failure |

Error variants of `RegisterSubmissionError`, and which entry point returns each:

| Variant | Returned by | Rule |
|---|---|---|
| `NumberMissing()` | `RegisterSubmissionCommand.parse` | BR-04 |
| `NumberTooLong(int maxLength, int actualLength)` | `RegisterSubmissionCommand.parse` | BR-05 |
| `NumberContainsControlCharacter(int codePoint, int index)` | `RegisterSubmissionCommand.parse` | BR-29 |
| `InsuredLimitMissing()` | `RegisterSubmissionCommand.parse` | BR-10 |
| `InsuredLimitNotPositive()` | `RegisterSubmissionCommand.parse` | BR-11 |
| `InsuredLimitPrecisionInvalid(int requiredScale, int actualScale)` | `RegisterSubmissionCommand.parse` | BR-12 |
| `NumberAlreadyUsed(String submissionNumber)` | `execute`, passed through from `SubmissionCommandRepository.insert` | BR-06, BR-09 |

`parse` judges the number first and then the amount, and returns the first failure (§4.3). The
declared error type of `execute` is the whole hierarchy (divergence E1), but the only variant it
returns is `NumberAlreadyUsed`. Caller not identified (BR-01) and role may not register (BR-02) are
not domain errors: they are the `AccessRefusal` of `Permission.registration(AccessPolicy)`.

**HTTP endpoint.** `POST /api/submissions`, `Content-Type: application/json`.

Request body: `{"submissionNumber": string | null, "insuredLimit": number | null}`. `insuredLimit` is
read as a `BigDecimal`, so its scale is the scale written in the JSON: `1200.00` has scale 2 and `1200`
has scale 0. No other field is read. The organisation, the caller and the moment never come from the
body (BR-15).

| Outcome | Status | `type` | `detail`, as implemented |
|---|---|---|---|
| registered | `201`, header `Location: /api/submissions/{number}` | — | body `{"submissionNumber": "<number>"}` |
| caller not identified (BR-01) | `401` | `urn:problem-type:submission:caller-not-identified` | `The caller could not be identified.` |
| role may not register (BR-02) | `403` | `urn:problem-type:submission:permission-denied` | `The role VIEWER may not register a submission.` |
| number missing (BR-04) | `400` | `urn:problem-type:submission:number-missing` | `submissionNumber is required and must contain at least one character.` |
| number too long (BR-05) | `400` | `urn:problem-type:submission:number-too-long` | `submissionNumber must be at most 64 characters, but was <n>.` |
| control character (BR-29) | `400` | `urn:problem-type:submission:number-contains-control-character` | `submissionNumber must not contain a control character, but U+<hex> was found at code point index <i>.` |
| insured limit missing (BR-10) | `400` | `urn:problem-type:submission:insured-limit-missing` | `insuredLimit is required.` |
| insured limit not positive (BR-11) | `400` | `urn:problem-type:submission:insured-limit-not-positive` | `insuredLimit must be greater than zero.` |
| precision invalid (BR-12) | `400` | `urn:problem-type:submission:insured-limit-precision-invalid` | `insuredLimit must have exactly 2 decimal places, but had <s>.` |
| number already used (BR-06, BR-09) | `409` | `urn:problem-type:submission:number-already-used` | `The submission number '<number, category Cc escaped as \uXXXX>' is already used by a submission of your organisation.` |

Every refusal body is `application/problem+json` with `type`, `title` (the HTTP reason phrase),
`status` and `detail`. **The contract is the status and the `type`.** The `detail` column is there so
a test can assert what a body must never contain (the insured limit, BR-22; an internal id, BR-47),
not to pin wording. `instance` is not set by this resource and is not contract. No `GET` handler exists
for the `Location` path.

The order at the edge, as implemented: `ActorContextResolver.resolve()`, then the gate (`401`, `403`),
then `RegisterSubmissionCommand.parse` (`400`), then `execute` (`409`). Spring reads the JSON body
before the gate runs, so a body that is not readable JSON, or an `insuredLimit` that cannot be read as
a `BigDecimal`, is a framework `400` before any `401` or `403` (divergence E9). The body of that `400`
is not specified by this module.

### TC3. Search submissions (BR-01, BR-16 to BR-28, BR-30 to BR-32, BR-45)

**Use case entry point.**

| | |
|---|---|
| Class | `submission.domain.SearchSubmissionsUseCase` |
| Constructor | `SearchSubmissionsUseCase(SubmissionQueryRepository repository)` |
| Method | `SubmissionPageView ask(ActorContext actor, SearchSubmissionsQuery query)`. There is no `Result` and no error variant: the read side refuses nothing (§5) |
| Query | `record SearchSubmissionsQuery(SubmissionFilter filter, int page, int size)`. The compact constructor clamps `page` to `>= 0` and `size` into `[MIN_PAGE_SIZE, MAX_PAGE_SIZE]`, which are `1` and `100`. Factory: `static SearchSubmissionsQuery parse(@Nullable String submissionNumber, @Nullable BigDecimal insuredLimitFrom, @Nullable BigDecimal insuredLimitTo, @Nullable Instant registeredFrom, @Nullable Instant registeredTo, int page, int size)` |
| Filter | `sealed interface SubmissionFilter` with `Narrowing(@Nullable SubmissionNumber reference, @Nullable BigDecimal insuredLimitFrom, @Nullable BigDecimal insuredLimitTo, @Nullable Instant registeredFrom, @Nullable Instant registeredTo)` and `MatchesNothing()`; `static SubmissionFilter parse(...)` with the same five parameters as `SearchSubmissionsQuery.parse` |
| Success | `record SubmissionPageView(List<SubmissionSummaryView> submissions, long totalMatchCount, int page, int size)`. Each row is `record SubmissionSummaryView(String submissionNumber, BigDecimal insuredLimit, String registeredBy, Instant registeredAt, SubmissionState state)`. `page` and `size` echo the clamped values. No row carries an internal id (BR-47) |
| Event | none |

How the number filter folds, through `SubmissionNumber.parse`:

| Number filter | Filter | Rule |
|---|---|---|
| `null` or `""` | `Narrowing` with `reference == null` | BR-19, BR-31 |
| contains a category-Cc character | `MatchesNothing` | BR-30 |
| 65 or more code points | `MatchesNothing` | BR-32 |
| otherwise | `Narrowing` with that `SubmissionNumber`, compared exactly | BR-07 |

For `MatchesNothing`, `ask` returns an empty page with `totalMatchCount == 0` and the echoed page and
size, and calls the repository not at all. For `Narrowing`, `ask` calls
`findPage(criteria, query.size(), (long) query.page() * query.size())` and `countMatching(criteria)`,
with `criteria.tenantId()` equal to `actor.tenantId()` and `criteria.submissionNumber()` equal to the
filter's number text, or `null`. The only refusal is BR-01, as `AccessRefusal.CallerNotIdentified` from
`Permission.anyIdentifiedCaller()`.

**HTTP endpoint.** `GET /api/submissions`, no body. Query parameters, all optional:

| Parameter | Type | Default |
|---|---|---|
| `submissionNumber` | text | absent |
| `insuredLimitFrom`, `insuredLimitTo` | decimal | absent |
| `registeredFrom`, `registeredTo` | ISO-8601 instant, converted by Spring | absent |
| `page` | int | `0` |
| `size` | int | `100` |

| Outcome | Status | Body |
|---|---|---|
| found, including an empty page (BR-20, BR-21, BR-27, BR-30 to BR-32) | `200` | `{"submissions": [{"submissionNumber", "insuredLimit", "registeredBy", "registeredAt", "state"}], "totalMatchCount", "page", "size"}`, with `state` as `"REGISTERED"` or `"APPROVED"` |
| caller not identified (BR-01) | `401` | problem, `type` `urn:problem-type:submission:caller-not-identified` |
| `PermissionDenied` | `403` | problem, `type` `urn:problem-type:submission:permission-denied`. Unreachable for search (BR-16), and a test proves it never occurs |

Rows are ordered by `registeredAt`, newest first (BR-24). Among rows with equal `registeredAt` the
order is deterministic but not contract beyond BR-28: each row appears on exactly one page. A query
parameter that Spring cannot convert (`size=abc`, `insuredLimitFrom=x`, a malformed instant) is a
framework `400` before the gate runs (divergence E9). The JSON text format of `registeredAt` comes from
the application's Jackson configuration, and nothing in `src/main` pins it.

### TC4. Approve a submission (BR-01, BR-33 to BR-43, BR-48)

**Use case entry point.**

| | |
|---|---|
| Class | `submission.domain.ApproveSubmissionUseCase` |
| Constructor | `ApproveSubmissionUseCase(SubmissionCommandRepository commandRepository, SubmissionApprovalRepository approvalRepository, SubmissionEventPublisher eventPublisher, SubmissionIdGenerator idGenerator, Clock clock)` |
| Method | `Result<SubmissionNumber, ApproveSubmissionError> execute(ActorContext actor, ApproveSubmissionCommand command)` |
| Command | `record ApproveSubmissionCommand(SubmissionNumber submissionNumber)`; factory `static Result<ApproveSubmissionCommand, ApproveSubmissionError> parse(String rawNumber)`. Every parse failure is `SubmissionNotFound()` (BR-48) |
| Success | `Success(SubmissionNumber)`, the command's number. The internal id is not returned (BR-47) |
| Event | on success only, exactly one `SubmissionApproved` to `SubmissionEventPublisher.publish`: `eventId` from `nextEventId()`, the command's number, `actor.tenantId()`, `approvedBy = actor.actorId()`, and `occurredAt` = the same instant that was handed to the approval port. Nothing is published on failure (BR-40) |

Error variants of `ApproveSubmissionError`. All three are fieldless:

| Variant | Returned when | Rule |
|---|---|---|
| `SubmissionNotFound()` | `ApproveSubmissionCommand.parse` fails; or `findOwnByNumber(actor.tenantId(), number)` is empty; or the stored snapshot no longer parses | BR-35, BR-48 |
| `ApproverRegisteredSubmission()` | the snapshot's `registeredBy` equals `actor.actorId()`. Checked before the approval port is called | BR-41 |
| `SubmissionAlreadyApproved()` | returned by `SubmissionApprovalRepository.approve` and passed through unchanged | BR-36, BR-39 |

Order inside `execute`: lookup by number in the caller's organisation, then the self-approval check,
then `approve(actor.tenantId(), <the snapshot's id as SubmissionId>, actor, clock.instant())`. The
clock is read only on the path that reaches the approval port. Caller not identified (BR-01) and role
may not approve (BR-33) are the `AccessRefusal` of `Permission.approval(AccessPolicy)`.

**HTTP endpoint.** `POST /api/submissions/{number}/approve`, no body. `{number}` is a `String` path
variable. Spring does not convert it, and it is parsed only after the caller is admitted.

| Outcome | Status | `type` | `detail`, as implemented |
|---|---|---|---|
| approved | `200` | — | body `{"submissionNumber": "<number>"}` |
| caller not identified (BR-01) | `401` | `urn:problem-type:submission:caller-not-identified` | `The caller could not be identified.` |
| role may not approve (BR-33) | `403` | `urn:problem-type:submission:permission-denied` | `The role VIEWER may not approve a submission.` |
| submission not found (BR-35, BR-48) | `404` | `urn:problem-type:submission:submission-not-found` | `The submission was not found.` |
| approver registered this submission (BR-41) | `403` | `urn:problem-type:submission:approver-registered-submission` | `The caller registered this submission and may not approve it.` |
| already approved (BR-36, BR-39) | `409` | `urn:problem-type:submission:submission-already-approved` | `The submission is already approved.` |

Every approval refusal is `application/problem+json` with `instance` set to the constant
`/api/submissions/approval`, so no body echoes the path. **The `404` body is contract in full:** it is
byte-identical for a malformed number, an unknown number and another organisation's number (BR-35,
BR-43, BR-48). For the other outcomes the contract is the status and the `type`.

### TC5. Outbound ports a test may replace with a fake

All five are public interfaces in `submission.domain`. The right-hand column states what the
production adapter does, so that a fake which does the same lets a use-case test stand in for an
integration test.

| Port | Methods | Used by | A fake behaves like the adapter when it |
|---|---|---|---|
| `SubmissionCommandRepository` | `Result<SubmissionId, RegisterSubmissionError.NumberAlreadyUsed> insert(SubmissionSnapshot snapshot)`; `Optional<SubmissionSnapshot> findOwnByNumber(UUID tenantId, SubmissionNumber submissionNumber)` | register, approve | refuses `insert` with `NumberAlreadyUsed(snapshot.submissionNumber())` when the same `(tenantId, submissionNumber)` is held, compared exactly, and otherwise returns `new SubmissionId(snapshot.id())`; finds only within the given tenant |
| `SubmissionApprovalRepository` | `Result<SubmissionId, ApproveSubmissionError> approve(UUID tenantId, SubmissionId submissionId, ActorContext actor, Instant approvedAt)` | approve | returns `Success(submissionId)` the first time a `submissionId` is approved, and `SubmissionAlreadyApproved()` every later time |
| `SubmissionQueryRepository` | `List<SubmissionSummaryView> findPage(SubmissionSearchCriteria criteria, int limit, long offset)`; `long countMatching(SubmissionSearchCriteria criteria)` | search | matches `criteria.tenantId()` and every non-null criterion, with inclusive bounds and an exact number; orders newest `registeredAt` first; counts the whole match and ignores `limit` and `offset`; reports `APPROVED` only for an approved submission |
| `SubmissionEventPublisher` | `void publish(SubmissionDomainEvent event)` | register, approve | records what it was handed |
| `SubmissionIdGenerator` | `SubmissionId nextSubmissionId()`; `UUID nextEventId()` | register (both), approve (`nextEventId`) | returns fixed values |

The read port's input is `record SubmissionSearchCriteria(UUID tenantId, @Nullable String
submissionNumber, @Nullable BigDecimal insuredLimitFrom, @Nullable BigDecimal insuredLimitTo,
@Nullable Instant registeredFrom, @Nullable Instant registeredTo)`.

Also replaceable:

- **`java.time.Clock`**, a constructor argument of both write use cases and a bean in the Spring
  context.
- **`AccessPolicy`**, which a test may implement and pass to `Permission.registration` or
  `Permission.approval`.
- **The caller identity at the web edge, `ActorContextResolver`.** It is a package-private class in
  `submission.adapters.web`, its method `Optional<AuthenticatedCaller> resolve()` is package-private,
  and in production it always returns `Optional.empty()`, so every request answers `401`. As
  implemented, the only way to replace it is a subclass declared in package
  `com.gs5.underwriting.submission.adapters.web`. An HTTP test therefore lives in that package. It
  builds a resource through its package-private constructor:
  `SubmissionCommandResource(RegisterSubmissionUseCase, ActorContextResolver, Permission)`,
  `SubmissionQueryResource(SearchSubmissionsUseCase, ActorContextResolver)` or
  `SubmissionApprovalResource(ApproveSubmissionUseCase, ActorContextResolver, Permission)`, or it
  overrides the resolver bean in a Spring context. See gap G1.

In a Spring context, a test may override by interface type the beans `SubmissionEventPublisher`,
`SubmissionIdGenerator` and `Clock`, and, where a test needs it, the three repository ports.

### TC6. What an `*IT` may read

The schema is `V1` to `V3` as committed. There is no `V4`.

| Object | Columns an IT may read | Constraints an IT may rely on |
|---|---|---|
| `submissions` | `id`, `tenant_id`, `submission_number`, `insured_limit`, `registered_by`, `registered_at`, `version` | `uq_submissions_tenant_submission_number` (BR-06, BR-08, BR-09); `ck_submissions_submission_number_not_empty` (BR-04); `ck_submissions_submission_number_no_control_chars` (BR-29; U+0000 is refused by the type itself); `ck_submissions_insured_limit_positive` (BR-11); `ck_submissions_insured_limit_scale` (BR-12); `uq_submissions_id_tenant` |
| `submission_approvals` | `submission_id`, `tenant_id`, `approved_by`, `approved_at`, `version` | primary key on `submission_id` (BR-36, BR-39); `fk_submission_approvals_submission`, `(submission_id, tenant_id)` referencing `submissions (id, tenant_id)`, with no cascade |
| `outbox_events` | `id`, `tenant_id`, `aggregate_id`, `topic`, `event_type`, `payload_json`, `created_at`, `processed_at`, `version` | **The submission module writes nothing here.** Events are published in-process through Spring's `ApplicationEventPublisher` (§12.1, still deferred), so an IT must not expect a row per event |
| `inbox_events` | `event_id`, `topic`, `processed_at` | not used by this module |
| `submission_current` (view) | — | **does not exist.** §20.3 and §20.4 specify it, and it is not implemented (D19). An IT must not reference it |

An IT may inject the three use case beans (`RegisterSubmissionUseCase`, `SearchSubmissionsUseCase`,
`ApproveSubmissionUseCase`) and the five ports by interface type. It may observe the published events
as Spring application events of the two `SubmissionDomainEvent` record types. Fixture rows are created
through the use cases or the port beans. A direct `INSERT` into the two tables is allowed only to test
the database's own constraints (§19.12), and names only the columns listed above.

### TC7. Not in the contract

The developer may rename, restructure or delete the following. No test may depend on any of it.

- **The aggregate** `Submission` (package-private) and all of its members: `register`, `reconstitute`,
  `toSnapshot`, `registeredEvent`, `id`, `isRegisteredBy`.
- **Value-object internals:** the private fields and constructors of `SubmissionNumber` and
  `InsuredLimit`, the exact text of every `toString()` beyond the masking property of TC1, and
  `RegisterSubmissionCommand.asRegistrationRefusal` (package-private).
- **Persistence:** `SubmissionJdbcEntity`, `SubmissionApprovalJdbcEntity`, `SubmissionPersistenceMapper`,
  `SubmissionSummaryJdbcProjection`, the interfaces `SubmissionCommandJdbcRepository`,
  `SubmissionQueryJdbcRepository` and `SubmissionApprovalJdbcRepository` with their method names and
  SQL text, and the classes `SubmissionCommandSqlAdapter`, `SubmissionQuerySqlAdapter` and
  `SubmissionApprovalSqlAdapter`. This includes the public method
  `SubmissionCommandSqlAdapter.findOwn(UUID, UUID)`, which is not on the port and exists only for
  round-trip checks: an IT that needs the stored row reads `submissions`. The index names are not
  contract either.
- **Web internals:** `ApproveRefusal`, the private `RawSearchRequest`, the private fold and helper
  methods, and the Java type names `RegisterSubmissionRequest`, `RegisteredSubmissionResponse` and
  `ApprovedSubmissionResponse`. Their JSON shape is contract, and their names are not. The wording of
  `detail`, except for the approval `404`. The `instance` of register and search problems.
- **Wiring:** `SubmissionConfiguration`, its bean names (`approvalPermission`, `registrationPermission`,
  `submissionAccessPolicy`, ...), where the gates are constructed, and `SpringSubmissionEventPublisher`.
- **Call counts and order on the id generator,** beyond this: an event's `eventId` is a value that
  `nextEventId()` returned.

### TC8. Gaps in the contract

- **G1. No public seam for the caller identity.** `ActorContextResolver` is package-private and is the
  only way an HTTP request gets an identified caller. A flow test in a package of its own, as
  `test-designer.agent.md` asks, can drive every use case and the access gate through public types, but
  it cannot drive an HTTP endpoint with an identified caller. Closing this needs a design decision on a
  public identity port. That decision is open here and is not made by this section.
- **G2. The `Location` of `201` names a path with no `GET` handler.** A test may assert the header's
  value, and must not follow it.
- **G3. The JSON format of `Instant` fields** is the application's Jackson default, and nothing in
  `src/main` pins it.

### Divergences from design

**Status of §19.11, checked against the code at HEAD.**

| # | Still holds? | Evidence in `src/main` |
|---|---|---|
| D1 | no | `approve(@PathVariable String number)`; the gate admits first, then `ApproveSubmissionCommand.parse(number)` runs |
| D2 | no | no UUID is read from the path; the number goes through `SubmissionNumber.parse` |
| D3 | no | `Submission.isRegisteredBy(actor)` runs after the lookup and before `approve`. The method is named differently from §19.4's `approvableBy`, which is not contract |
| D4 | no | three variants; `403` with `approver-registered-submission` |
| D5 | no | `SubmissionNotFound()` is fieldless |
| D6 | no | constant `detail`, and constant `instance` `/api/submissions/approval` |
| D7 | no | `idGenerator.nextEventId()` |
| D8 | no, in substance | `SubmissionState` is on the view, filled by a `LEFT JOIN` in `findPage`; `countMatching` has no join. It reads the tables, not `submission_current` (see D19) |
| D9 | in a changed form | V3 at HEAD already holds the §19.8 target: composite key, `uq_submissions_id_tenant`, no cascade, no redundant index. It got there by an in-place edit (`44bc3de`; `5bf01b3` had the single-column cascading key and the redundant index), which §20.4 and §20.6 withdraw in favour of a restore plus V4. Carried by D19 |
| D10 | no, for approval | `ApproveRefusal` and one switch. Registration still has two switches (E4) |
| D11 | no | `isApproved` does not exist |
| D12 | no | `reconstitute` re-parses and returns `Optional<Submission>` |
| D13 | resolved as option 1 | `canDecide()` is kept and used for both operations, as §13 records. §16.5 still says it is deleted, which is stale text |
| D14 | accepted | `AccessPolicy` exists |
| D15 | decision | about tests, not `src/main`; not checked here |
| D16 | partly | `findPage` still selects `s.id AS submission_id`, and `SubmissionSummaryJdbcProjection` still has `UUID submissionId`. It is not mapped into `SubmissionSummaryView`, so no response carries it |
| D17, D18, D20 | design text only | the code is consistent: the number in the `201` body and in the events; lookup by `findOwnByNumber` |
| D19 | yes | no `V4`, no `submission_current`, no append-only triggers, and no `submission_repositories_issue_only_inserts_and_selects` rule. V2 and V3 hold HEAD content, not the reviewed content |

**Divergences that hold. The contract above is written as the code does it.**

| Item | Design says | Code does | BR affected |
|---|---|---|---|
| D16 | the search does not select or map the internal id (§20.3) | `s.id AS submission_id` is selected and held in the projection, and dropped before the view | BR-47 (no response leaks it today) |
| D19 | V2 and V3 restored to the reviewed content; V4 adds the rename, the composite key, the append-only triggers and the `submission_current` view; search reads the view (§20.3, §20.4) | V2 and V3 edited in place; no V4; search and count read `submissions` and `submission_approvals` directly | BR-44, BR-45, BR-46 |
| E1 | `execute` returns `Result<SubmissionId, RegisterSubmissionError.NumberAlreadyUsed>` (§3.3) | `Result<SubmissionId, RegisterSubmissionError>`, widened with `.mapError(error -> error)` | BR-06, BR-09 (the type no longer proves that only the duplicate refusal can come out) |
| E2 | `ActorContext` is a final class of `tenantId` and `actorId` with no role; `ActorRole` lives in `adapters/access` with no methods (§16.5) | `record ActorContext(UUID tenantId, String actorId, ActorRole role)`; `ActorRole` is in `shared.kernel` with `canDecide()` | BR-16, BR-02, BR-33 (a role check in `domain/` is not a build failure) |
| E3 | gates are built only in `adapters/config`; raw use cases are not beans; ArchUnit A1 to A3 (§13, §16.4, §19.10 rule D) | the use cases are beans; each resource injects its raw use case and calls `new OperationGate<>(...)`; A1 to A3 are absent from `ArchitectureRulesTest` | BR-01, BR-02, BR-33 (the bypass protection is convention) |
| E4 | one `RegisterRefusal` union folded by one switch with no `default` (§3.2, §13.1) | two switches, `refused(RegisterSubmissionError)` and `refusedAccess(AccessRefusal)`; no `RegisterRefusal` type | BR-01, BR-02, BR-04 to BR-06, BR-09 to BR-12, BR-29 (the nine-leaf completeness at one switch) |
| E5 | register `403` has problem type `role-may-not-register` (§3.2, §13.2) | `permission-denied` | BR-02 |
| E6 | `SubmissionSearchCriteria.number` is `@Nullable SubmissionNumber`; the `Narrowing` component is `number` (§5.1, §5.4) | `@Nullable String submissionNumber`; the component is `reference` | BR-30, BR-32 (store safety rests on the use case, not on the type) |
| E7 | `approve(SubmissionApproval)`, error narrowed to `SubmissionAlreadyApproved`, with a masking `SubmissionApproval` record (§19.5, §19.11) | `approve(UUID tenantId, SubmissionId submissionId, ActorContext actor, Instant approvedAt)` returning `Result<SubmissionId, ApproveSubmissionError>`; no `SubmissionApproval` type | BR-36, BR-39 (an adapter could return any refusal); BR-42 (masking relies on `ActorContext.toString`) |
| E8 | approval `execute` returns `Result<SubmissionId, ...>` (§19.4) | `Result<SubmissionNumber, ApproveSubmissionError>` | BR-47 (the code is the consistent one, and §19.4 is stale) |
| E9 | the order is identify, then permission, then parse; framework conversion is a known residual (§16.3, Q1) | the JSON body and the typed query parameters are converted by Spring before `admit`, so unreadable input is a `400` before `401` or `403` | BR-01, BR-02; search's single refusal reason |

---

Design written to `docs/adr/submission-registration-design.md`. Open a new chat and call
`@test-designer` to turn it into contracts and failing tests.
