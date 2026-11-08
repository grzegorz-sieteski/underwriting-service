# Submission registration — functional specification

Feature: **register an insurance submission, and search submissions by filter.**

Stage 1 artefact. Source of every business fact: the product owner's confirmed decisions recorded
in `docs/workflow/submission-registration-state.md` — the conversations of 2026-11-01 (the
original scope, and the search filter set and result order) and of 2026-11-02 (`BR-29`, refusing
numbers that contain control characters, decided after exploratory testing showed the previous
rule could not be honoured), plus a defect found in review that gave search `BR-30`.

Nothing in this document is an analyst invention. Every business fact here traces to a product
owner answer; where the business has not decided something, this document says so and places it
out of scope rather than filling the gap. **Every business fact in this specification is confirmed by the product owner.** Approval (`BR-33` to `BR-43`) was added on 2026-11-03 by a product-owner decision that reverses the earlier statements placing approval out of scope. The
two open questions its first draft carried — the search filter set and the result order — were
answered by the product owner on 2026-11-01 and are recorded below as confirmed facts.

**Revision of 2026-11-08 (product owner).** A submission number has no special characters: it
consists only of ASCII letters, digits and the hyphen (`BR-29`, rewritten). This replaces the
earlier rule that refused only control characters, so a number containing a space, `;`, `/` or a
letter outside ASCII, which was valid before, is now refused. The reason recorded: a number names a
submission in the request path (`BR-48`), and characters with a meaning in a path made one number
approve another. Passages below that still describe spaces or other characters as valid are
superseded by `BR-29` and kept only as the history of the 2026-11-02 decision.

Also on 2026-11-08 the product owner closed the two questions append-only (`BR-44` to `BR-46`)
left open. Retention, removal and anonymisation of recorded facts stay out of scope (Q16). The
history of a submission is not readable through the service, and no new operation is wanted for it
(Q17). Both are listed under Scope below; neither changes a rule.

**Revision of 2026-11-04 (product owner).** The submission number is the only identifier of a submission that leaves this service, and it is the name a caller uses for every operation (`BR-47`, `BR-48`). The internal identity is never shown outside the service. Approval names the submission by its submission number. Events carry the submission number. The term *submission reference* is renamed *submission number* throughout, and the rules it governs keep their meaning. `BR-35` no longer speaks of a malformed identity: a text that cannot be a submission number is covered by `BR-48`.

## Scope

In scope:

- registering a new submission
- approving a registered submission, once and finally (section 3, Command 2)
- searching submissions the caller's own organisation has registered, one page of results at a
  time, with the whole matching result set reachable by paging

Deliberately **out of scope**. Everything below is either something the business has decided
nothing about, or — in the last case — something it has deliberately left to engineering:

- any decision on a submission other than approval, such as referral or declinature (a separate future feature)
- any lifecycle state of a submission beyond "registered" and "approved"; registration, search and approval need no other,
  so none is defined here
- amending, withdrawing, cancelling or deleting a registered submission, and un-approving one (an approval is final, `BR-36`)
- any authority limit attached to a role
- any currency other than the single confirmed one
- any character in a submission number beyond the allowed set of `BR-29` (revision of
  2026-11-08): ASCII letters, digits and the hyphen. Spaces, punctuation, symbols, letters of other
  scripts, emoji and control characters are all refused
- how long a submission is kept, and any removal, anonymisation or retention of a recorded fact,
  including a request to remove an approver's or registering caller's identity. Confirmed by the
  product owner on 2026-11-08 (Q16): nothing in this feature removes or rewrites a fact (`BR-44`).
  Retention is a separate future feature with its own rule
- reading the history of a submission. Confirmed by the product owner on 2026-11-08 (Q17): no
  caller reads an earlier state through this service, and no operation for it exists or is to be
  added. An earlier state stays on record (`BR-46`) for audit, which reads it from storage
- the **maximum permitted page size**. Paging itself is in scope and specified below, but the
  upper bound on how large a single page may be is a resource-protection parameter, not a
  business rule. The page size is bounded; **the bound is set in the technical design**, and this
  document deliberately states no number for it. This is the one item here that the business has
  considered and consciously delegated, rather than simply not decided

---

## 1. Ubiquitous language

