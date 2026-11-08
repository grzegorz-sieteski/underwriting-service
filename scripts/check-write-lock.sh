#!/usr/bin/env bash
#
# Phase boundary check for the AI SDLC workflow.
#
# The write locks described in .github/agents/*.agent.md cannot be enforced by an IDE: nothing
# stops an agent with edit access from touching src/test/ when it was told not to. This script
# is what turns those rules into something checkable. Run it after every stage.
#
#   ./scripts/check-write-lock.sh red [<feature>]     # test-designer: catalogue + tests, src/main untouched
#   ./scripts/check-write-lock.sh skeleton <feature>  # developer 4a: declarations only, every NEW test fails
#   ./scripts/check-write-lock.sh green               # developer 4b: src/test/ and docs/ untouched
#   ./scripts/check-write-lock.sh qa      # qa-explorer: src/main/ untouched
#
# BASE may be set to compare against something other than HEAD, e.g. BASE=origin/main.

set -uo pipefail

PHASE="${1:-}"
BASE="${BASE:-HEAD}"
MVNW="./mvnw"
[ -x "$MVNW" ] || MVNW="mvn"

red()   { printf '\033[31m%s\033[0m\n' "$1"; }
green() { printf '\033[32m%s\033[0m\n' "$1"; }

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

red_one() {
    set_feature "$1"
    local rc=0
    echo "-- feature: $FEATURE"
    [ -f "$SPEC" ] || { red "FAIL: specification $SPEC not found"; return 1; }
    [ -f "$CATALOG" ] || { red "FAIL: scenario catalogue $CATALOG not found"; return 1; }

    # Every business rule of the specification is in the catalogue.
    local missing="" br
    for br in $(grep -oE 'BR-[0-9]+' "$SPEC" | sort -u); do
        grep -qE "\\b${br}\\b" "$CATALOG" || missing="$missing $br"
    done
    if [ -n "$missing" ]; then
        red "FAIL: business rules with no scenario:$missing"
        rc=1
    else
        green "OK: every business rule of the specification has a scenario"
    fi

    # Every NEW scenario opens the display name of at least one test.
    local ids id in_tree
    ids="$(catalog_new_ids)"
    in_tree="$(test_tree_ids)"
    if [ -z "$ids" ]; then
        red "FAIL: the catalogue has no NEW scenario. RED without a new test proves nothing."
        rc=1
    fi
    for id in $ids; do
        printf '%s\n' "$in_tree" | grep -qxF "$id" || {
            red "FAIL: $id is NEW in the catalogue, but no test display name starts with \"$id:\""
            rc=1
        }
    done
    [ "$rc" -eq 0 ] && green "OK: every NEW scenario has a test, found by its id"
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
        for f in $features; do red_one "$f" || rc=1; done

        # Every scenario id used in a test exists in some catalogue: no untraced test.
        all_ids="$(cat docs/tests/*-scenarios.md 2>/dev/null | grep -oE '^\|\s*SC-[0-9]+' | grep -oE 'SC-[0-9]+' | sort -u)"
        for id in $(test_tree_ids); do
            printf '%s\n' "$all_ids" | grep -qxF "$id" || {
                red "FAIL: a test is labelled $id, but no catalogue has that scenario"
                rc=1
            }
        done
        [ "$rc" -eq 0 ] || exit 1
        green "OK: RED phase gate passed. Compilation is not required here; next gate: skeleton"
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
        if [ -z "$allowed" ]; then
            assert_untouched "src/test/" src/test || rc=1
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
        echo "usage: $0 {red <feature>|skeleton <feature>|green|qa}   (optionally with BASE=origin/main)" >&2
        exit 2
        ;;
esac
