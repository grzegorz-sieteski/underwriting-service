#!/usr/bin/env bash
#
# Phase boundary check for the AI SDLC workflow.
#
# The write locks described in .github/agents/*.agent.md cannot be enforced by an IDE: nothing
# stops an agent with edit access from touching src/test/ when it was told not to. This script
# is what turns those rules into something checkable. Run it after every stage.
#
#   ./scripts/check-write-lock.sh pin                 # orchestrator: record the verified commit
#   ./scripts/check-write-lock.sh red [<feature>]     # test-designer: catalogue + tests, src/main untouched
#   ./scripts/check-write-lock.sh skeleton <feature>  # developer 4a: declarations only, every NEW test fails
#   ./scripts/check-write-lock.sh green               # developer 4b: src/test/ and docs/ untouched
#   ./scripts/check-write-lock.sh qa                  # qa-explorer: src/main/ untouched
#   ./scripts/check-write-lock.sh trace <feature>     # any time: every BR -> scenario -> test
#
# What a phase may not change is measured against a BASELINE, never against HEAD. Against HEAD,
# an agent that commits its forbidden edit makes the diff empty and the gate passes. The
# orchestrator runs `pin` at every hand-over, on the commit it has just verified; the pin lives in
# .git/, outside the tree any agent commits. BASE=<ref> overrides it (CI uses origin/<base>).

set -uo pipefail

PHASE="${1:-}"
PIN_FILE="$(git rev-parse --git-dir 2>/dev/null)/sdlc-baseline"
MVNW="./mvnw"
[ -x "$MVNW" ] || MVNW="mvn"

red()   { printf '\033[31m%s\033[0m\n' "$1"; }
green() { printf '\033[32m%s\033[0m\n' "$1"; }

# Resolve BASE for the phases that compare against it. Fail closed: no pin, no gate.
resolve_base() {
    if [ -z "${BASE:-}" ]; then
        if [ ! -s "$PIN_FILE" ]; then
            red "FAIL: no baseline pinned. The orchestrator runs '$0 pin' on the verified commit"
            red "      before this stage starts. Comparing against HEAD would let a committed"
            red "      forbidden edit pass unseen."
            exit 1
        fi
        BASE="$(cat "$PIN_FILE")"
    fi
    git rev-parse --verify -q "${BASE}^{commit}" > /dev/null \
        || { red "FAIL: baseline $BASE is not a commit"; exit 1; }
    git merge-base --is-ancestor "$BASE" HEAD \
        || { red "FAIL: baseline $BASE is not an ancestor of HEAD; history was rewritten"; exit 1; }
    echo "baseline: $(git log -1 --format='%h %s' "$BASE")"
}

changed_under() {
    git diff --name-only "$BASE" -- "$@" 2>/dev/null
    git ls-files --others --exclude-standard -- "$@" 2>/dev/null
}

FEATURE="${2:-}"

set_feature() {
    FEATURE="$1"
    SPEC="docs/specs/${FEATURE}-spec.md"
    CATALOG="docs/tests/${FEATURE}-scenarios.md"
}

require_feature() {
    [ -n "$FEATURE" ] || { echo "usage: $0 $PHASE <feature>, e.g. $0 $PHASE submission-registration" >&2; exit 2; }
    set_feature "$FEATURE"
}

# Scenario ids of the catalogue rows marked NEW. Tests are found by these ids at the start of
# their display names, never by class or method: the code structure belongs to the developer.
catalog_new_ids() {
    grep -E '^\|\s*SC-[0-9]+\s*\|' "$CATALOG" | grep -E '\|\s*NEW\s*\|\s*$' \
        | grep -oE '^\|\s*SC-[0-9]+' | grep -oE 'SC-[0-9]+' | sort -u
}

# Scenario ids that open a display name anywhere in the test tree.
test_tree_ids() {
    grep -rhoE '(@DisplayName\(|name\s*=\s*)"SC-[0-9]+:' src/test/java 2>/dev/null \
        | grep -oE 'SC-[0-9]+' | sort -u
}

# All scenario ids of the catalogue, and their state column.
catalog_rows() {
    grep -E '^\|\s*SC-[0-9]+\s*\|' "$CATALOG" \
        | sed -E 's/^\|\s*(SC-[0-9]+)\s*\|.*\|\s*([A-Za-z]+)\s*\|\s*$/\1 \2/'
}

