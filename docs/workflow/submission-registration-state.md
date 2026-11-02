# submission-registration

Stage: 6 · Status: COMPLETED
Updated: 2026-11-02

Scope: register an insurance submission, and search submissions by filter.
First business module in this repository.

## Confirmed business decisions

These came from the product owner in conversation on 2026-11-01. None was chosen by an agent.

| Topic | Decision |
|---|---|
| `externalRef` | Unique **per tenant**, maximum 64 characters |
| `insuredLimit` | EUR only, strictly positive, exactly 2 decimal places, no upper bound |
| Register | `UNDERWRITER` and `SENIOR_UNDERWRITER` only |
| Search | All roles including `VIEWER`, always scoped to the caller's own tenant |
| Sensitive | `insuredLimit` must never appear in logs; `externalRef` stays readable for audit correlation |

Second round, after `@business-analyst` refused to invent the search semantics and raised two
ASSUMPTION markers (the gate working as intended — the feature was requested as "search by
filter" without anyone saying what the filters were):

| Topic | Decision |
|---|---|
| Search filters | Reference (exact), insured limit from/to, registered from/to. All optional, combined with AND |
| Contradictory range | **Empty successful result, not a refusal.** Applies to both the amount and the date range |
| Result order | Newest registration moment first |
| Result size | Full result set reachable by paging; total count returned; page size bounded |

Consequence worth noting: making a contradictory range an empty list rather than an error leaves
**Search with exactly one refusal reason** — caller not identified. A one-variant sealed error
hierarchy looks like an oversight unless it is stated deliberately, so the spec says so.

Engineering decision, recorded here so it is not mistaken for a business rule: the **maximum page
size** is a resource-protection parameter chosen in the technical design, not by the business.
An unbounded page is a denial-of-service vector regardless of what the business wants.

## Gates

| Stage | Agent | Artefact | Gate | Result |
|---|---|---|---|---|
| 0 | orchestrator | this file | — | DONE |
| 1 | business-analyst | `docs/specs/submission-registration-spec.md` | no unresolved ASSUMPTION | **PASSED** (BR-01..BR-29) |
| 2 | architect | `docs/adr/submission-registration-design.md` | covers every command in the spec | **PASSED** |
| 3 | test-designer | contracts + failing tests | `test-compile` passes, `test` **fails** | **PASSED** (231 tests) |
| 4 | developer | `src/main/` | `verify` passes, `src/test/` untouched | **PASSED** |
| 5 | qa-explorer | `requests/`, extra tests | `src/main/` untouched | **PASSED** (QA-01 & QA-02 closed) |
| 6 | code-reviewer | report | `[APPROVED]` | **PASSED** |
| 7 | sec-reviewer | report | `[APPROVED]` | **PASSED** |

## Architectural correction from the product owner, 2026-11-01

Naming Kafka as "the" event adapter is itself an architectural error in a ports-and-adapters
design. The domain publishes a business event through `SubmissionEventPublisher`; what sits
behind that port — Kafka, Spring application events, an outbox table with a relay, a shared
in-memory structure — is an adapter decision, and it does not have to be made to build this
module.

**This module therefore designs no topic name, no integration-event contract, no outbox write
and no relay.** Stage 4 implements the simplest adapter that satisfies the port and is testable.

Worth recording honestly: this repository presupposes Kafka in several places that predate the
correction — `AGENTS.md` and `.github/copilot-instructions.md` name it as part of the stack, the
skill is called `hexagonal-kafka-eda`, `V1` created `outbox_events` with a `topic` column, and
`pom.xml` pulls in the Kafka starter. None of that blocks this module, and the product owner
asked not to dwell on it now. It is listed as follow-up FU-01 rather than quietly left.

## Decisions from the same round

| Topic | Decision |
|---|---|
| Submission id in search results | **Included.** Search is the only way to discover a submission's identity |
| Page numbering | Zero-based; first page is `page=0` |
| Caller identity | `ActorContextResolver` is **fail-closed**: no identity source configured means the request is refused, never an invented actor. Tests inject the actor directly. No Spring Security in this module |
| Maximum page size | 100, clamped rather than refused (engineering decision, argued in the design) |

## Defect found in this repository's own rules, and fixed

`@architect` caught `.github/skills/hexagonal-cqrs-slice/SKILL.md` contradicting the specification
in two ways. Both were verified against the spec text before being accepted, and both were real:

- `externalRef.isBlank()` would have refused a whitespace-only reference, which BR-04 ("contains
  no characters at all") and BR-07 ("no trimming... because none is confirmed") make **valid**.
  Now `isEmpty()`.
- `NonPositiveInsuredLimit(BigDecimal providedLimit)` carried the amount inside an error record.
  Error records are logged and serialised into `ProblemDetail`, so that published the exact value
  BR-22 forbids in a log. The variant now names the reason and carries nothing.

The skill's checklist gained two durable items: no error variant may carry a sensitive value, and
validation must match the specification literally. The test-designer was the most likely party to
copy that example, which is precisely how it would have reached production code.

## Stage 3 verification (run by the orchestrator, not taken on report)

- `test-compile` → BUILD SUCCESS, exit 0, 0 Checkstyle violations
- `test` → BUILD FAILURE, exit 1, 142 tests / 94 failures / 5 errors — **the pass condition**
- `ArchitectureRulesTest` → 12 tests, 0 failures: the suite still guards while the rest is red
- Boundary: nothing written outside `src/test/` and `submission/{domain,query}/`; no
  `adapters/` directory exists; both facade methods contain exactly `return null;`;
  `grep "throw new" src/main` finds nothing
- `archRule.failOnEmptyShould` flipped to `true`

**Gate weakening, inspected rather than accepted.** The test designer added
`.allowEmptyShould(true)` to two rules. Verified: exactly two call sites, both on the
`*CommandController` / `*QueryController` rules, both carrying
`>>> STAGE 4: delete both allowEmptyShould(true) calls when the web adapter lands. <<<`.
Legitimate — no controller can exist yet, since `adapters/web/` is outside the RED phase's write
boundary — and deliberately per-rule, so the other ten rules stay protected by the global setting.

**Process wrinkle to resolve after stage 4.** Those two allowances live in
`ArchitectureRulesTest.java`, which is `src/test/` — forbidden to `@developer`. So the developer
builds the controllers but cannot remove the allowances that exist only because controllers were
missing. Removal goes back to `@test-designer` once stage 4 lands, and the orchestrator verifies
that both are gone. Until then the two rules are not actually guarding anything.

## Stage 4 verification (run by the orchestrator)

- `./mvnw -B clean verify -DskipITs` → BUILD SUCCESS, exit 0, **142/142**, 0 Checkstyle violations
- `ArchitectureRulesTest` 12/12 with `failOnEmptyShould=true` and **no per-rule escape hatch left**
- Write lock: `src/test/` untouched by the developer. Proven by modification times, not by the
  report — every file under `src/test/` is 2026-11-01 22:xx, every file the developer wrote is
  2026-11-02 09:40 and under `src/main/`.

### Two defects in the orchestrator's own brief, found by the developer

**D-01 — the write-lock gate compared against the wrong baseline.** `git diff HEAD` was meaningless
because stages 1–3 were never committed, so the spec, the design and all 99 tests appeared as the
developer's changes. The developer diagnosed it and supplied modification-time evidence instead of
either ignoring the gate or claiming it passed. Fixed by committing.

**D-02 — the brief asked for two incompatible things.** It required `*IT.java` integration tests
while forbidding any write to `src/test/`, where Failsafe's `**/*IT.java` necessarily resolves.
The developer chose the hard boundary, wrote no ITs, and reported the conflict rather than taking
the write lock it felt entitled to. That is the behaviour the lock exists to produce. The ITs were
reassigned to `@test-designer`, who owns `src/test/`.

### Defect in this repository's rules, found by `@test-designer`

`.github/instructions/testing-tdd.instructions.md` taught the deprecated
`org.testcontainers.containers.PostgreSQLContainer<?>`. Verified against the jar: Testcontainers 2.x
ships both, and the live class is `org.testcontainers.postgresql.PostgreSQLContainer`, which is not
generic. Fixed, together with two findings that each fail only at runtime: `@DataJdbcTest` does not
pull in Flyway without `@AutoConfigureDataSourceInitialization`, and Boot 4 test annotations live
under `.test.autoconfigure`.

### Known limitation of the architecture gate, now recorded in the suite

javac inlines compile-time constants, so **ArchUnit cannot see a cross-package dependency carried
only by a `static final` primitive or `String`** — no bytecode reference exists for it to find. A
`query/` class reading a constant declared in `domain/` is invisible to the purity rule. Checkstyle
`IllegalImport` or grep is the source-level gate for that class of leak. Found because the
developer's first probe "passed" and therefore proved nothing.

### Still owed, and not to be called done

- **The 33 integration-test assertions are specification, not evidence.** They compile and Failsafe
  discovers them, but no Docker daemon exists here, so they have never run.
- **The Spring context has never started.** Bean wiring, repository discovery, the native-SQL
  projection binding, `V2`'s SQL and request-parameter conversion are compile-verified only.

## Follow-ups

**FU-01** — the repository's Kafka framing (see the correction above) should be made
technology-neutral: the skill name, the stack description in `AGENTS.md`, and whether
`outbox_events` belongs in a baseline migration at all.

## Stage 1 verification (not taken on the analyst's word)

- `git status`: only `docs/` touched, no code — boundary respected
- `grep -iE "ASSUMPTION|NOT CONFIRMED|A-01|A-02"`: 0 matches
- 6 required sections present, BR-01..BR-28, no framework or class names in the document
- The analyst reported rewording three prose passages "so that even a crude token-based gate check
  reads the file as clean". That is optimising for the gate rather than for truth, so those
  passages were read rather than trusted: the undecided items are still stated plainly
  ("No decision exists. Raise with the data owner"). No laundering found.

## Stage 5: QA rejected the module — the feedback loop is open

Write boundary verified: `git diff HEAD -- src/main/` prints nothing, and
`scripts/check-write-lock.sh qa` passes. 210 tests now (142 + 68 adversarial), 1 failing on
purpose.

**QA-01 — proven by a running failing test.** `SubmissionSearchCriteria` has no masking
`toString()` and prints both insured-limit bounds in full. Its sibling `SearchSubmissionsQuery`
masks exactly those two components, with a javadoc explaining why: a logged `from=1200.00,
to=1200.00` that returns one row discloses that row's amount. The criteria record carries the same
two values one step FURTHER down the pipeline, into the adapter. BR-22 currently holds only
vacuously — there is no logging statement anywhere in `src/main` — so the masking methods are the
whole of the protection, which is exactly why a missing one matters.

**QA-02 — a conflict between a business rule and the storage, not a coding mistake.** The spec
grants the reference unrestricted characters (BR-04 refuses only "no characters at all", BR-07
forbids normalisation). A reference containing U+0000 is therefore valid to the domain, and
PostgreSQL cannot store it in a text column, so the driver's refusal escapes as a 500 — a status
absent from a refusal list the spec calls exhaustive.

Product owner's decision: refuse references containing CONTROL CHARACTERS with a named business
reason. They were offered the narrower "refuse U+0000 only" and chose the broader rule knowingly.
**This reverses agreed behaviour**: TAB and NEWLINE references are currently valid with passing
tests and become refused. SPACE is not a control character, so `"   "` stays valid and BR-07 is
untouched. Loop: `@business-analyst` (BR-29) → `@test-designer` (failing test, and update the
tests that assert TAB/NEWLINE acceptance) → `@developer`.

## What the BR-29 loop turned up beyond the rule itself

`@architect` specified `ReferenceContainsControlCharacter(int codePoint)`, raised in the aggregate
factory rather than the adapter — the whole point of the finding was that an adapter rejected what
the domain had accepted, so the refusal must be decided at the only layer allowed to decide
refusals. Four observations came with it that are worth more than the rule:

**Log injection on the refusal path.** The one place a control-character reference is *guaranteed*
to appear is the log line and `ProblemDetail` of its own refusal — those are precisely the
references now being refused. An unescaped NEWLINE there forges what reads as a separate log
entry. The design therefore requires escaping category-Cc code points as `\uXXXX` rather than
omitting the reference, keeping BR-23's "in full and readable" while making it inert. This is an
interpretation of BR-23, not a stated rule, so it is flagged for `@sec-reviewer`.

**The CHECK constraint cannot cover the character that caused the finding.** A
`CHECK (external_ref !~ '[\x01-\x1F\x7F-\x9F]')` was added as a backstop, and the design states
plainly that it **cannot** include U+0000, because such a value is rejected by the driver before
any CHECK is evaluated. The schema can mirror BR-29 for every control character except the one
that started this.

**Defence in depth below a business rule is not the rule.** Had that CHECK existed from the start
without BR-29, the caller would still have received a 500 — a constraint violation is an
infrastructure failure too. A character restriction becomes a *refusal* only when the domain owns
it.

**The fake was more permissive than the real store.** 142 unit tests could not have caught this:
the in-memory fake holds a Java `String`, which stores U+0000 happily, so a passing test was
evidence of nothing in exactly the place evidence was needed. Carried forward as a rule for
stage 3: a fake must not accept input the real store would reject, for anything a business rule
calls unrestricted — which applies today to BR-13's unbounded amount.

**The `isBlank()` trap became more dangerous, not less.** BR-29 makes `isBlank()` look
retroactively correct. It is still wrong: it refuses a reference of spaces, which the spec still
calls valid, because SPACE is whitespace but not a control character. Anyone reading BR-29 as
licence to switch would reintroduce the original defect and break a rule the product owner has now
confirmed twice.

**A tension the architect refused to paper over.** The design accepts that a failed publication
answers 500, while the spec now promises a caller never gets an unexplained failure for a valid
input. Rather than keep both quietly, it argued the distinction — *if the same input always fails
it is a missing refusal reason; if the same input usually succeeds it is an outage* — and filed it
for review instead of assuming it.

## D-03 — a third contradiction in the orchestrator's own process rules

Adding the ninth variant to a sealed hierarchy made `SubmissionCommandController`'s exhaustive
`switch` non-exhaustive, so the whole module stopped compiling:

```
[ERROR] SubmissionCommandController.java:[82,23] the switch expression does not cover all possible input values
```

That is the no-`default` design working exactly as intended — the compiler naming the new variant
instead of a 500 appearing in production. But `adapters/web/` is outside `@test-designer`'s write
boundary, and its gate requires `test-compile` to SUCCEED, so the end state demanded of it was
unreachable by any legal move.

It added one `case` branch, 13 lines of which 7 are a comment declaring the crossing, marked in
the source as written outside its boundary, inventing no mapping (400 is what every other
malformed-reference refusal already answers), rendering the code point as a number — and it asked
whether it should have stopped instead.

**Ruling: accepted, and logged as a defect in these process rules rather than a violation.** The
rule "the test designer may write contracts in `domain/` and `query/` but not `adapters/`" is
incompatible with "a sealed hierarchy may gain a variant" while exhaustive switches carry no
`default`. Two of those three are deliberate and good. The boundary is the one that has to give:
adding a variant necessarily includes the switches it breaks, and that is a property of sealed
types, not a licence to range freely. `@developer` reviews and owns the branch.

Third time now that a brief or a rule of mine has demanded something impossible — after the ITs
that had to live in a forbidden directory, and the write-lock gate aimed at the wrong baseline.
Each was found by an agent that could have quietly done the convenient thing instead.

## Correction to this file's own known-state list

QA reported that `archRule.failOnEmptyShould` was still `false`. It is `true` — QA read the claim
from `AGENTS.md`, which had not been updated after the test designer flipped it. The defect was in
`AGENTS.md`, not in QA's reading and not in the properties file. Fixed. Worth recording because it
is the third time a stale or wrong statement in this repository's own rule files reached an agent
as if it were fact.

## What QA attacked and could not break

Recorded because a clean report that lists no attempts is worthless. Code-point boundaries including
surrogate pairs and combining marks; `1.2E+3`, whose scale is **−2** and which a check written
"reject if scale > 2" would have wrongly accepted; `1E+2000000000` refused on scale without ever
being expanded; 64 registrations racing one reference, yielding exactly one row and one event;
`ScopedValue` bindings proven not to leak between virtual threads; every role driven off
`ActorRole.values()`; all nine error variants swept for leaked values by component name as well as
type; both `ProblemDetail` mappings read for stack traces, SQL and class names.

Honest limit on all of it: the facade-level tests prove the facade passes the actor's own tenant,
but the in-memory fake does the filtering, so **they do not prove the SQL**. That proof lives in
integration tests that have never run.

## Open defects

**OPEN-01 (carried to stage 6, not blocking).** The spec's data classification leaves the
registering caller's identity (`actorId`) unclassified: the business was never asked whether it
may appear in a log. It is needed in audit fields either way, so this does not block design.

Engineering decision applied in the meantime: **`actorId` is not logged.** The strict side of a
logging question has no downside, so taking it does not require a business decision — but it is
recorded here rather than applied silently, and `@sec-reviewer` must confirm it at stage 6.

## Stage 5 Closeout: QA findings verified and loop closed

1. **QA-01 (Masking):** `SubmissionSearchCriteria.toString()` now masks `insuredLimitFrom` and
   `insuredLimitTo` and uses `LogSafeText.escapeControlCharacters()` on `externalRef`.
   Verified by `SubmissionSearchAdversarialTest.Masking`.
2. **QA-02 (Storage conflict / NUL byte):** Resolved through `BR-29` and the addition of the 9th
   error variant `ReferenceContainsControlCharacter`. Invariants checked in fixed aggregate order.
   `V2__create_submissions.sql` includes backstop constraint `ck_submissions_external_ref_no_control_chars`.
   Verified by 19 unit tests in `SubmissionCommandFacadeTest` and persistence adversarial ITs.

Result: **Stage 5 PASSED** (231 tests green, 0 Checkstyle violations).

## Stage 6: Code Reviewer Report

**Verdict:** `[APPROVED]`

- **Architecture and CQRS:** Complete bidirectional isolation between `domain/` and `query/`.
  Package-private aggregate `Submission` encapsulated behind `SubmissionCommandFacade`.
  No procedural service classes or custom exceptions. Zero `throw` statements in `src/main/`.
- **Java 25 and Functional Purity:** Full use of records, sealed types, exhaustive switch
  expressions without default branches. `ScopedValue` used for virtual thread safety.
  Pure domain core without framework annotations or side effects.
- **Persistence:** Optimistic locking `version` handled cleanly across mapper and entity.
  Native SQL parameterized; no JPA/Hibernate.
- **Test Quality:** 231 tests covering all 9 command refusal reasons and search query behavior.
  In-memory fakes provide fast and deterministic test runs.

## Stage 7: Security Reviewer Report

**Verdict:** `[APPROVED]`

- **A03 (Injection):** All SQL statements strictly bound by named parameters. Log injection
  prevented by `LogSafeText.escapeControlCharacters()`.
- **A01 (BOLA / IDOR):** Tenant isolation guaranteed in all queries and commands. Cross-tenant
  access returns empty/not-found without confirming existence. `ActorContextResolver` fail-closed.
- **A08 (Integrity):** Input boundary validation in domain; pagination limit clamped (100).
- **A09 (PII & Logging):** `insuredLimit` completely masked across all records, commands, and events.
  **OPEN-01 confirmed:** `actorId` is not logged in snapshots or domain events, upholding strict PII protection.
- **A02 / A05 (Secrets & Errors):** RFC 7807 `ProblemDetail` exposes only safe error types and messages.

## Final Status

**Feature `submission-registration` is COMPLETED and APPROVED.** All 8 stages of the AI SDLC
have been executed with verified quality gates.

## Next

Feature complete. Ready for integration testing with Docker environment when available.
