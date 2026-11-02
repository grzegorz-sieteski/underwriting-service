package com.example.service.submission.adapters.persistence;

import module java.base;

import com.example.service.shared.kernel.Result;
import com.example.service.submission.domain.RegisterSubmissionError;
import com.example.service.submission.domain.SubmissionCommandRepository;
import com.example.service.submission.domain.SubmissionId;
import com.example.service.submission.domain.SubmissionSnapshot;
import org.springframework.stereotype.Component;

/**
 * The write side of the {@code submissions} table.
 *
 * <p><strong>No {@code @Transactional}, because there is nothing for it to span.</strong> This
 * adapter issues one statement, and a single statement is atomic by itself. An
 * {@code @Transactional} here would be decoration. If a later publishing adapter needs the fact to
 * be atomic with the state change, the boundary has to span both port calls — and that is a
 * deferred adapter decision, not something to improvise here.
 */
@Component
class SubmissionCommandSqlAdapter implements SubmissionCommandRepository {

    private final SubmissionCommandJdbcRepository repository;

    SubmissionCommandSqlAdapter(SubmissionCommandJdbcRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /**
     * The row count is the whole duplicate test: {@code 1} means the row was inserted, {@code 0}
     * means this organisation had already used the reference (BR-06), including when a competing
     * registration won the race (BR-09). No {@code DuplicateKeyException} is caught, by design.
     */
    @Override
    public Result<SubmissionId, RegisterSubmissionError.ReferenceAlreadyUsed> insert(
            SubmissionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        var entity = SubmissionPersistenceMapper.toEntity(snapshot);
        var insertedRows = repository.insertIfReferenceFree(entity.id(), entity.tenantId(),
                entity.externalRef(), entity.insuredLimit(), entity.registeredBy(),
                entity.registeredAt());
        return insertedRows == 1
                ? Result.success(new SubmissionId(entity.id()))
                : Result.failure(
                        new RegisterSubmissionError.ReferenceAlreadyUsed(entity.externalRef()));
    }

    /**
     * Reads one of the caller's own rows back as a snapshot, version included.
     *
     * <p>Not part of {@link SubmissionCommandRepository}: no command in this feature loads a
     * submission. It is the read-back half of the snapshot round trip the design requires of this
     * adapter, and it is what proves the mapper carries {@code version} in both directions.
     */
    Optional<SubmissionSnapshot> findOwn(UUID tenantId, UUID id) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(id, "id");
        return repository.findOwn(tenantId, id).map(SubmissionPersistenceMapper::toSnapshot);
    }
}