# Traceability of one feature: spec -> catalogue -> tests. With "require_new", at least one NEW
# scenario must exist (the RED phase); without it, this is the standing check for a module.
trace_one() {
    set_feature "$1"
    local need_new="${2:-}" rc=0
    echo "-- feature: $FEATURE"
    [ -f "$SPEC" ] || { red "FAIL: specification $SPEC not found"; return 1; }
    [ -f "$CATALOG" ] || { red "FAIL: scenario catalogue $CATALOG not found"; return 1; }

    # Every business rule of the specification is in the catalogue.
    local missing="" br
    for br in $(grep -oE 'BR-[0-9]+' "$SPEC" | sort -u); do
        grep -qE "\b${br}\b" "$CATALOG" || missing="$missing $br"
    done
    if [ -n "$missing" ]; then
        red "FAIL: business rules with no scenario:$missing"
        rc=1
    else
        green "OK: every business rule of the specification has a scenario"
    fi

    # Every NEW or EXISTING scenario opens the display name of at least one test. A GAP row is
    # a scenario nobody has tested yet: it is listed, and it fails the check until it has a test.
    local in_tree id state rows
    in_tree="$(test_tree_ids)"
    rows="$(catalog_rows)"
    if [ -n "$need_new" ] && ! printf '%s\n' "$rows" | grep -q ' NEW$'; then
        red "FAIL: the catalogue has no NEW scenario. RED without a new test proves nothing."
        rc=1
    fi
    while read -r id state; do
        [ -n "$id" ] || continue
        case "$state" in
            NEW|EXISTING)
                printf '%s\n' "$in_tree" | grep -qxF "$id" || {
                    red "FAIL: $id is $state in the catalogue, but no test display name starts with \"$id:\""
                    rc=1
                } ;;
            GAP)
                red "FAIL: $id is a GAP: a scenario with no test yet"
                rc=1 ;;
            *)
                red "FAIL: $id has state '$state'; expected NEW, EXISTING or GAP"
                rc=1 ;;
        esac
    done <<< "$rows"
    [ "$rc" -eq 0 ] && green "OK: every scenario has a test, found by its id"
    return "$rc"
}

# Every scenario id used in a test exists in some catalogue: no untraced test.
no_orphan_ids() {
    local rc=0 id all_ids
    all_ids="$(cat docs/tests/*-scenarios.md 2>/dev/null | grep -oE '^\|\s*SC-[0-9]+' | grep -oE 'SC-[0-9]+' | sort -u)"
    for id in $(test_tree_ids); do
        printf '%s\n' "$all_ids" | grep -qxF "$id" || {
            red "FAIL: a test is labelled $id, but no catalogue has that scenario"
            rc=1
        }
    done
    return "$rc"
}

assert_untouched() {
    local label="$1"; shift
    local files
    files="$(changed_under "$@" | sort -u)"
    if [ -n "$files" ]; then
        red "FAIL: ${label} must not be modified in this phase, but these changed:"
        printf '  %s\n' $files
        return 1
    fi
    green "OK: ${label} untouched"
    return 0
}

case "$PHASE" in
    red|skeleton|green|qa) resolve_base ;;
esac

