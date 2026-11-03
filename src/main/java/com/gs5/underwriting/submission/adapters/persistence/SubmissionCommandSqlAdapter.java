package com.gs5.underwriting.submission.adapters.persistence;

import module java.base;

import com.gs5.underwriting.shared.kernel.Result;
import com.gs5.underwriting.submission.domain.RegisterSubmissionError;
import com.gs5.underwriting.submission.domain.SubmissionCommandRepository;
import com.gs5.underwriting.submission.domain.SubmissionId;
import com.gs5.underwriting.submission.domain.SubmissionSnapshot;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

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
        this.repository = repository;
    }

    /**
     * The row count is the whole duplicate test: {@code 1} means the row was inserted, {@code 0}
     * means this organisation had already used the reference (BR-06), including when a competing
     * registration won the race (BR-09). No {@code DuplicateKeyException} is caught, by design.
     */
    @Override
    public Result<SubmissionId, RegisterSubmissionError.ReferenceAlreadyUsed> insert(
            SubmissionSnapshot snapshot) {
        var entity = SubmissionPersistenceMapper.toEntity(snapshot);
        var insertedRows = repository.insertIfReferenceFree(entity.id(), entity.tenantId(),
                entity.externalRef(), entity.insuredLimit(), entity.registeredBy(),
                entity.registeredAt());
        return insertedRows == 1
                ? Result.success(new SubmissionId(entity.id()))
                : Result.failure(
                        new RegisterSubmissionError.ReferenceAlreadyUsed(entity.externalRef()));
    }

    @Override
    public Optional<SubmissionSnapshot> findOwn(UUID tenantId, UUID id) {
        return repository.findOwn(tenantId, id).map(SubmissionPersistenceMapper::toSnapshot);
    }
}