| Term | Definition |
|---|---|
| **Submission** | A request from the market to consider insuring a risk, as recorded by this service. A submission is created once, by registration. Its only later change is approval, which is final (`BR-36`) and is recorded as a new fact rather than an edit (`BR-44`); otherwise it is read-only within this feature. |
| **Submission Number** | The identifier the submitting party already uses for this submission, supplied by the caller and not generated by this service. Known to the business as `submissionNumber`. It is the only identifier of a submission that leaves this service: the internal identity is never shown outside it. It is the value audit uses to correlate a submission with the originating system. |
| **Internal Identity** | The service's own key for a submission, generated when the submission is registered. It is used only inside this service and is never shown to a caller or carried in an event (`BR-47`). |
| **Insured Limit** | The monetary amount of cover the submission asks for. Known to the business as `insuredLimit`. Always expressed in euro; the business has confirmed a single currency, so no currency is supplied, stored or returned. |
| **Organisation** | The business entity the caller belongs to (the tenant). Every submission belongs to exactly one organisation: the one its registering caller belonged to. Organisations are isolated from each other: no caller ever reads another organisation's submissions. |
| **Caller** | The authenticated person or system performing an operation. A caller is always known by three things together: their organisation, their own identity, and their role. |
| **Role** | What a caller is permitted to do. Exactly three roles exist: **Viewer**, **Underwriter**, **Senior Underwriter**. No other role exists in this feature. |
| **Registration** | The act of recording a submission for the first time. Registration either succeeds completely or changes nothing at all. |
| **Registration Moment** | The point in time at which a submission was registered. Recorded because audit needs to know when the correlated submission entered the service, and because search results need a stable order. |
| **Control Character** | A character in Unicode general category **Cc**: any character in the range U+0000–U+001F, or in the range U+007F–U+009F. This includes NUL (U+0000), TAB (U+0009) and NEWLINE (U+000A). It **excludes** SPACE (U+0020), which is an ordinary printable character and not a control character. "Control character" is a narrower idea than "whitespace" and the two must not be treated as interchangeable: TAB and NEWLINE are both, SPACE is only whitespace. |
| **Approval** | The recording of a registered submission as approved, by a caller permitted to approve it (`BR-33`). Each submission is approved at most once, and an approval is final (`BR-36`). The approval records who approved and when. |
| **Approver** | The caller performing an approval. Known, like every caller, by organisation, identity and role. |
| **Submission State** | The lifecycle state of a submission, which is one of exactly two values: **registered**, the state every submission has from the moment it is recorded, and **approved**, the state it has after a successful approval (`BR-36`). No other state exists. The state a submission is in at any time is the most current one, decided by `BR-45` and `BR-46`. |
| **Approved Submission** | A submission that has an approval recorded against it. |
| **Duplicate Submission Number** | A submission number that the caller's own organisation has already used on an earlier successfully registered submission. The same number used by a *different* organisation is not a duplicate. |
| **Submission Search** | A read-only enquiry that returns one page of the submissions of the caller's own organisation, narrowed by optional filter criteria, together with how many submissions matched in total. |
| **Filter Criterion** | One optional narrowing condition supplied with a search. Omitting a criterion means "do not narrow on this". |
| **Matching Set** | Every submission of the caller's own organisation that satisfies all supplied filter criteria. A search never returns the matching set in one answer; it returns one page of it. |
| **Result Page** | One consecutive run of the matching set, taken in the confirmed result order, returned by a single search. A caller asks for one page at a time and reaches the rest of the matching set by asking for further pages. A page may be empty. |
| **Page Request** | The part of a search that says which page is wanted and how large a page is. A page size is bounded above; the bound is a resource-protection parameter set in the technical design, not a business decision, and this document states no number for it. |
| **Total Match Count** | How many submissions are in the matching set in total, reported with every page so the caller knows how much there is to page through. It counts the whole matching set, not the page. |
| **Refusal Reason** | A named business outcome in which an operation does not happen. A refusal is a normal, expected answer, not a malfunction. Every refusal reason a command can produce is listed in this document; the list is exhaustive by construction. A caller must never receive an unexplained failure for an input this document calls valid — if that happens, the refusal catalogue is wrong and this document must be corrected, as it was for `BR-29`. |

---

## 2. Business rules (decision table)

Every row is testable exactly as written.

