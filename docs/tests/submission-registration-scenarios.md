# Submission registration — scenario catalogue

Scenarios of the `submission` module. The first pass traced the existing module; revision 7
(2026-11-08: allowed number characters, V4, the approval path) added the `NEW` rows. Sources: `docs/specs/submission-registration-spec.md` (`BR-01` to `BR-48`) and
the `## Test contract` section of `docs/adr/submission-registration-design.md` (TC1 to TC8).

- `NEW`: written in revision 7 and shown to fail before the implementation (pinned RED commit).
- `EXISTING`: an existing test covers the scenario; that test's display name starts with the id.
- `GAP`: the specification or the Test contract requires it, and no test covers it yet.
  `./scripts/check-write-lock.sh trace submission-registration` fails on every `GAP` until it is
  closed.
- `Level`: `unit` (use case entry point, access gate or value object, with fakes; the ArchUnit
  suite also runs here), `web` (HTTP endpoint), `it` (real PostgreSQL).

Fixtures used in Given: organisations A and B; `U1`, `U2` underwriters of A; `S1` a senior
underwriter of A; `V` a viewer of A. A valid amount is `1200.00`.

## Register a submission

| ID    | BR    | Flow                 | Scenario (business language)          | Given                         | When                          | Then                                      | Level | State |
|-------|-------|----------------------|---------------------------------------|-------------------------------|-------------------------------|-------------------------------------------|-------|-------|
| SC-01 | BR-03, BR-15 | register submission | an underwriter or a senior underwriter registers a valid submission | caller of either deciding role in A | registers `ABC-1` with `1200.00` | registered; the id the store returned; the registering caller recorded is the identified caller | unit | EXISTING |
| SC-02 | BR-15 | register submission | registration records organisation, number, amount, caller and moment | U1 in A; clock fixed at `2026-11-01T10:15:30Z` | registers `ABC-1`, `1200.00` | one stored submission: organisation A, `ABC-1`, `1200.00`, `U1`, the clock's moment, no version yet | unit | EXISTING |
| SC-03 | BR-04 | register submission | an absent or empty number is refused | U1 | registers number absent, or `""` | refused: number missing | unit | EXISTING |
| SC-04 | BR-04, BR-07, BR-29 | register submission | a number of ASCII letters, digits and hyphens is valid and kept exactly | U1 | registers `ABC-1`, `abc-1`, `Q-2026-001`, `0123456789`, `-` | registered; the stored number equals the supplied text | unit | NEW |
| SC-05 | BR-07 | register submission | numbers differing only in letter case are different numbers | U1 | registers `ABC-1`, `abc-1`, `Abc-1` | every one registers; equal text is equal, differing text is not | unit | EXISTING |
| SC-06 | BR-29 | register submission | an injection-shaped number is refused at its first character that is not allowed | U1 | registers numbers shaped like SQL, markup, path traversal, `%00`, `REF;1`, a bidi override | refused: number contains a character that is not allowed (code point, index); nothing stored | unit | NEW |
| SC-07 | BR-05 | register submission | a number of exactly 64 characters is accepted | U1 | registers `"A"`×64, or 64 allowed mixed characters | registered | unit | EXISTING |
| SC-08 | BR-05 | register submission | a number of 65 or more code points is refused | U1 | registers `"A"`×65, 65 emoji, or 33 base-plus-combining pairs | refused: number too long (max 64, got 65; 66 for the pairs), never the UTF-16 length | unit | EXISTING |
| SC-09 | BR-29, BR-07 | register submission | a number containing any character other than an ASCII letter, digit or hyphen is refused whole | U1 | registers a number with NUL, TAB, NEWLINE, CR, U+0085, SPACE, `;`, `/`, `\`, `.`, `%`, `_`, `~`, NO-BREAK SPACE, `Ł`, `É`, an emoji or an unpaired surrogate | refused: number contains a character that is not allowed (code point, index); nothing stored, nothing announced, nothing stripped | unit | NEW |
| SC-10 | BR-29, BR-07 | register submission | a number with spaces, including one made only of spaces, is refused and never trimmed | U1 | registers `" "`, `"   "`, `" ABC-1"`, `"ABC-1 "`, `"AB CD"` | refused: character not allowed, U+0020 at the first space | unit | NEW |
| SC-11 | BR-29, BR-05 | register submission | the refusal names the first character that is not allowed, as one whole code point | U1 | registers `"A;B/C"`, `"AB;C/D"`, `"A😀;B"`, `"AB😀"` | refused at the first offender: `;` at 1, `;` at 2, the emoji at 1, the emoji at 2 | unit | NEW |
| SC-12 | BR-04, BR-05, BR-29 | register submission | within the number, missing is judged before too long, and too long before the characters | U1 | registers `""`; or `"A"`×64 followed by a space | refused: number missing; refused: number too long (64, 65) | unit | EXISTING |
| SC-13 | BR-04, BR-10, BR-11, BR-29 | register submission | the number is judged before the insured limit | U1 | registers `"AB\tCD"` with no amount or `0`; or `""` with no amount | refused for the number (control character, or missing), not for the amount | unit | EXISTING |
| SC-14 | BR-10 | register submission | an absent insured limit is refused | U1 | registers `ABC-1` with no amount | refused: insured limit missing | unit | EXISTING |
| SC-15 | BR-11 | register submission | zero and negative amounts are refused on their sign, before their scale | U1 | registers `0.00`, `-0.00`, `-0.01`, `-1200.00`, `BigDecimal.ZERO` | refused: insured limit not positive; the refusal carries no amount | unit | EXISTING |
| SC-16 | BR-11, BR-12 | register submission | `0.01` is the smallest accepted amount | U1 | registers `0.01`; and `0.001` | `0.01` registered; `0.001` refused: precision invalid (2, 3) | unit | EXISTING |
| SC-17 | BR-12 | register submission | an amount without exactly two decimal places is refused, and `1200` is not `1200.00` | U1 | registers `1200`, `1200.5`, `1200.005`, `1200.0000`; and `1200.00` | refused: precision invalid (required 2, actual 0, 1, 3, 4); `1200.00` registered | unit | EXISTING |
| SC-18 | BR-12, BR-13 | register submission | exponent notation is judged on the scale it actually has | U1 | registers `1.2E+3`-style amounts (negative scale), `120000E-2` (scale 2), an absurd exponent | negative scale refused as precision invalid, cheaply; scale-2 exponent form registered | unit | EXISTING |
| SC-19 | BR-11, BR-12, BR-13 | register submission | a positive two-decimal amount is accepted with no upper bound and stored with its scale | U1 | registers `0.10`, `1000000.00`, a 32-digit and a 400-digit amount with scale 2 | registered; stored amount equals the supplied one, scale 2 | unit | EXISTING |
| SC-20 | BR-06 | register submission | a number already used in the same organisation is refused, and the earlier submission is unchanged | U1 registered `ABC-1` with `1200.00` | S1 registers `ABC-1` with `77.00` | refused: number already used (`ABC-1`); one stored submission, still `1200.00` by `U1`; one event only | unit | EXISTING |
| SC-21 | BR-08 | register submission | the same number in a different organisation is accepted, also under contention | B holds `ABC-1` | U1 of A registers `ABC-1`, alone or racing B | registered; one submission per organisation | unit | EXISTING |
| SC-22 | BR-09 | register submission | registrations racing for one number in one organisation record exactly one | nothing registered | 2, and 64, registrations of `RACE-1` in A at once | exactly one registered, every other refused: number already used; one stored row, one event | unit | EXISTING |
| SC-23 | BR-01, BR-02, BR-04, BR-05, BR-06, BR-09, BR-10, BR-11, BR-12, BR-29 | register submission | registration has exactly nine refusal reasons: two of access, seven of the domain | the compiled refusal types | they are listed | caller not identified, permission denied; number missing, too long, control character, insured limit missing, not positive, precision invalid, number already used; each a value record | unit | EXISTING |
| SC-24 | BR-22 | register submission | no refusal reason carries or prints the insured limit or the caller | every refusal reason, instantiated | it is printed or inspected | no amount and no actor id in any refusal; the control-character refusal holds only numbers | unit | EXISTING |
| SC-25 | BR-22, BR-42 | register submission | the write side never prints the amount or the registering caller | a command, a snapshot, an insured limit with `1200.00` by `uw-01` | each is printed | neither `1200.00` nor `uw-01` appears, while the accessors still return both | unit | EXISTING |
| SC-26 | BR-23 | register submission | the number, organisation and moment are printed readably, and control characters are escaped | a snapshot; a hostile number with a forged log line | it is printed | number in full, organisation and moment shown; every Cc character as `\uXXXX`, one inert line; the id prints as a UUID | unit | EXISTING |
| SC-27 | BR-15, BR-47 | register submission | a successful registration answers 201 with the number only | U1 identified at the edge | `POST /api/submissions` `{"submissionNumber":"ABC-1","insuredLimit":1200.00}` | 201; `Location: /api/submissions/ABC-1`; body `{"submissionNumber":"ABC-1"}` with no internal id | web | EXISTING |
| SC-28 | BR-01 | register submission | registration without an identified caller answers 401 | no caller at the edge | `POST /api/submissions` with a valid body | 401, type `caller-not-identified`; nothing stored, nothing announced | web | EXISTING |
| SC-29 | BR-02 | register submission | a viewer is refused at the edge whatever the body | V identified at the edge, in the wired application | `POST /api/submissions` with a valid body, and with `""` and `0.00` | 403, type `permission-denied`, for every body; nothing stored | web | EXISTING |
| SC-30 | BR-04, BR-05, BR-29 | register submission | each number refusal answers 400 with its own problem type | U1 identified | `POST` with number `""`, `"A"`×65, `"AB;CD"` | 400, types `number-missing`, `number-too-long`, `number-contains-character-not-allowed` | web | EXISTING |
| SC-31 | BR-10, BR-11, BR-12, BR-22 | register submission | each amount refusal answers 400, and no body shows the amount | U1 identified | `POST` with insuredLimit `null`, `-5.00`, `1200.5` | 400, types `insured-limit-missing`, `insured-limit-not-positive`, `insured-limit-precision-invalid`; no body contains the amount | web | EXISTING |
| SC-32 | BR-06, BR-23 | register submission | a duplicate number answers 409 | U1 registered `ABC-1` | U2 posts `ABC-1` | 409, type `number-already-used`; detail names the number with Cc escaped | web | EXISTING |

## Search submissions

| ID    | BR    | Flow                 | Scenario (business language)          | Given                         | When                          | Then                                      | Level | State |
|-------|-------|----------------------|---------------------------------------|-------------------------------|-------------------------------|-------------------------------------------|-------|-------|
| SC-33 | BR-16 | search submissions | every role gets the identical page and count, and the read side never reads the role | five submissions S-1..S-5 in A | a caller of each role in A searches with no filter | the same page S-5..S-1 and total 5 for every role; no read type refers to a role | unit | EXISTING |
| SC-34 | BR-17 | search submissions | another organisation's submissions are absent from the page and from the count | A holds S-1..S-5; B holds a newer `S-1` matching every filter | V searches, with any filter, any page, or 64 searches from A and B at once | only A's rows; total 5; the query has no organisation component; no answer crosses over | unit | EXISTING |
| SC-35 | BR-17, BR-20 | search submissions | a number held only by another organisation is indistinguishable from one nobody holds | B holds `ONLY-IN-B` | V searches by `ONLY-IN-B`, and by a number nobody holds | both: success, empty page, total 0, byte-identical; never a refusal | unit | EXISTING |
| SC-36 | BR-19, BR-31 | search submissions | no criteria, or an absent or empty number filter, narrows nothing | A holds S-1..S-5 | V searches with no criteria, number `null` or `""`, alone or with an amount bound | every own submission (or every one within the other bound); the number filter is not applied | unit | EXISTING |
| SC-37 | BR-07, BR-06 | search submissions | the number filter is compared exactly as supplied | A holds `S-3` | V searches by `S-3`, `s-3`, `S3` | `S-3` finds exactly one, total 1; near-misses are an empty success | unit | EXISTING |
| SC-38 | BR-18 | search submissions | criteria combine with "and", bounds are inclusive and never revalidated | S-1..S-5 at 100.00..500.00, an hour apart | V searches 200.00..400.00; hours 1..3; from 200.00 and up to hour 2; from -1 to 99999.5 | S-4, S-3, S-2; S-4, S-3, S-2; S-3, S-2; all five | unit | EXISTING |
| SC-39 | BR-20 | search submissions | a search matching nothing is a success | A holds S-1..S-5 | V searches by `NOT-A-REFERENCE` | success, empty page, total 0 | unit | EXISTING |
| SC-40 | BR-21 | search submissions | a contradictory range is an empty success, not a refusal | A holds S-1..S-5 | V searches 5000.00..1000.00, hour 4..hour 0, or both | success, empty page, total 0 | unit | EXISTING |
| SC-41 | BR-24 | search submissions | results come newest first | S-1..S-5 registered an hour apart | V searches | S-5, S-4, S-3, S-2, S-1 | unit | EXISTING |
| SC-42 | BR-25 | search submissions | a page request is zero-based and clamped, never refused, and echoes what was applied | A holds S-1..S-5 | V asks page 0 and 1 of size 2; size 500, 0, -1, MIN; page -3; page MAX with size 100 | S-5,S-4 then S-3,S-2; size clamped into 1..100 and echoed; page clamped to 0; offset computed without overflow; paging on the echoed size skips nothing | unit | EXISTING |
| SC-43 | BR-26 | search submissions | the total match count is the whole matching set, on every page | A holds S-1..S-5 | V asks pages 0, 1, 2 and 7 of size 2 | total 5 on every page, including the empty one | unit | EXISTING |
| SC-44 | BR-27 | search submissions | a page past the end is an empty success with the true count | A holds S-1..S-5 | V asks page 10 of size 2; page 4 of size 2 for a number nobody holds | empty page, total 5; empty page, total 0 | unit | EXISTING |
| SC-45 | BR-28 | search submissions | paging reaches every match once, also with tied moments and concurrent inserts | S-1..S-5 plus two rows tied with S-3 | V pages through by 2 and by 3; or searches while rows are added | each submission on exactly one page in the newest-first order; a page never holds more rows than its count | unit | EXISTING |
| SC-46 | BR-30 | search submissions | a number filter with a character that is not allowed matches nothing, even with other criteria | A holds S-1..S-5 | V searches by `"AB\tCD"`, `"\n"`, `"A\u0000B"`, `"REF;1"`, `"A/B"`, `"A.B"`, `"   "`, `" ABC-1"`, `"ŁÓDŹ"`, an emoji, alone or with an amount bound | success, empty page, total 0; the filter is never stripped | unit | NEW |
| SC-47 | BR-32, BR-05, BR-07 | search submissions | a number filter over 64 characters matches nothing; exactly 64 is compared normally | A holds a 64-character number | V searches by 65 characters (with or without a space); and by 64 characters | over 64: success, empty, total 0, never shortened; exactly 64: compared normally | unit | EXISTING |
| SC-48 | BR-14, BR-47 | search submissions | a result row shows number, amount, registering caller, moment and state, and nothing else | a search result | its row is read | exactly number, insured limit, registered by, registered at, state; no currency, no internal identity | unit | EXISTING |
| SC-49 | BR-22, BR-23, BR-42 | search submissions | the read side never prints an amount, a bound or a caller, and escapes the number | a row with `1200.00` by `uw-01`; a query and criteria with amount bounds and a hostile number filter; a page | each is printed | no amount, no bound, no caller; the number printed in full with Cc escaped | unit | EXISTING |
| SC-50 | BR-45, BR-46, BR-17, BR-26 | search submissions | search shows each submission's current state, only within the caller's organisation | A holds `REF-APPROVED` (approved) and `REF-OPEN`; B holds `SHARED-REF` that A approved under the same number | A searches; B searches | A: `APPROVED` and `REGISTERED`, total 2; B: one row `REGISTERED`, total 1 | it | EXISTING |
| SC-51 | BR-16, BR-24, BR-45 | search submissions | a search answers 200 with the page as JSON, for every role | V identified at the edge; A holds two submissions | `GET /api/submissions` | 200; `submissions[]` each with `submissionNumber`, `insuredLimit`, `registeredBy`, `registeredAt`, `state`; `totalMatchCount`, `page`, `size`; never 403 | web | EXISTING |
| SC-52 | BR-01 | search submissions | a search without an identified caller answers 401 | no caller at the edge | `GET /api/submissions` | 401, type `caller-not-identified` | web | EXISTING |

## Approve a submission

| ID    | BR    | Flow                 | Scenario (business language)          | Given                         | When                          | Then                                      | Level | State |
|-------|-------|----------------------|---------------------------------------|-------------------------------|-------------------------------|-------------------------------------------|-------|-------|
| SC-53 | BR-33, BR-40, BR-47 | approve submission | an underwriter who did not register the submission approves it, once | U1 registered `REF-1` | U2 approves `REF-1` | approved; the answer names `REF-1` only (200 `{"submissionNumber":"REF-1"}`); one approval recorded; one approval event announced | unit | EXISTING |
| SC-54 | BR-35 | approve submission | a number no submission of the caller's organisation has is not found | A holds `REF-1` | U1 approves `OTHER-1` | refused: submission not found | unit | EXISTING |
| SC-55 | BR-35, BR-43, BR-47 | approve submission | another organisation's submission is indistinguishable from an unknown one | B holds `OTHER-1` | U2 approves `OTHER-1`, and `UNKNOWN-1` | both 404, bodies byte-identical | web | EXISTING |
| SC-56 | BR-48, BR-35, BR-43 | approve submission | a text that cannot be a number names no submission | A holds `REF-1` | U2 approves `"bad\tnumber"`, and `UNKNOWN-1` | both 404 with byte-identical bodies that do not echo the text; nothing recorded or announced | web | EXISTING |
| SC-57 | BR-33, BR-34, BR-43 | approve submission | a viewer is refused for the role before the number is looked at | A holds `REF-1` | V approves `"bad\tnumber"`, and `REF-1` | 403, type `permission-denied`, in both cases; nothing recorded | web | EXISTING |
| SC-58 | BR-41 | approve submission | nobody approves a submission they registered | U1 registered `REF-1` | U1 approves `REF-1` | refused: approver registered this submission; 403, type `approver-registered-submission`; nothing recorded or announced | unit | EXISTING |
| SC-59 | BR-41, BR-34 | approve submission | the self-approval check comes before the already-approved check | U1 registered `REF-1`; S1 approved it | U1 approves `REF-1` | refused: approver registered this submission (403 `approver-registered-submission`), not already approved | unit | EXISTING |
| SC-60 | BR-36 | approve submission | an approved submission is not approved again | U1 registered `REF-1`; U2 approved it | S1 approves `REF-1` | refused: submission already approved | unit | EXISTING |
| SC-61 | BR-39, BR-36, BR-40 | approve submission | two approvals racing for one submission record exactly one | U1 registered `REF-n` | U2 and S1 approve it at once (25 rounds) | each round: one success, one refused as already approved; one approval row; one event | it | EXISTING |
| SC-62 | BR-34, BR-35, BR-36, BR-41 | approve submission | approval has exactly three domain refusal reasons | the compiled refusal types | they are listed | submission not found, approver registered this submission, submission already approved | unit | EXISTING |
| SC-63 | BR-35 | approve submission | a stored submission that no longer satisfies the rules is never approved | A holds `REF-2` stored with `100.123` | U2 approves `REF-2` | refused: submission not found; nothing announced | unit | EXISTING |
| SC-64 | BR-07, BR-35, BR-36, BR-48 | approve submission | a path segment carrying `;` names no submission and approves nothing | A holds `REF-1`, registered by U1 | U2 posts `/api/submissions/REF-1;x/approve` or `/api/submissions/REF-1;/approve` | 404 with the same body as an unknown number; `REF-1` still registered; nothing announced. Also proven on a real Tomcat (`SubmissionHttpFlowIT`), with `REF-1;jsessionid=1` added | web | NEW |
| SC-65 | BR-29, BR-48 | approve submission | a path carrying an encoded `/` names no submission | A holds `REF-1` | U2 posts `/api/submissions/REF-1%2Fx/approve` | refused, the same answer for an existing and an unknown number; nothing recorded. No registrable number contains `/` (BR-29). The application answers 404 (MockMvc); on a real Tomcat the container refuses first with 400, accepted 2026-11-08 (see Notes) | web | EXISTING |
| SC-66 | BR-38 | approve submission | approval changes none of the registered facts | U1 registered `REF-1` with `1200.00` at T0 | U2 approves `REF-1`; V searches | the row still shows `REF-1`, `1200.00`, `U1`, T0, now with state `APPROVED` | it | EXISTING |
| SC-67 | BR-37, BR-44 | approve submission | the recorded approval names the approver and the clock's moment | U1 registered `REF-1`; clock fixed at T1 | U2 approves `REF-1` | exactly one approval recorded for `REF-1`: approved by `U2`, at T1, in organisation A; the registration fact unchanged | it | EXISTING |
| SC-68 | BR-01 | approve submission | an approval without an identified caller answers 401 | no caller at the edge | `POST /api/submissions/REF-1/approve` | 401; nothing recorded | web | EXISTING |
| SC-69 | BR-36 | approve submission | approving an approved submission answers 409 | `REF-1` approved by U2 | S1 posts `/api/submissions/REF-1/approve` | 409, type `submission-already-approved`; still one approval, one event | web | EXISTING |

## Identity and access

| ID    | BR    | Flow                 | Scenario (business language)          | Given                         | When                          | Then                                      | Level | State |
|-------|-------|----------------------|---------------------------------------|-------------------------------|-------------------------------|-------------------------------------------|-------|-------|
| SC-70 | BR-01 | identity/access | registration without an identified caller is refused before anything else | no caller | registration is attempted with any input | refused: caller not identified; the amount is never touched and the operation never runs | unit | EXISTING |
| SC-71 | BR-01, BR-16 | identity/access | search has exactly one refusal: caller not identified | no caller | a search is attempted | refused: caller not identified; the search never runs | unit | EXISTING |
| SC-72 | BR-01, BR-34 | identity/access | approval without an identified caller is refused before the role | no caller | an approval is attempted | refused: caller not identified | unit | EXISTING |
| SC-73 | BR-02 | identity/access | a viewer is refused for registration before any input is examined | V | V registers with a malformed or valid amount | refused: permission denied (VIEWER); the amount is touched zero times, while an underwriter's is touched | unit | EXISTING |
| SC-74 | BR-02, BR-03, BR-33 | identity/access | the access policy alone decides which roles register and approve, and fails closed | a policy admitting some roles | a caller of each role registers or approves | admitted roles reach the operation exactly once with their own actor and input; others refused: permission denied naming the role; an empty policy refuses everyone | unit | EXISTING |
| SC-75 | BR-02, BR-03, BR-33 | identity/access | exactly Underwriter and Senior Underwriter may decide | the three roles | each is asked whether it may decide | Underwriter and Senior Underwriter yes; Viewer no | unit | EXISTING |
| SC-76 | BR-16 | identity/access | search never refuses for the role | a caller of each role | the search gate admits it | admitted, and the search runs; permission denied never occurs | unit | EXISTING |
| SC-77 | BR-01 | identity/access | a caller is identified only from well-formed claims | claims for tenant, actor id and role | tenant missing or not a canonical UUID; actor id absent, blank, 256 code points, or with a control character; role missing or not an exact name | no caller. Well-formed claims (actor id up to 255 code points, spaces kept) give a caller with exactly those values | unit | EXISTING |
| SC-78 | BR-42 | identity/access | the caller's identity is never printed | a caller `uw-01` | the caller context is printed | `uw-01` does not appear | unit | EXISTING |

## Persistence

| ID    | BR    | Flow                 | Scenario (business language)          | Given                         | When                          | Then                                      | Level | State |
|-------|-------|----------------------|---------------------------------------|-------------------------------|-------------------------------|-------------------------------------------|-------|-------|
| SC-79 | BR-04, BR-05, BR-07, BR-29 | persistence | every number the domain accepts can be stored exactly | PostgreSQL with the migrations | `ABC-1`, `abc-1`, `-`, `0123456789` and a 64-character number are stored | each stored and read back exactly | it | EXISTING |
| SC-80 | BR-13 | persistence | an amount of 400 digits round-trips exactly | PostgreSQL | a 400-digit amount with scale 2 is stored | read back equal, scale 2 | it | EXISTING |
| SC-81 | BR-11, BR-12, BR-29 | persistence | the database refuses what bypasses the domain | PostgreSQL with V4 | a row with scale 3, a negative amount, or a number with any character other than an ASCII letter, digit or hyphen is inserted directly; and rows of allowed characters | refused by the check constraints (`ck_submissions_submission_number_allowed_chars` for characters; NUL by the column type); allowed characters stored | it | NEW |
| SC-82 | BR-15 | persistence | a stored submission keeps every field exactly | PostgreSQL | a new submission is inserted and read back | every field equal, amount scale kept, a version assigned | it | EXISTING |
| SC-83 | BR-06, BR-09 | persistence | the database settles duplicate numbers within one organisation | A holds `ABC-1` | a second `ABC-1` in A is inserted; or 32 inserts race | refused: number already used; the earlier row untouched; the session still usable; exactly one row after the race | it | EXISTING |
| SC-84 | BR-08, BR-07 | persistence | uniqueness never crosses organisations, and letter case matters | A holds `ABC-1` | B inserts `ABC-1`; A inserts `abc-1`; A and B race for one number | each inserted; one row per organisation | it | EXISTING |
| SC-85 | BR-17 | persistence | every read statement is limited to the caller's organisation | A holds S-1..S-5; B holds the newest row `S-1` | A reads back by id, pages, counts, filters by a number only B holds, or searches while registrations race | never B's row, never counted; a foreign number answers exactly like an unknown one; no torn row | it | EXISTING |
| SC-86 | BR-18, BR-19, BR-21 | persistence | the search statements bind and filter correctly on a real server | A holds S-1..S-5 | searches with no filter, every filter, a bound of another scale, equal bounds, contradictory bounds | binds; inclusive bounds; numeric comparison across scales; every projected column with scale intact; contradictory range empty and total 0 | it | EXISTING |
| SC-87 | BR-24, BR-25, BR-26, BR-27, BR-28 | persistence | paging works against the real planner | A holds S-1..S-5, some tied | pages by limit and offset, past the end, at offset `Integer.MAX_VALUE`×100 | newest first, ties by a fixed order; each row on one page; past the end empty with the true count; the count ignores limit and offset | it | EXISTING |
| SC-88 | BR-17, BR-36 | persistence | the approval table refuses a cross-organisation or second approval | A holds `REF-1` | an approval of it naming B is inserted; a second approval of an approved submission is inserted | both refused by the database | it | EXISTING |
| SC-89 | BR-44 | persistence | an approved submission cannot be deleted | `REF-2` approved | `DELETE FROM submissions` for it | refused; the approval is not cascaded away | it | EXISTING |
| SC-90 | BR-44 | persistence | recorded facts are append-only in the database | one registered and one approved submission | `UPDATE`, `DELETE` and `TRUNCATE` on the registration and approval tables; then a new registration | each rewrite refused as insufficient privilege (42501), every fact unchanged; the insert succeeds | it | NEW |
| SC-91 | BR-45, BR-46 | persistence | the latest-state view shows one current state per submission, and search shows it | `REGISTERED-1` registered; `APPROVED-1` registered and approved; `OTHER-1` in another organisation | `submission_current` is read, and a search runs for organisation A | one row each with the right state; search: `REGISTERED-1` registered then `APPROVED-1` approved, no `OTHER-1` | it | NEW |
| SC-92 | BR-47 | persistence | the latest-state view, which search reads, carries no internal identity | PostgreSQL with V4 | the columns of `submission_current` are listed | exactly tenant, number, insured limit, registered by, registered at, approved; no id | it | NEW |

## Events

| ID    | BR    | Flow                 | Scenario (business language)          | Given                         | When                          | Then                                      | Level | State |
|-------|-------|----------------------|---------------------------------------|-------------------------------|-------------------------------|-------------------------------------------|-------|-------|
| SC-93 | BR-15, BR-47 | events | a successful registration announces `SubmissionRegistered` exactly once | U1 in A; fixed clock and event id | U1 registers `ABC-1`, `1200.00` | one event: the fixed event id, `ABC-1` as the number, organisation A, `1200.00`, `U1`, the clock's moment; no internal id | unit | EXISTING |
| SC-94 | BR-06, BR-09, BR-15 | events | a refused registration announces nothing | A holds `ABC-1`; or registrations race | U1 registers `ABC-1`; racing losers | refused: number already used; no event for any refusal; each winner announced once | unit | EXISTING |
| SC-95 | BR-22 | events | `SubmissionRegistered` never prints the amount, but carries it | an event with `1200.00` | it is printed | `1200.00` absent from the text; the accessor returns it | unit | EXISTING |
| SC-96 | BR-37, BR-40, BR-47 | events | `SubmissionApproved` carries who approved, when, and the number | U1 registered `REF-1`; fixed clock T1 and event id E | U2 approves `REF-1` | exactly one `SubmissionApproved`: event id E, number `REF-1`, organisation A, approved by U2, at T1 | web | EXISTING |
| SC-97 | BR-42 | events | `SubmissionApproved` never prints the approver | U2 approves `REF-1` | the announced event is printed | `uw-02` does not appear; the number does | web | EXISTING |

## Architecture

| ID    | BR    | Flow                 | Scenario (business language)          | Given                         | When                          | Then                                      | Level | State |
|-------|-------|----------------------|---------------------------------------|-------------------------------|-------------------------------|-------------------------------------------|-------|-------|
| SC-98 | BR-36, BR-38, BR-40, BR-44 | architecture | the structural rules of the production code hold | the compiled production classes | the architecture rules are evaluated | pass: pure domain, read and write use cases kept apart, raw input only through parse, no service or throwable types, no domain operation that reverses or rewrites a fact, only the approval use case constructs `SubmissionApproved` (any constructor, so a signature change cannot silence the rule), and no persistence `@Query` issues `UPDATE`, `DELETE`, `TRUNCATE` or `MERGE`. Each rule is shown to fail by a deliberate break | arch | EXISTING |

## Notes

**Counts.** 98 scenarios: 87 `EXISTING`, 11 `NEW`, 0 `GAP`. Every `BR-01` to `BR-48` appears in at
least one row. The ten former `GAP` rows (SC-27 to SC-32, SC-51, SC-52, SC-66, SC-67) are covered by
`submission.flows.SubmissionHttpFlowIT`: the whole application on an embedded Tomcat at a random
port, PostgreSQL in a container, plain HTTP. It uses only the Test contract (the endpoints, the
public `ActorContextResolver`, the `Clock` bean, the published events, the TC6 columns) and gives
every test an organisation of its own, so it never removes a fact. Each of its assertions was shown
to fail against a deliberately broken implementation (Location path, viewer permission, search
order, `;` stripping, approval moment, search state).

**SC-65 on a real Tomcat (decided 2026-11-08).** Tomcat's default `encodedSolidusHandling` is
`reject`: a path with `%2F` is answered `400` with Tomcat's HTML error page before Spring or the
access gate runs. The product owner accepted it: such a path is only ever a probe, since no
registrable number contains `/`. The test asserts the business rule, not the layer: a 4xx, the same
status and body for an existing and an unknown number, and nothing approved or announced.

**How the ids were attached.** Only display-name strings changed: `@DisplayName` and the `name` of
`@ParameterizedTest`. ArchUnit rules are fields, which `@DisplayName` cannot annotate, so `SC-98`
sits on the class `ArchitectureRulesTest` as a class-level `@DisplayName` (with its import). That one
id therefore covers the whole class. The former `SC-99` (rule C vacuous) is folded into `SC-98`:
rule C now matches any constructor call or reference of `SubmissionApproved`, shown by a
deliberate break.
`TestBoundaryRulesTest`, `ResultTest` and the binding tests of `ActorContextTest` (`callWith`,
`runWith`, `current`, `belongsToTenant`) are kernel or test-hygiene checks with no business scenario,
and carry no id.

**Tests that reach outside the Test contract** (left unchanged; recorded so they can be moved later):

- The persistence `*IT`s drive or `@Import` the SQL adapters, which are public since revision 7
  (G1), so this is no longer a boundary violation. The two entries still frozen in
  `src/test/resources/archunit_store` are `SubmissionRoundTrip` reaching the package-private
  aggregate.
- `SubmissionCommandSqlAdapterIT` and `SubmissionAdversarialPersistenceIT` read rows back through
  `SubmissionCommandSqlAdapter.findOwn(UUID, UUID)`, which TC7 says exists only for round-trip
  checks; an IT should read `submissions` instead.
- `SubmissionCommandSqlAdapterIT` round-trips through the aggregate (`reconstitute`, `toSnapshot`)
  via the helper `SubmissionRoundTrip` in `submission.domain`, which reaches the package-private
  `Submission`.
- `SubmissionQuerySqlAdapterIT` (SC-86) has a display name about binding `id AS submission_id` into
  the projection: it describes the divergence D16 that SC-92 asks to remove, although its body only
  asserts the numbers.
- `SubmissionApprovalResourceTest` subclasses `ActorContextResolver` and calls the resource
  constructor, both public since revision 7 (G1 closed).
- `SubmissionApprovalSchemaIT` and `SubmissionSearchStateIT` insert approval rows with SQL; TC6
  allows a direct insert only to test the database's own constraints, which holds for the first and
  not for the second.
- Several unit tests inspect structure by reflection rather than behaviour: the permitted subclasses
  of `RegisterSubmissionError`, `ApproveSubmissionError`, `SubmissionNumberError` and
  `AccessRefusal` (SC-23, SC-62), the record components of `SubmissionSummaryView`,
  `SearchSubmissionsQuery` and the error records (SC-24, SC-33, SC-34, SC-48), and the
  read side's vocabulary for `ActorRole` (SC-33).
- `OperationGateTest` subclasses `BigDecimal` to count touches on the amount; `ControlCharactersTest`
  and `LogSafeTextTest` exercise `shared.kernel` helpers that TC1 does not list.
