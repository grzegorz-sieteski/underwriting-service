package com.gs5.underwriting.submission.adapters.persistence;

import com.gs5.underwriting.shared.kernel.ActorContext;
import com.gs5.underwriting.shared.kernel.ActorRole;
import com.gs5.underwriting.shared.kernel.Result;
import com.gs5.underwriting.submission.domain.ApproveSubmissionCommand;
import com.gs5.underwriting.submission.domain.ApproveSubmissionError;
import com.gs5.underwriting.submission.domain.ApproveSubmissionUseCase;
import com.gs5.underwriting.submission.domain.SubmissionApprovalRepository;
import com.gs5.underwriting.submission.domain.SubmissionCommandRepository;
import com.gs5.underwriting.submission.domain.SubmissionDomainEvent;
import com.gs5.underwriting.submission.domain.SubmissionEventPublisher;
import com.gs5.underwriting.submission.domain.SubmissionId;
import com.gs5.underwriting.submission.domain.SubmissionSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureDataSourceInitialization;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two approvals of one submission at the same moment, against the real primary key (BR-39).
 *
 * <p>Not transactional, on purpose. The two threads run on separate connections, so they would not
 * see a test transaction's uncommitted submission, and each approval must commit to race. Rows are
 * removed after each test.
 *
 * <p>The race is run many times, because one interleaving proves little.
 *
 * <p>RED at HEAD, and the reason is the schema, not the use case. {@code ON CONFLICT (submission_id)}
 * arbitrates only the primary key. The redundant unique index {@code (tenant_id, submission_id)} in V3
 * is a second conflict target that the clause does not name, so the losing insert raises
 * {@code DuplicateKeyException} rather than returning a row count of zero. The loser then fails with an
 * exception, not with {@code SubmissionAlreadyApproved}. Removing that index (ADR section 19.8, D9) is
 * part of the fix.
 */
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigureDataSourceInitialization
@Import({SubmissionCommandSqlAdapter.class, SubmissionApprovalSqlAdapter.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class SubmissionApprovalConcurrencyIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    private static final int ROUNDS = 25;
    private static final UUID TENANT_A = UUID.fromString("0a000000-0000-0000-0000-00000000000a");
    private static final Instant REGISTERED_AT = Instant.parse("2026-11-01T10:15:30Z");
    private static final Instant APPROVED_AT = Instant.parse("2026-11-02T08:00:00Z");
    private static final ActorContext FIRST_APPROVER =
            new ActorContext(TENANT_A, "uw-02", ActorRole.UNDERWRITER);
    private static final ActorContext SECOND_APPROVER =
            new ActorContext(TENANT_A, "suw-01", ActorRole.SENIOR_UNDERWRITER);

    @Autowired
    private SubmissionCommandRepository commands;

    @Autowired
    private SubmissionApprovalRepository approvals;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void removeRows() {
        jdbcTemplate.update("DELETE FROM submission_approvals");
        jdbcTemplate.update("DELETE FROM submissions");
    }

    @Test
    @DisplayName("two concurrent approvals record exactly one approval and announce exactly one event (BR-36, BR-39, BR-40)")
    void twoConcurrentApprovalsRecordOneRowAndOneEvent() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            var submissionId = UUID.randomUUID();
            commands.insert(new SubmissionSnapshot(submissionId, TENANT_A, "REF-" + round,
                    new BigDecimal("100.00"), "uw-01", REGISTERED_AT, null));
            var publisher = new RecordingPublisher();
            var useCase = new ApproveSubmissionUseCase(commands, approvals, publisher,
                    Clock.fixed(APPROVED_AT, ZoneOffset.UTC));
            var command = new ApproveSubmissionCommand(new SubmissionId(submissionId));

            var outcomes = raceTwice(useCase, command);

            assertThat(outcomes.stream().filter(Result::isSuccess).count())
                    .as("round %d: successful approvals", round).isEqualTo(1L);
            assertThat(refusalNames(outcomes))
                    .as("round %d: refusals", round).containsExactly("SubmissionAlreadyApproved");
            assertThat(approvalRows(submissionId))
                    .as("round %d: approval rows", round).isEqualTo(1);
            assertThat(publisher.events)
                    .as("round %d: events announced", round).hasSize(1);
        }
    }

    private List<Result<SubmissionId, ApproveSubmissionError>> raceTwice(
            ApproveSubmissionUseCase useCase, ApproveSubmissionCommand command) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                start.await();
                return useCase.execute(FIRST_APPROVER, command);
            });
            var second = pool.submit(() -> {
                start.await();
                return useCase.execute(SECOND_APPROVER, command);
            });
            start.countDown();
            return List.of(first.get(), second.get());
        }
    }

    private static List<String> refusalNames(List<Result<SubmissionId, ApproveSubmissionError>> outcomes) {
        return outcomes.stream()
                .filter(Result::isFailure)
                .map(outcome -> outcome.errorToOptional().orElseThrow().getClass().getSimpleName())
                .toList();
    }

    private int approvalRows(UUID submissionId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission_approvals WHERE submission_id = ?",
                Integer.class, submissionId);
    }

    /** Records what was announced. Thread-safe, because the two approvals publish from two threads. */
    private static final class RecordingPublisher implements SubmissionEventPublisher {

        private final List<SubmissionDomainEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void publish(SubmissionDomainEvent event) {
            events.add(event);
        }
    }
}