| ID | Rule | Condition | Expected outcome / refusal |
|---|---|---|---|
| `BR-01` | A caller must be identified before any operation in this feature | No caller is known (no organisation, identity and role available) | Operation refused: **caller not identified**. Nothing is recorded and nothing is returned |
| `BR-02` | Only an Underwriter or a Senior Underwriter may register a submission, and the role is decided **before any of the input is examined** | Caller's role is Viewer | Registration refused: **role may not register**, whatever the number and insured limit contain. The input is not examined, so a Viewer sending a malformed number is refused for the role, not for the number. The caller may still search (see `BR-16`) |
| `BR-03` | Registration permission depends on role alone | Caller's role is Underwriter, or Senior Underwriter | Registration is permitted. The two roles are treated identically here; no authority limit applies in this feature |
| `BR-04` | A submission number must be supplied | Number is absent, or contains no characters at all | Registration refused: **number missing** |
| `BR-05` | A submission number is at most 64 characters long | Number is 65 characters or longer | Registration refused: **number too long**. A number of exactly 64 characters is accepted |
| `BR-06` | A submission number is unique within one organisation | The caller's organisation already has a successfully registered submission carrying the identical number | Registration refused: **number already used**. The earlier submission is unchanged |
| `BR-07` | An accepted number is stored and compared exactly as supplied, never normalised | Two numbers differ in any character, including in letter case | They are different numbers. No trimming, case folding, padding, re-encoding or other normalisation is ever applied, because none is confirmed. This rule governs **how** an accepted number is treated, and says nothing about which characters are acceptable — that is `BR-04`, `BR-05` and `BR-29`. A number that `BR-29` refuses is refused whole: no character is ever stripped or replaced to make it acceptable |
| `BR-08` | The same number may be used by different organisations | Another organisation has already registered a submission with the identical number, and the caller's own organisation has not | Registration succeeds. Uniqueness never crosses organisations |
| `BR-09` | Where two registrations race for the same number in the same organisation, the first to be recorded wins | Two registrations with the identical number and organisation are attempted and neither has completed yet | Exactly one is recorded; the other is refused: **number already used**. Both are never recorded |
| `BR-10` | An insured limit must be supplied | Insured limit is absent | Registration refused: **insured limit missing** |
| `BR-11` | An insured limit is strictly positive | Insured limit is zero or negative | Registration refused: **insured limit not positive**. `0.01` is accepted; `0.00` and `-0.01` are refused |
| `BR-12` | An insured limit has exactly two decimal places | Insured limit is written with fewer or more than two decimal places | Registration refused: **insured limit precision invalid**. `1200.00` is accepted; `1200`, `1200.5` and `1200.005` are refused |
| `BR-13` | An insured limit has no upper bound | Insured limit is arbitrarily large and satisfies `BR-11` and `BR-12` | Registration succeeds. No maximum amount exists, and none may be introduced without a business decision |
| `BR-14` | An insured limit is always in euro | A caller supplies, or expects to receive, a currency | No currency is accepted, recorded or returned anywhere in this feature. Every amount is euro by definition |
| `BR-15` | A successful registration records the submission against the caller's own organisation | Registration succeeds | The submission belongs to the caller's organisation, carries the supplied number and insured limit, names the registering caller, and carries its registration moment |
| `BR-16` | Every role may search submissions | Caller's role is Viewer, Underwriter or Senior Underwriter | Search is permitted. Role never narrows or widens what a search returns |
| `BR-17` | A search returns only submissions of the caller's own organisation | A submission of another organisation satisfies every supplied filter criterion | That submission is not returned, is not counted, and its existence is not revealed in any way |
| `BR-18` | Filter criteria narrow a search together | Two or more filter criteria are supplied | Only submissions satisfying **all** supplied criteria are returned |
| `BR-19` | An omitted filter criterion narrows nothing | A filter criterion is not supplied | That criterion imposes no restriction. A search with no criteria at all returns every submission of the caller's own organisation |
| `BR-20` | A search matching nothing is a success, not a refusal | No submission of the caller's organisation satisfies the supplied criteria | A success carrying an empty page and a total match count of zero |
| `BR-21` | A search whose bounds contradict each other matches nothing, and is still a success | Both bounds of a range are supplied and the lower bound is greater than the upper bound. This applies to the insured limit range and, identically, to the registration moment range | A success carrying an empty page and a total match count of zero. It is **not** a refusal, and no refusal reason exists for it |
| `BR-22` | An insured limit never appears in a log | Any operation, successful or refused, is logged | The amount is absent from the log entry. This holds for refusals that are *about* the amount (`BR-10`, `BR-11`, `BR-12`): the reason may be named, the value may not be shown |
| `BR-23` | A submission number may appear in a log | Any operation is logged | The number may be written in full and readable, because audit needs it to correlate submissions |
| `BR-24` | Search results are returned in a stable, defined order | A search returns two or more submissions | They are ordered by registration moment, newest first |
| `BR-25` | A search returns one page of the matching set, not the whole set | A search is performed | Exactly the page asked for is returned, taken from the matching set in the order of `BR-24`. A page size is bounded above; the bound is set in the technical design, not here |
| `BR-26` | Every search reports how large the whole matching set is | A search succeeds, including when the page returned is empty | The total match count of the whole matching set is returned alongside the page, so the caller can tell how many pages exist. The count ignores which page was asked for |
| `BR-27` | Asking for a page beyond the end of the matching set is a success | The first submission of the requested page lies past the end of the matching set, including when the matching set is empty | A success carrying an empty page and the true total match count. It is **not** a refusal |
| `BR-28` | Paging reaches the whole matching set, once each | Every page of a matching set is requested in turn while the matching set is unchanged | Each matching submission appears on exactly one page, no submission is skipped, none appears twice, and the sequence of pages follows the order of `BR-24` |
| `BR-29` | A submission number contains only ASCII letters, digits and the hyphen (revision of 2026-11-08, product owner: "no special characters") | The number contains, at any position, at least one character other than `A`–`Z`, `a`–`z`, `0`–`9` and `-` (U+002D). Space, `;`, `/`, `\`, `.`, `%`, any other punctuation or symbol, any letter outside ASCII, any emoji and any control character (NUL, TAB, NEWLINE among them) are such characters | Registration refused: **number contains a character that is not allowed**. The number is refused whole; nothing is stripped or replaced (`BR-07`). The refusal names the first such character by its code point and its position, never the number itself |
| `BR-30` | A search whose number filter contains a character that is not allowed matches nothing, and is still a success | A search supplies a submission number filter containing at least one character that `BR-29` does not allow. No such number can have been registered, because `BR-29` refuses every one at registration | A success carrying an empty page and a total match count of zero. It is **not** a refusal and **not** an error, and no refusal reason exists for it: search keeps exactly one, **caller not identified** (`BR-01`). The filter is never stripped or altered to make it match. A filter made only of spaces matches nothing too, since a space is not allowed. This extends `BR-20` and `BR-21` |
| `BR-31` | A number filter that is empty is treated as not supplied, and narrows nothing | A search supplies a submission number filter that is present but empty, containing no characters at all (for example a blank field left untouched by the client) | The filter is treated exactly as if it had been omitted (`BR-19`): the search is not narrowed by number, and the other criteria apply as usual. With no other criteria, every submission of the caller's own organisation is in the matching set. It is a success, **not** a refusal and **not** an error, and no refusal reason exists for it: search keeps exactly one, **caller not identified** (`BR-01`). This is consistent with `BR-19`, which already says an omitted criterion narrows nothing; `BR-31` states that an empty number filter counts as omitted. A filter made only of spaces is not empty: it is a value that matches nothing (`BR-30`) |
| `BR-32` | A search whose number filter is longer than 64 characters matches nothing, and is still a success | A search supplies a submission number filter of 65 characters or more, counted the same way `BR-05` counts the length of a number. No such number can have been registered, because `BR-05` refuses every one at registration | A success carrying an empty page and a total match count of zero. It is **not** a refusal and **not** an error, and no refusal reason exists for it: search keeps exactly one, **caller not identified** (`BR-01`). The filter is never shortened or altered to make it match. A filter of **exactly 64 characters** is not affected: it is compared exactly as supplied (`BR-07`) and matches a submission registered with that very number. This extends `BR-30`, and through it `BR-20` and `BR-21` |
| `BR-33` | Only an Underwriter or a Senior Underwriter may approve a submission | Caller's role is Viewer | Approval refused: **role may not approve**. The role is decided before the submission is looked at, so a Viewer learns nothing about whether the submission exists. The caller may still search (`BR-16`) |
| `BR-34` | An approval is judged in a fixed order, and the first failure found is the one refusal the caller receives | Any approval is attempted | The order is: caller identified (`BR-01`); role permits approving (`BR-33`); submission found in the caller's own organisation (`BR-35`); approver did not register the submission (`BR-41`); submission not already approved (`BR-36`). Nothing later in the order is considered once an earlier check fails. The self-approval check comes before the already-approved check because it depends only on who the caller is and who registered the submission, not on the approval history; this mirrors the role being decided before the number history at registration, and means a caller who may not approve their own submission learns nothing about its approval state |
| `BR-35` | An approval can only reach a submission of the caller's own organisation | The submission named does not belong to the caller's own organisation, or does not exist at all | Approval refused: **submission not found**. The two cases are indistinguishable to the caller, so it cannot tell a submission of another organisation from one that does not exist (`BR-17`). The check is made after the role (`BR-34`) |
| `BR-36` | An approval is final, and a submission is approved at most once | The submission already has an approval recorded | Approval refused: **submission already approved**. The earlier approval stands on record unchanged (`BR-44`). Within this feature no approval can be withdrawn, reversed or repeated |
| `BR-37` | A successful approval records who approved and when | Approval succeeds | Exactly one approval is recorded for the submission, as a new fact added to the record (`BR-44`), naming the approving caller and the moment of approval. The moment is taken from the clock and is never supplied by the caller |
| `BR-38` | A successful approval changes none of the registered facts | Approval succeeds | The registration fact is never changed (`BR-44`). Approval adds a new fact and alters no registered fact. The submission's number, insured limit, registering caller and registration moment are the same in every view of the submission, whatever its current state (`BR-45`) |
| `BR-39` | Where two approvals of the same submission race, the first recorded wins | Two approvals of the same submission are attempted and neither has completed | Exactly one is recorded; the other is refused: **submission already approved**. Both are never recorded (as `BR-09`) |
| `BR-40` | A successful approval announces the approval as a business fact | Approval succeeds | The fact `SubmissionApproved` is announced (section 5). It is never announced for a refused approval |
| `BR-41` | A caller may not approve a submission that the same caller registered | The approving caller is the caller who registered the submission | Approval refused: **approver registered this submission**. The check comes after the submission is found in the caller's own organisation and before the already-approved check (`BR-34`). A caller whose role does not permit approving is refused for the role first (`BR-33`), whatever the submission |
| `BR-42` | The approving caller's identity never appears in a log | Any operation by an approver is logged | The approving caller's identity is absent from the log entry, on the same terms as the registering caller's identity (`BR-22`, OPEN-01). This applies to every approval, including a refused one |
| `BR-43` | A refused approval reveals only the one reason it was refused | Any approval is refused | The caller learns the single refusal reason named in the command catalogue and nothing more. No submission the caller may not see is revealed, in particular not by its existence (`BR-35`). A caller who may not approve learns nothing about the submission, because the role is decided first (`BR-34`) |
| `BR-44` | Recorded facts are never changed or removed. A change of state is a new fact added to the record | Any operation would change the state of a submission, or would otherwise alter or remove a fact already recorded | The change is recorded as a new fact added after the existing ones. No recorded fact is altered or deleted, by any operation. A refused operation records nothing (`BR-09`, `BR-36`, and each refusal in the command catalogue) |
| `BR-45` | Each submission's view shows its most current state only: registered, or approved once an approval has been recorded. An earlier state is never shown as current | A caller reads a submission, by search or in any other way | The view shows exactly one state: **registered**, or **approved** once an approval has been recorded. An earlier state is never shown as the current one. Search and every other way of reading a submission obey this, and remain within the caller's own organisation (`BR-17`) |
| `BR-46` | The most current state of a submission is the latest fact recorded for it | A submission has a registration recorded, and then an approval is recorded for it | The approval is the current state, and the registration remains on record as an earlier state. A later fact of the same kind is refused (`BR-36`), so a submission carries at most one registration and at most one approval, and its current state is always one of the two values of **Submission State** |
| `BR-47` | The internal identity of a submission is never shown outside this service. The submission number alone identifies a submission to any caller | Any response, refusal, search result or event that identifies a submission is produced | Every such identification uses the submission number. No response, refusal, search result or event carries the internal identity (glossary: **Internal Identity**). The submission number is the only identifier of a submission that leaves this service |
| `BR-48` | A text that cannot be a submission number names no submission | An operation names a submission by text that fails `BR-04`, `BR-05` or `BR-29` | The text names no submission and is refused exactly as a submission that does not exist (`BR-35`). The caller cannot tell the two cases apart. A request whose address carries a character that has a meaning in an address may instead be refused by the server before the service examines it (product owner, 2026-11-08). It then changes nothing, and its answer is the same whether or not a submission of that name exists, so it reveals nothing either |

---

## 3. Command catalogue

### Command 1 — Register a Submission

| | |
|---|---|
| **Who may do it** | A caller whose role is **Underwriter** or **Senior Underwriter** (`BR-02`, `BR-03`). The role is decided before any input is examined: see "The order in which a registration is judged" below |
| **Organisational context** | The submission is always registered for the caller's own organisation. The caller cannot name an organisation (`BR-15`) |

Inputs and their hard constraints:

| Input | Required | Constraint |
|---|---|---|
| Submission number | Yes | Present and non-empty (`BR-04`); at most 64 characters (`BR-05`); contains only ASCII letters, digits and the hyphen (`BR-29`); not already used by the caller's organisation, compared exactly as supplied (`BR-06`, `BR-07`) |
| Insured limit | Yes | Present (`BR-10`); strictly greater than zero (`BR-11`); exactly two decimal places (`BR-12`); no maximum (`BR-13`); euro, with no currency supplied (`BR-14`) |

Everything else about the submission — the organisation, the registering caller, the registration
moment — is taken from the identified caller and from the clock. It is never accepted as input.

**Every reason this command can be refused** (exhaustive) — there are **nine**:

The numbering below is the order in which refusals are decided (see the next subsection).

| # | Refusal reason | Raised when | Rule |
|---|---|---|---|
| 1 | **Caller not identified** | No caller is known | `BR-01` |
| 2 | **Role may not register** | The caller's role is Viewer | `BR-02` |
| 3 | **Number missing** | No number supplied, or it contains no characters | `BR-04` |
| 4 | **Number too long** | Number is longer than 64 characters | `BR-05` |
| 5 | **Number contains a character that is not allowed** | The number contains any character other than an ASCII letter, a digit or the hyphen: a space, `;`, `/`, punctuation, a non-ASCII letter, an emoji or a control character | `BR-29` |
| 6 | **Insured limit missing** | No insured limit supplied | `BR-10` |
| 7 | **Insured limit not positive** | Insured limit is zero or negative | `BR-11` |
| 8 | **Insured limit precision invalid** | Insured limit does not have exactly two decimal places | `BR-12` |
| 9 | **Number already used** | The caller's organisation has already registered this exact number, including when a competing registration wins the race | `BR-06`, `BR-09` |

No other refusal exists for this command. A refusal records nothing.

**Business fact that follows success:** the submission is registered — it belongs to the caller's
organisation, carries the supplied number and insured limit, names the caller who registered
it, and carries the moment of registration. It is immediately findable by a search from the same
organisation. The fact is announced as `SubmissionRegistered` (section 5).

#### The order in which a registration is judged

A registration is judged in the sequence below. **The first failure found is the one refusal the
caller receives; nothing later in the sequence is considered.**

| Step | What is judged | Refusal, in the order it is tried | Rule |
|---|---|---|---|
| 1 | The caller is identified | caller not identified | `BR-01` |
| 2 | The caller's role permits registering | role may not register | `BR-02`, `BR-03` |
| 3 | The number | number missing, then number too long, then number contains a character that is not allowed | `BR-04`, `BR-05`, `BR-29` |
| 4 | The insured limit | insured limit missing, then insured limit not positive, then insured limit precision invalid | `BR-10`, `BR-11`, `BR-12` |
| 5 | The number against what is already registered | number already used | `BR-06`, `BR-09` |

This is the order registration has always been judged in. Nothing about it has changed; it is
written down here so that it is not reversed by accident.

**Why the role comes before any of the input is examined.** A caller who may not register has no
business having what they sent read, interpreted or judged. Two things follow, and both are
deliberate:

- *An unauthorised caller learns nothing about the input.* A refusal for the role says nothing
  about whether the number or the insured limit would have been accepted, and nothing about
  which numbers the organisation already holds. If field refusals came first, anyone holding
  a Viewer's credentials, genuine or stolen, could use registration to probe the rules and the
  stored numbers without ever being permitted to register.
- *No effort is spent on someone who is not allowed to submit.* The rules about the input are the
  part of the service that handles whatever a caller chooses to send, so they are exposed to
  whoever reaches them. Limiting that exposure to callers who are permitted to register is the
  point of deciding the role first.

Within step 3 and within step 4 the order is fixed, so a request with two faults in the same field
always receives the earlier of the two. Step 5 depends on what the caller's organisation has
already registered, not on the input alone, so it comes last.

Which refusal the caller receives when more than one thing is wrong:

| Request | Refusal received |
|---|---|
| No caller is known, and the number is empty | **Caller not identified** |
| A Viewer sends a malformed number (for example an empty one, or one containing a TAB) | **Role may not register**, not the number refusal |
| A Viewer sends a malformed insured limit (for example `0.00`) | **Role may not register**, not the insured limit refusal |
| A Viewer sends a valid request whose number the organisation has already used | **Role may not register** |
| An Underwriter sends a number of 65 characters containing a TAB | **Number too long** (the earlier of the two faults in the number) |
| An Underwriter sends a number containing a TAB and no insured limit | **Number contains a character that is not allowed** (the number is judged before the insured limit) |
| An Underwriter sends a number of 65 characters and an insured limit of `1200.5` | **Number too long** |
| An Underwriter sends a valid request whose number the organisation has already used | **Number already used** |

#### Why numbers with control characters are refused (history; superseded by the 2026-11-08 revision of `BR-29`)

This restriction was added on 2026-11-02, after the first eight refusal reasons were already
agreed and tested. It is recorded here so that a reader a year from now does not have to
rediscover the reason or mistake it for an arbitrary tightening.

Exploratory testing registered a number containing NUL (U+0000). The business rules of the day
said it was valid, so the service accepted it — and the store the service keeps submissions in
cannot hold that character, so recording it failed afterwards, below the level at which business
refusals are decided. The caller received an unexplained server error. That error was not one of
the refusal reasons this catalogue calls exhaustive, which means the promise made in this document
was not being kept: a value the business had called valid could not in fact be registered, and the
caller was told nothing useful.

The product owner was offered the narrow fix — refuse only NUL, the one character that cannot be
stored — and **deliberately chose the broader rule**: refuse every control character. A submission
number is an identifier that people read, quote in correspondence and use to correlate records
in audit; characters that cannot be seen or printed have no legitimate place in one, and letting
them in invites numbers that look identical but are not (`BR-07` makes such a pair two
different numbers, permanently). Refusing the whole category is easier to explain to a
submitting party than a single-character exception, and it closes the question rather than leaving
a second control character to be discovered later.

The business point, stated once: a caller who sends an unusable number is owed a clear refusal
naming what is wrong with it, not a server error.

#### Behaviour this reverses, and behaviour it leaves alone

`BR-29` **changes behaviour that was previously confirmed and is currently asserted by passing
tests.** This is a deliberate reversal, not a clarification, and it is called out here rather than
folded in quietly.

Now **refused**, having previously been valid:

| Number | Previously | Now |
|---|---|---|
| A number containing NUL (U+0000) | Valid by rule, unrecordable in practice | Refused: **number contains a control character** |
| A number consisting of a single TAB (U+0009) | **Valid** | **Refused** |
| A number consisting of a single NEWLINE (U+000A) | **Valid** | **Refused** |
| A number such as `AB\tCD` or `AB\nCD`, containing a control character anywhere inside it | **Valid** | **Refused** |
| Any other character of category Cc, anywhere in the number | **Valid** | **Refused** |

**Unchanged and still valid** — nothing here is affected, and no rule about it was reversed:

| Number | Status |
|---|---|
| A number containing SPACE (U+0020) between other characters | **Valid**, as before |
| A number consisting only of spaces, for example `"   "` | **Valid**, as before. It is present, it is non-empty, it contains no control character, and it is not normalised away |
| A number with leading or trailing spaces | **Valid**, as before, and the spaces are preserved exactly (`BR-07`) |
| Letters of any script, digits, punctuation, symbols, emoji | **Valid**; no restriction beyond `BR-29` exists |

SPACE is not a control character, so no rule about spaces changed. `BR-07`'s prohibition on
trimming stands exactly as it did, and a number of spaces must still be accepted and stored
with its spaces intact.

---

### Command 2 — Approve a Submission

| | |
|---|---|
| **Who may do it** | A caller whose role is **Underwriter** or **Senior Underwriter** (`BR-33`). Confirmed by the product owner: the same two roles as for registration |
| **Organisational context** | The submission must belong to the caller's own organisation (`BR-35`). An approval is never recorded against another organisation's submission |

Inputs and their hard constraints:

| Input | Required | Constraint |
|---|---|---|
| Submission number | Yes | Names one submission. It is examined only after the role is decided (`BR-34`). A number that names no submission of the caller's own organisation is refused as **submission not found** (`BR-35`) |

Nothing else is accepted. The approving caller, the moment of approval and the organisation are
taken from the identified caller and from the clock.

**Every reason this command can be refused** (exhaustive) — there are **five**:

| # | Refusal reason | Raised when | Rule |
|---|---|---|---|
| 1 | **Caller not identified** | No caller is known | `BR-01` |
| 2 | **Role may not approve** | The caller's role is Viewer | `BR-33` |
| 3 | **Submission not found** | No submission of the caller's own organisation has this submission number, whether because it belongs to another organisation or does not exist | `BR-35` |
| 4 | **Approver registered this submission** | The approving caller is the caller who registered the submission | `BR-41` |
| 5 | **Submission already approved** | The submission already has an approval recorded, including when a competing approval wins the race | `BR-36`, `BR-39` |

No other refusal exists for this command. A refusal records nothing.

The order of these checks is fixed (`BR-34`): caller identified, role, submission found, approver did
not register it, not already approved. So a Viewer naming a submission of another organisation is refused for the role, and
learns nothing about whether that submission exists.

Worked examples of the order:

| Request | Refusal received |
|---|---|
| No caller is known, and the submission number is unknown | **Caller not identified** |
| A Viewer names a submission that exists in another organisation | **Role may not approve**, not *submission not found* |
| An Underwriter names a submission of another organisation, or one that does not exist | **Submission not found** (the two cases are not distinguishable) |
| An Underwriter names a submission they registered themselves | **Approver registered this submission** |
| An Underwriter names a submission of their own organisation that is already approved, and did not register it | **Submission already approved** |
| An Underwriter names a submission of their own organisation that is not yet approved | Approved; the approval records the Underwriter's identity and the moment (`BR-37`) |

**Business fact that follows success:** the submission is approved. The approval records who
approved it and when, changes none of the registered facts (`BR-38`), and is announced as
`SubmissionApproved` (section 5).

---

## 4. Query catalogue

### Query 1 — Search Submissions

| | |
|---|---|
| **Who needs it** | Every role: Viewer, Underwriter, Senior Underwriter (`BR-16`) |
| **What they may see** | Submissions registered by their **own** organisation only |
| **What they are not allowed to see** | Any submission of any other organisation, under any filter, on any page, in the total match count, and in any refusal message — their existence is never revealed (`BR-17`). This restriction is not relaxed for any role |

Filter criteria, all optional (`BR-19`), combined with "and" (`BR-18`):

| Criterion | Meaning |
|---|---|
| Submission number | Returns the submission carrying exactly this number, compared exactly as supplied (`BR-07`). Because a number is unique within an organisation (`BR-06`), this narrows to at most one submission. An empty number filter counts as not supplied and narrows nothing (`BR-31`). A number filter longer than 64 characters matches nothing (`BR-32`) |
| Insured limit — at least | Returns submissions whose insured limit is greater than or equal to this amount (inclusive) |
| Insured limit — at most | Returns submissions whose insured limit is less than or equal to this amount (inclusive) |
| Registered from | Returns submissions whose registration moment is at or after this moment (inclusive) |
| Registered to | Returns submissions whose registration moment is at or before this moment (inclusive) |

Both amount bounds are comparison values, not amounts being registered: the constraints of
`BR-11` and `BR-12` are rules about what may be *registered* and are not re-imposed on a filter
value.

A range whose lower bound exceeds its upper bound simply matches nothing: the search succeeds and
returns an empty page with a total match count of zero. This holds for the insured limit range and
for the registration moment range alike, and it is **not** a refusal (`BR-21`).

A number filter containing a character that `BR-29` does not allow likewise matches nothing,
because `BR-29` refuses every such number at registration, so none can ever have been stored. The
search succeeds and returns an empty page with a total match count of zero. It is **not** a refusal
and **not** an error (`BR-30`). The filter is not cleaned up: no character is stripped or replaced
to make it match something. A filter of spaces only matches nothing for the same reason.

**A filter that is not set is simply not applied** (`BR-19`), and for the number filter an
**empty** value counts as not set (`BR-31`). The product owner's answer, recorded: a filter that is
not set takes no part in the search. A client that leaves its number field untouched therefore
receives all of the caller's own submissions, not none.

Number filters that look alike and are treated differently:

| Number filter sent | What it is | Treatment | Result of the search |
|---|---|---|---|
| Present but empty (no characters) | Not set | Not applied (`BR-31`, `BR-19`) | Success. Narrows nothing: with no other criteria, every submission of the caller's own organisation matches, newest first, one page at a time |
| Contains a character that is not allowed, for example a space, `;`, `/`, a TAB or NUL | A value no stored number can equal | Applied as given, never altered (`BR-30`) | Success with an empty page and a total match count of zero |
| Longer than 64 characters (65 or more, counted as `BR-05` counts) | A value no stored number can equal | Applied as given, never shortened (`BR-32`) | Success with an empty page and a total match count of zero |
| Exactly 64 characters | A value within the registrable length | Applied, compared exactly as supplied (`BR-07`) | Success. Returns the submission registered with exactly that number, if the caller's organisation has one; otherwise an empty page and a total of zero (`BR-20`) |
| Made only of spaces, for example `"   "` | Not empty, but a space is not allowed (`BR-29`) | Applied as given, never altered (`BR-30`) | Success with an empty page and a total match count of zero |

Worked examples, for an organisation holding three submissions with numbers `A-1`, `A-2` and
`B7`:

- Number filter empty, no other criteria: all three submissions, total three.
- Number filter empty, insured limit at least some amount: only the submissions at or above
  that amount, as if the number filter had not been sent.
- Number filter `"A\tB"` (containing a TAB): empty page, total zero.
- Number filter `"   "` (three spaces): empty page, total zero, because a space is not allowed
  (`BR-30`).
- Number filter `a-1`: empty page, total zero, because numbers are compared exactly, letter case
  included (`BR-07`).
- Number filter `A-1`: the one submission `A-1`, total one.
- Number filter of 65 characters, made of the 64 characters of a number the organisation
  holds followed by one more: empty page, total zero (`BR-32`). It is not shortened to 64 to find
  a match.
- Number filter of exactly 64 characters, equal to a number the organisation registered:
  that one submission, total one. If no such number exists: empty page, total zero (`BR-20`).

In every case above the search succeeds; none is refused.

Search has no ordering question between role and input: every role may search (`BR-16`) and no
filter value is ever refused (`BR-20`, `BR-21`, `BR-30`, `BR-31`, `BR-32`), so the only thing judged before a search
runs is that the caller is identified (`BR-01`).

**Page request.** Every search asks for one page of the matching set (`BR-25`): which page is
wanted, and how large a page is. A page size is bounded above; **that bound is a
resource-protection parameter set in the technical design, not a business rule**, and no number
for it appears in this document.

**Order of results.** By registration moment, newest first (`BR-24`). The order is the same for
every caller and every page, which is what makes paging reach the whole matching set exactly once
(`BR-28`).

**What a successful search returns:** one page of submissions in that order, plus the total match
count of the whole matching set (`BR-26`). Each returned submission shows its submission
number, its insured limit, the caller who registered it, and its registration moment. Nothing
else exists to show. Each returned submission also shows its **state**: either *registered* or *approved* (see the glossary term Submission State). The state shown is always the most current one, and an earlier state is never shown (`BR-45`). The state is visible only within the caller's own organisation, like every other part of a result (`BR-17`).

**Every reason this query can be refused** (exhaustive) — there is exactly **one**:

| # | Refusal reason | Raised when | Rule |
|---|---|---|---|
| 1 | **Caller not identified** | No caller is known | `BR-01` |

**This single-reason list is deliberate and complete, not an oversight.** Once the caller is
known, every search succeeds. In particular a search is never refused for a role reason
(`BR-16`), never refused because it matched nothing (`BR-20`), never refused because a range
contradicts itself (`BR-21`), never refused because the number filter contains a control
character (`BR-30`), never refused because the number filter is empty (`BR-31`), never refused because the
number filter is longer than 64 characters (`BR-32`), and never
refused because the page asked for lies beyond the end of the matching set (`BR-27`) — each of those is a successful search returning an empty page.

---

## 5. Business events

One fact in this feature is worth telling other systems.

| Event | Stated as | Raised when | Carries |
|---|---|---|---|
| `SubmissionApproved` | A submission has been approved | An approval succeeds (`BR-40`) | The organisation, the submission number, the approving caller's identity, the moment of approval. The insured limit is not carried |
| `SubmissionRegistered` | A submission has been registered | A registration succeeds (`BR-15`) | The organisation, the submission number, the insured limit, the caller who registered it, the registration moment |

Notes:

- The event is never raised for a refused registration.
- The insured limit travels inside the event because downstream systems need the amount; this is
  not a log, and `BR-22` is a rule about logs. Any system receiving the event inherits the
  obligation not to log the amount.
- Searching announces nothing: a read is not a business fact. Whether a search should additionally
  be reported to audit has never been asked of the business, and is out of scope here.
- No other event exists, because no other business fact occurs in this feature. Events about
  decisions on a submission belong to a future feature. Approval is the only such decision specified here (section 3, Command 2).

---

## 6. Data classification

| Field | Classification | May it appear in a log? |
|---|---|---|
| **Insured limit** | Financially sensitive — confirmed by the business | **Never.** Not in a success log, not in a refusal log, not in a diagnostic message, not partially, not rounded (`BR-22`) |
| **Submission number** | Business identifier — confirmed readable by the business | **Yes**, in full and readable, because audit needs it to correlate submissions (`BR-23`) |
| **Organisation** | Isolation boundary. No sensitivity decision exists | Follow this repository's existing audit practice. Not classified here |
| **Registering caller's identity** | Attributable to an individual. Recorded in audit fields, but confirmed by the product owner (2026-11-02, OPEN-01) as **never loggable** | **Never.** Not in a success log, not in a refusal log, not in any diagnostic message. It is stored for audit and read from storage, never from logs |
| **Approving caller's identity** | Attributable to an individual, recorded in the approval for audit and carried in the approval event. Confirmed by the product owner as never loggable, as for the registering caller | Never written to a log, on the same terms as the registering caller's identity (`BR-42`) |
| **Approval moment** | Not sensitive on its own | No decision exists. Not classified here |
| **Registration moment** | Not sensitive on its own | No decision exists. Not classified here |

---

## Hand-over checklist

- [x] Every rule has an ID and is testable as written
- [x] Every command and query lists **all** of its refusal reasons, exhaustively: **nine** for
      Register a Submission, **one** for Submission Search, and **five** for Approve a Submission
- [x] Every number traces to the product owner's confirmed answer (64 characters, two decimal
      places, strictly positive, no upper bound, three roles, the Cc ranges U+0000–U+001F and
      U+007F–U+009F) or is absent
- [x] One previously confirmed behaviour is reversed by `BR-29` (TAB, NEWLINE, NUL and the rest
      of category Cc move from valid to refused). The reversal, its reason, and the inputs that
      are **not** affected are all stated explicitly under the Register command rather than
      folded in silently — existing tests asserting the old behaviour must be changed
- [x] Registration is judged in the order caller identified, role, number, insured limit,
      number already used. The role is decided before any input is examined, so an unauthorised
      caller learns nothing about the input; the reason is stated under the Register command.
      This is the long-standing order, not a reversal
- [x] Recorded facts are append-only: a change of state is a new fact, and a view shows only the most current state (`BR-44` to `BR-46`)
- [x] Search accepts any filter value without refusal, including a number filter containing a
      control character (`BR-30`), and treats an empty number filter as not supplied (`BR-31`), and accepts a number filter
      longer than 64 characters as matching nothing (`BR-32`); it
      still has exactly one refusal reason
- [x] No class name, table name, query language or framework term anywhere in this document
- [x] **Every business fact is confirmed by the product owner, including approval.** The
      self-approval refusal (`BR-41`), not-found rule (`BR-35`), approver logging rule
      (`BR-42`), role policy (`BR-33`) and approval state in search results are all decided. The two open
      questions the first draft carried were answered by the product owner on 2026-11-01: the
      search filter set (number, insured limit range, registration moment range, all optional
      and combined with "and") and the result order (newest registration moment first). The
      product owner also chose an empty successful result, rather than a refusal, for a range
      whose bounds contradict each other — which is why Submission Search has exactly one
      refusal reason
- [x] The one number this feature does not state — the maximum permitted page size — is recorded
      as an engineering decision in the technical design by agreement, not left as a gap here
