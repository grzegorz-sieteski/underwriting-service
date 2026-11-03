package com.gs5.underwriting.submission.domain;

import com.gs5.underwriting.shared.kernel.ActorContext;
import com.gs5.underwriting.shared.kernel.ActorRole;
import com.gs5.underwriting.shared.kernel.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class ApproveSubmissionUseCaseTest {

    private static final UUID TENANT_A = UUID.fromString("0a000000-0000-0000-0000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("0b000000-0000-0000-0000-00000000000b");
    private static final Instant APPROVED_AT = Instant.parse("2026-11-02T12:00:00Z");

    private static final ActorContext UNDERWRITER =
            new ActorContext(TENANT_A, "uw-01", ActorRole.UNDERWRITER);
    private static final ActorContext SENIOR_UNDERWRITER =
            new ActorContext(TENANT_A, "suw-01", ActorRole.SENIOR_UNDERWRITER);
    private static final SubmissionId SUBMISSION_ID = new SubmissionId(UUID.fromString("11111111-1111-1111-1111-111111111111"));

    private InMemorySubmissionCommandRepository commandRepository;
    private InMemorySubmissionApprovalRepository approvalRepository;
    private InMemorySubmissionEventPublisher eventPublisher;
    private ApproveSubmissionUseCase useCase;

    @BeforeEach
    void setUp() {
        commandRepository = new InMemorySubmissionCommandRepository();
        approvalRepository = new InMemorySubmissionApprovalRepository();
        eventPublisher = new InMemorySubmissionEventPublisher();
        useCase = new ApproveSubmissionUseCase(commandRepository, approvalRepository,
                eventPublisher, Clock.fixed(APPROVED_AT, ZoneOffset.UTC));

        commandRepository.insert(new SubmissionSnapshot(SUBMISSION_ID.value(), TENANT_A,
                "REF-1", new BigDecimal("100.00"), "uw-01",
                Instant.parse("2026-11-01T09:00:00Z"), 0L));
    }

    @Test
    @DisplayName("approval is refused when the submission does not belong to the caller's tenant")
    void shouldRefuseApprovalWhenSubmissionIsNotFound() {
        // Given
        var otherTenantSubmissionId = new SubmissionId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        var command = new ApproveSubmissionCommand(otherTenantSubmissionId);

        // When
        var result = useCase.execute(UNDERWRITER, command);

        // Then
        assertThat(result).isEqualTo(Result.failure(
                new ApproveSubmissionError.SubmissionNotFound(otherTenantSubmissionId)));
    }

    @Test
    @DisplayName("an underwriter can approve an existing submission")
    void shouldApproveSubmissionWhenCallerIsAllowed() {
        // Given
        var command = new ApproveSubmissionCommand(SUBMISSION_ID);

        // When
        var result = useCase.execute(UNDERWRITER, command);

        // Then
        assertThat(result).isEqualTo(Result.success(SUBMISSION_ID));
        assertThat(eventPublisher.publishedEvents()).hasSize(1);
        assertThat(eventPublisher.publishedEvents().getFirst())
                .isInstanceOf(SubmissionDomainEvent.SubmissionApproved.class);
    }

    @Test
    @DisplayName("approval is refused when the submission is already approved")
    void shouldRefuseApprovalWhenSubmissionAlreadyApproved() {
        // Given
        approvalRepository.approve(TENANT_A, SUBMISSION_ID, UNDERWRITER, APPROVED_AT);
        var command = new ApproveSubmissionCommand(SUBMISSION_ID);

        // When
        var result = useCase.execute(SENIOR_UNDERWRITER, command);

        // Then
        assertThat(result).isEqualTo(Result.failure(
                new ApproveSubmissionError.SubmissionAlreadyApproved(SUBMISSION_ID)));
    }

    private static final class InMemorySubmissionApprovalRepository implements SubmissionApprovalRepository {

        private final Map<UUID, SubmissionApproval> approvals = new ConcurrentHashMap<>();

        @Override
        public boolean isApproved(UUID tenantId, SubmissionId submissionId) {
            return approvals.containsKey(submissionId.value());
        }

        @Override
        public Result<SubmissionId, ApproveSubmissionError> approve(
                UUID tenantId,
                SubmissionId submissionId,
                ActorContext actor,
                Instant approvedAt) {
            var existing = approvals.putIfAbsent(submissionId.value(),
                    new SubmissionApproval(submissionId.value(), tenantId, actor.actorId(), approvedAt));
            return existing == null
                    ? Result.success(submissionId)
                    : Result.failure(new ApproveSubmissionError.SubmissionAlreadyApproved(submissionId));
        }

        private record SubmissionApproval(UUID submissionId, UUID tenantId, String approvedBy, Instant approvedAt) { }
    }
}
