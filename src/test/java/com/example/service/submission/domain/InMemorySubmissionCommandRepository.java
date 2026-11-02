package com.example.service.submission.domain;

import com.example.service.shared.kernel.Result;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A deterministic stand-in for the real {@code submissions} table.
 *
 * <p>A fake, not a mock. It reproduces the one behaviour of the table that the business rules
 * depend on — {@code UNIQUE (tenant_id, external_ref)} — and nothing else, so BR-06, BR-08 and
 * BR-09 can be asserted without a container. {@code putIfAbsent} stands in for
 * {@code ON CONFLICT ... DO NOTHING}: it settles the race in the store rather than in a
 * read-then-write pre-check, exactly as the adapter must.
 */
final class InMemorySubmissionCommandRepository implements SubmissionCommandRepository {

    /** The unique constraint, as a value. Uniqueness never crosses organisations (BR-08). */
    private record ReferenceKey(UUID tenantId, String externalRef) { }

    private final Map<ReferenceKey, SubmissionSnapshot> byReference = new ConcurrentHashMap<>();

    @Override
    public Result<SubmissionId, RegisterSubmissionError.ReferenceAlreadyUsed> insert(
            SubmissionSnapshot snapshot) {
        var key = new ReferenceKey(snapshot.tenantId(), snapshot.externalRef());
        var alreadyThere = byReference.putIfAbsent(key, snapshot);
        return alreadyThere == null
                ? Result.success(new SubmissionId(snapshot.id()))
                : Result.failure(
                        new RegisterSubmissionError.ReferenceAlreadyUsed(snapshot.externalRef()));
    }

    // ------------------------------------------------------------------ arrange

    /** Arranges a submission that some earlier, successful registration already recorded. */
    void givenAlreadyRegistered(UUID tenantId, String externalRef) {
        var snapshot = new SubmissionSnapshot(UUID.randomUUID(), tenantId, externalRef,
                new java.math.BigDecimal("1.00"), "someone-else",
                java.time.Instant.parse("2020-01-01T00:00:00Z"), 0L);
        byReference.put(new ReferenceKey(tenantId, externalRef), snapshot);
    }

    // ------------------------------------------------------------------ inspect

    /** Everything recorded, newest-written order not guaranteed — sort before asserting order. */
    List<SubmissionSnapshot> storedSubmissions() {
        return byReference.values().stream()
                .sorted(Comparator.comparing(SubmissionSnapshot::externalRef))
                .toList();
    }

    Optional<SubmissionSnapshot> storedSubmission(UUID tenantId, String externalRef) {
        return Optional.ofNullable(byReference.get(new ReferenceKey(tenantId, externalRef)));
    }

    int storedCount() {
        return byReference.size();
    }
}
