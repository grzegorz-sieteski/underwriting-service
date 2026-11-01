---
description: "Rules for domain/ (write side) and query/ (read side): purity, encapsulation, failures as values"
applyTo: "**/{domain,query}/**/*.java"
---

# Write side and read side

You are inside the business core. It compiles without Spring on the classpath, and it must stay
that way — that is what makes these rules testable in milliseconds instead of needing a container.

## Allowed dependencies

`java.*` · `com.example.service.shared.kernel.*` · `org.jspecify.annotations.*`

Nothing else. No `org.springframework`, no `tools.jackson`, no `com.fasterxml.jackson`, no
`org.apache.kafka`, no persistence annotations. `@Table`, `@Id`, `@Column` and `@Version` belong
on the `*JdbcEntity` record in `adapters/persistence/`, never on an aggregate.

## No exceptions — failures are values

```java
// NO
if (ref.isBlank()) {
    throw new IllegalArgumentException("external ref is blank");
}

// YES
if (ref.isBlank()) {
    return Result.failure(new RegisterSubmissionError.BlankExternalReference());
}
```

`Objects.requireNonNull(x, "x")` stays allowed: it guards a programmer error, not a business
branch. Everything a user can cause is a `Result.failure`.

Errors are **per operation**, not per module:

```java
public sealed interface ApproveSubmissionError {
    record SubmissionNotFound(SubmissionId id) implements ApproveSubmissionError { }
    record NotPendingUnderwriting(SubmissionStatus current) implements ApproveSubmissionError { }
    record AuthorityLimitExceeded(BigDecimal limit, BigDecimal requested) implements ApproveSubmissionError { }
}
```

The caller's `switch` then handles exactly the failures that this operation can produce — and the
compiler proves nothing is missing.

## Encapsulation in `domain/`

| Visibility | Types |
|---|---|
| `public` | `*CommandFacade`, `*Command` records, `*Error`, ports, domain events, `*Id`, `*Snapshot` |
| **package-private** | the aggregate itself and every internal collaborator |

The aggregate being package-private is not a style preference — it is what stops a controller or a
JDBC adapter from reaching past the facade. The compiler enforces it for free.

- No public constructors on an aggregate. Use named factories: `Submission.register(...)` for a
  new one, `Submission.reconstitute(...)` for one loaded from the database.
- Validate invariants inside the aggregate, not in a caller.
- Reference other aggregates by typed id (`CustomerId`), never by object reference.
- Keep the optimistic-lock `version` as a plain `Long` field with no annotation.

## `query/` is a separate world

`query/` cannot import `domain/`, and `domain/` cannot import `query/` — both directions are
blocked by ArchUnit, and the package-private aggregate blocks it at compile time anyway.

- A query returns a projection record (`*View`), never an aggregate.
- A `*QueryFacade` validates the query (pagination bounds, tenant) and returns
  `Result<View, *QueryError>`.
- Read ports return `*View` records directly; the SQL that fills them lives in the adapter.

## Commands and queries are standalone records

No shared `Command` interface, no `handle(Command)`. Each operation is a named method on the
facade taking its own record and returning its own `Result`. A command may carry a masking
`toString()` when it holds sensitive data:

```java
public record RegisterSubmissionCommand(String externalRef, BigDecimal insuredLimit) {
    @Override
    public String toString() {
        return "RegisterSubmissionCommand[externalRef=" + externalRef + ", insuredLimit=***]";
    }
}
```

## The caller is in `ActorContext`

Every operation that depends on who is asking reads `ActorContext.current()`, which returns an
`Optional` rather than throwing when unbound. Tenant isolation is a business rule: a submission
belonging to another tenant must come back as `NotFound`, never as `Forbidden` — a distinct
"forbidden" tells the caller the record exists.
