# submission-registration

Stage: 1 · Status: IN_PROGRESS
Updated: 2026-11-01

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
| 1 | business-analyst | `docs/specs/submission-registration-spec.md` | no unresolved ASSUMPTION | **PASSED** |
| 2 | architect | `docs/adr/submission-registration-design.md` | covers every command in the spec | **PASSED** |
| 3 | test-designer | contracts + failing tests | `test-compile` passes, `test` **fails** | **PASSED** |
| 4 | developer | `src/main/` | `verify` passes, `src/test/` untouched | **PASSED** |
| 5 | qa-explorer | `requests/`, extra tests | `src/main/` untouched | pending |
| 6 | code-reviewer + sec-reviewer | reports | both `[APPROVED]` | pending |

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

## Open defects

**OPEN-01 (carried to stage 6, not blocking).** The spec's data classification leaves the
registering caller's identity (`actorId`) unclassified: the business was never asked whether it
may appear in a log. It is needed in audit fields either way, so this does not block design.

Engineering decision applied in the meantime: **`actorId` is not logged.** The strict side of a
logging question has no downside, so taking it does not require a business decision — but it is
recorded here rather than applied silently, and `@sec-reviewer` must confirm it at stage 6.

## Execution note

Each stage runs as a separate subagent with its own fresh context, so no stage can see the
previous stage's reasoning — only the artefact it left on disk. The orchestrator runs
`./mvnw` and `scripts/check-write-lock.sh` after each stage rather than accepting a claim.

## Next

Stage 1: `@business-analyst` writes the specification from the confirmed decisions above.