case "$PHASE" in
    pin)
        if [ -n "$(git status --porcelain)" ]; then
            red "FAIL: the working tree is not clean. Pin a commit, not an uncommitted state."
            exit 1
        fi
        git rev-parse HEAD > "$PIN_FILE"
        green "OK: baseline pinned at $(git log -1 --format='%h %s' HEAD)"
        ;;

    red)
        echo "== RED phase: scenarios and tests only, src/main untouched =="
        rc=0
        assert_untouched "src/main/" src/main || rc=1

        # Without a feature argument (CI), check every catalogue this change touches.
        if [ -n "$FEATURE" ]; then
            features="$FEATURE"
        else
            features="$(changed_under docs/tests | grep -E '^docs/tests/.*-scenarios\.md$' \
                        | sed -E 's#^docs/tests/(.*)-scenarios\.md$#\1#' | sort -u)"
            [ -n "$features" ] || { red "FAIL: no scenario catalogue changed under docs/tests/"; exit 1; }
        fi
        for f in $features; do trace_one "$f" require_new || rc=1; done
        no_orphan_ids || rc=1
        [ "$rc" -eq 0 ] || exit 1
        green "OK: RED phase gate passed. Compilation is not required here; next gate: skeleton"
        ;;

    trace)
        echo "== TRACE: specification -> scenario catalogue -> tests =="
        require_feature
        rc=0
        trace_one "$FEATURE" || rc=1
        no_orphan_ids || rc=1
        [ "$rc" -eq 0 ] || exit 1
        green "OK: every business rule is traced to a scenario and every scenario to a test"
        ;;

    skeleton)
        echo "== SKELETON (stage 4a): declarations only, every NEW test must fail =="
        require_feature
        rc=0
        assert_untouched "src/test/" src/test || rc=1
        assert_untouched "docs/" docs || rc=1
        assert_untouched "Flyway migrations" src/main/resources/db/migration || rc=1

        # Added production lines may declare, never decide. Anything that computes a result is
        # logic, and logic belongs to stage 4b, after this gate.
        logic='\bif\s*\(|\bfor\s*\(|\bwhile\s*\(|\bswitch\b|\bcase\b|\s\?\s.*\s:\s|Result\.(success|failure)|\.(map|flatMap|fold|filter|orElse\w*)\(|@Query|\b(SELECT|INSERT|UPDATE|DELETE)\b|\breturn\s+(?!null\s*;)'
        added="$( { git diff "$BASE" -- src/main | grep -E '^\+[^+]' | cut -c2-
                    for f in $(git ls-files --others --exclude-standard -- src/main); do cat "$f"; done; } \
                  | grep -vE '^\s*(\*|//|/\*)' )"
        # A grep that errors (exit 2) must stop the gate, not read as "no logic found".
        hits="$(printf '%s\n' "$added" | LC_ALL=C.UTF-8 grep -P "$logic")"
        grep_rc=$?
        if [ "$grep_rc" -gt 1 ]; then
            red "FAIL: the logic check itself could not run (grep exit $grep_rc)"
            exit 1
        fi
        if [ -n "$hits" ]; then
            red "FAIL: the skeleton contains logic. Bodies must be 'return null;' or empty:"
            printf '%s\n' "$hits" | sed 's/^/    /' | head -20
            rc=1
        fi
        [ "$rc" -eq 0 ] || exit 1
        green "OK: skeleton declares only"

        SKELETON_LOG="$(mktemp)"
        rm -rf target/skeleton-reports
        echo "== running the suite with NullAway off (skeleton bodies return null on purpose) =="
        $MVNW -B -q clean test -Dnullaway.severity=OFF > "$SKELETON_LOG" 2>&1
        # Classes compiled with NullAway off must not survive into a later incremental build:
        # an unchanged skeleton file would never be recompiled and its `return null;` would pass.
        mkdir -p target/skeleton-reports
        cp target/surefire-reports/TEST-*.xml target/skeleton-reports/ 2>/dev/null
        rm -rf target/classes target/test-classes target/maven-status
        if ! ls target/skeleton-reports/TEST-*.xml > /dev/null 2>&1; then
            red "FAIL: no test reports. The skeleton does not compile; see $SKELETON_LOG"
            grep -E 'ERROR.*\.java' "$SKELETON_LOG" | head -20
            exit 1
        fi

        # One line per test case: "<classname>#<display name> PASS|FAIL". The pom makes Surefire
        # report display names, so a scenario id is visible here without knowing any method.
        results="$(awk '
            /<testcase / {
                match($0, /name="[^"]*"/);      n = substr($0, RSTART + 6, RLENGTH - 7)
                match($0, /classname="[^"]*"/); c = substr($0, RSTART + 11, RLENGTH - 12)
                st = "PASS"; open = ($0 !~ /\/>[[:space:]]*$/)
                if (!open) print c "#" n " " st
                next
            }
            open && /<(failure|error)[ >]/ { st = "FAIL" }
            open && /<\/testcase>/ { print c "#" n " " st; open = 0 }
        ' target/skeleton-reports/TEST-*.xml)"

        for id in $(catalog_new_ids); do
            # A parameterised invocation is reported as "<method display name> <invocation name>",
            # so the id may follow a prefix; it must still be a whole id followed by a colon.
            mine="$(printf '%s\n' "$results" | grep -E "#(.*[^A-Za-z0-9-])?${id}:" || true)"
            if [ -z "$mine" ]; then
                red "FAIL: no test for $id ran"
                rc=1
            elif printf '%s\n' "$mine" | grep -q ' PASS$'; then
                red "FAIL: a test of $id PASSES against the skeleton, so it asserts nothing the"
                red "      skeleton does not already satisfy. Back to @test-designer:"
                printf '%s\n' "$mine" | grep ' PASS$' | sed -E 's/^[^#]*#/    /; s/ PASS$//'
                rc=1
            fi
        done
        [ "$rc" -eq 0 ] || exit 1
        green "OK: every NEW test fails against the skeleton. Stage 4b may start."
        ;;

    green)
        echo "== GREEN phase: only src/main may change =="
        rc=0
        ALLOWLIST="scripts/green-signature-allowlist.txt"
        allowed="$( [ -f "$ALLOWLIST" ] && grep -v '^#' "$ALLOWLIST" | grep . || true )"
        # The frozen ArchUnit store (TestBoundaryRulesTest) rewrites itself when a recorded violation
        # disappears, so production changes may shrink it. It may never grow: an added line would
        # be a new violation smuggled into the record of accepted ones.
        STORE="src/test/resources/archunit_store"
        store_added="$(git diff "$BASE" -- "$STORE" 2>/dev/null | grep -E '^\+[^+]' || true)"
        store_new="$(git ls-files --others --exclude-standard -- "$STORE" 2>/dev/null)"
        if [ -n "$store_added" ] || [ -n "$store_new" ]; then
            red "FAIL: the frozen ArchUnit store gained entries; it may only shrink"
            printf '%s\n' "$store_added" $store_new | sed 's/^/    /' | head -10
            rc=1
        fi
        if [ -z "$allowed" ]; then
            assert_untouched "src/test/ (except the store shrinking)" \
                src/test ":(exclude)$STORE" || rc=1
        else
            # A signature migration is in progress: listed test files may change, but only in
            # ways that do not alter WHAT a test checks. Every other test file stays frozen.
            touched="$(changed_under src/test | sort -u)"
            for f in $touched; do
                if ! printf '%s\n' "$allowed" | grep -qxF "$f"; then
                    red "FAIL: $f is not on the signature-migration allowlist"
                    rc=1
                fi
            done
            # Changed lines in allowlisted files must not touch an assertion, a test annotation
            # or a display name. Constructor calls, imports and types may change; verdicts may not.
            guard='assert|\.is[A-Z]|\.has[A-Z]|\.contains|\.doesNot|\.satisfies|\.allSatisfy|\.extracting|\.matches|@Test|@ParameterizedTest|@DisplayName|@ValueSource|@CsvSource|@MethodSource|@EnumSource|@Nested|@Disabled'
            for f in $allowed; do
                hits="$(git diff "$BASE" -- "$f" 2>/dev/null | grep -E '^[+-][^+-]' | grep -E "$guard" || true)"
                if [ -n "$hits" ]; then
                    red "FAIL: $f changes a line that decides what the test checks:"
                    printf '%s\n' "$hits" | sed 's/^/    /' | head -20
                    rc=1
                fi
            done
            [ "$rc" -eq 0 ] && green "OK: src/test/ changes are mechanical and confined to the allowlist"
        fi
        assert_untouched "docs/" docs || rc=1
        [ "$rc" -eq 0 ] || {
            red ""
            red "Changing a test to make the build pass is always the shortest path to green,"
            red "and it produces a passing build over broken software. Revert these files and"
            red "report the problem to @test-designer instead."
            exit 1
        }
        echo "== running full verify =="
        # clean: never trust classes left behind by the skeleton gate, compiled with NullAway off.
        $MVNW -B clean verify || { red "FAIL: ./mvnw clean verify did not pass"; exit 1; }
        green "OK: GREEN phase gate passed"
        ;;

    qa)
        echo "== QA phase: src/main must not be patched =="
        assert_untouched "src/main/" src/main || {
            red ""
            red "Patching the code you found the bug in means nobody learns the bug existed."
            red "Report it and add a failing test; @developer fixes it."
            exit 1
        }
        green "OK: QA phase gate passed"
        ;;

    *)
        echo "usage: $0 {pin|red [<feature>]|skeleton <feature>|green|qa|trace <feature>}   (BASE=<ref> overrides the pin)" >&2
        exit 2
        ;;
esac
