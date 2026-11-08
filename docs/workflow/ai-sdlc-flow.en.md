# AI SDLC flow — description (English)

The process runs in eight stages. Each stage is done by a separate role in a fresh context, so
no role inherits the previous one's reasoning. A gate is a condition the orchestrator checks
before work moves on.

- **Stage 0, orchestrator.** Opens and maintains the state board in `docs/workflow/`. It routes
  work and checks every gate; it writes no code and no tests.
- **Stage 1, business analyst.** Writes the specification in `docs/specs/` as numbered business
  rules (`BR-xx`). Any business number it cannot trace to the product owner must be marked
  `ASSUMPTION`. **Gate:** no unresolved `ASSUMPTION` marker.
- **Stage 2, architect.** Writes the technical design in `docs/adr/`. **Gate:** the design covers
  every command in the specification.
- **Stage 3, test designer (RED).** Lists every business-flow scenario in `docs/tests/`, each
  traced to a `BR-xx`, with no class names. Then writes tests that drive each flow through the
  design's Test contract only (use case entry point, HTTP endpoint, replaceable ports), plus the
  port fakes, in `src/test/` only. Tests are found by the scenario id opening their display name,
  not by class or method, so they impose no code structure. **Gate:** every rule has a scenario,
  every NEW scenario has a test, `src/main/` unchanged. The tests do not compile yet.
- **Stage 4a, developer (SKELETON).** Writes the design's Test contract with
  `return null;` bodies. Never committed. **Gate:** every NEW test **fails**. A test that passes
  against the skeleton checks nothing and goes back to stage 3.
- **Stage 4b, developer (GREEN).** Implements `src/main/` until the tests pass. It may not edit
  tests. **Gate:** `verify` succeeds and `src/test/` is unchanged, checked by
  `scripts/check-write-lock.sh green`.
- **Stage 5, QA explorer.** Writes HTTP scenarios and adversarial tests. It may not patch
  production code. **Gate:** `src/main/` is unchanged.
- **Stage 6, code and security reviewers.** Read-only. Each returns `[APPROVED]` or
  `[CHANGES REQUIRED]`.

When a reviewer returns `CHANGES REQUIRED`, the work goes back to **stage 3**. A failing test is
written for each finding first, and only then is the code changed. A test that does not fail
before the fix does not prove the fix.

Write-lock rules are enforced by `scripts/check-write-lock.sh`, which compares against the last
reviewed commit, not against whatever `HEAD` happens to be.
