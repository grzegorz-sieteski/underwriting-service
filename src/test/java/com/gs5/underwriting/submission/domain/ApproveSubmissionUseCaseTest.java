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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class ApproveSubmissionUseCaseTest {

    private static final UUID TENANT_A = UUID.fromString("0a000000-0000-0000-0000-00000000000a");
    private static final Instant APPROVED_AT = Instant.parse("2026-11-02T12:00:00Z");

    /** Registered the submission in setUp, so may not approve it (BR-41). */
    private static final ActorContext UNDERWRITER =
            new ActorContext(TENANT_A, "uw-01", ActorRole.UNDERWRITER);
    /** Did not register the submission, so may approve it. */
    private static final ActorContext OTHER_UNDERWRITER =
            new ActorContext(TENANT_A, "uw-02", ActorRole.UNDERWRITER);
    private static final ActorContext SENIOR_UNDERWRITER =
            new ActorContext(TENANT_A, "suw-01", ActorRole.SENIOR_UNDERWRITER);
    private static final SubmissionNumber NUMBER = number("REF-1");

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
                eventPublisher, new FixedSubmissionIdGenerator(), Clock.fixed(APPROVED_AT, ZoneOffset.UTC));

        commandRepository.insert(new SubmissionSnapshot(UUID.randomUUID(), TENANT_A,
                NUMBER.value(), new BigDecimal("100.00"), "uw-01",
                Instant.parse("2026-11-01T09:00:00Z"), 0L));
    }

    @Test
    @DisplayName("SC-54: approval is refused when no submission of the caller's organisation has the number (BR-35)")
    void shouldRefuseApprovalWhenSubmissionIsNotFound() {
        // Given
        var command = new ApproveSubmissionCommand(number("OTHER-1"));

        // When
        var result = useCase.execute(UNDERWRITER, command);

        // Then
        assertThat(result).isEqualTo(Result.failure(new ApproveSubmissionError.SubmissionNotFound()));
    }

    @Test
    @DisplayName("SC-53: an underwriter who did not register the submission can approve it, and the result names it by its number")
    void shouldApproveSubmissionWhenCallerIsAllowed() {
        // Given
        var command = new ApproveSubmissionCommand(NUMBER);

        // When
        var result = useCase.execute(OTHER_UNDERWRITER, command);

        // Then
        assertThat(result.toOptional()).map(SubmissionNumber::value).contains(NUMBER.value());
        assertThat(eventPublisher.publishedEvents()).hasSize(1);
        assertThat(eventPublisher.publishedEvents().getFirst())
                .isInstanceOf(SubmissionDomainEvent.SubmissionApproved.class);
    }

    @Test
    @DisplayName("SC-60: approval is refused when the submission is already approved")
    void shouldRefuseApprovalWhenSubmissionAlreadyApproved() {
        // Given
        approvalRepository.approve(TENANT_A, registeredId(), OTHER_UNDERWRITER, APPROVED_AT);
        var command = new ApproveSubmissionCommand(NUMBER);

        // When
        var result = useCase.execute(SENIOR_UNDERWRITER, command);

        // Then
        assertThat(result).isEqualTo(Result.failure(new ApproveSubmissionError.SubmissionAlreadyApproved()));
    }

    @Test
    @DisplayName("SC-58: the registrant of a submission may not approve it, and nothing is recorded or announced (BR-41)")
    void shouldRefuseSelfApprovalAndRecordNothing() {
        // Given
        var command = new ApproveSubmissionCommand(NUMBER);

        // When
        var result = useCase.execute(UNDERWRITER, command);

        // Then
        assertThat(refusalNameOf(result)).isEqualTo("ApproverRegisteredSubmission");
        assertThat(approvalRepository.approvalCount()).isZero();
        assertThat(eventPublisher.publishedEvents()).isEmpty();
    }

    @Test
    @DisplayName("SC-59: self-approval is refused before already-approved, so an earlier approval does not change the answer (BR-41, BR-34)")
    void shouldRefuseSelfApprovalBeforeAlreadyApproved() {
        // Given
        approvalRepository.approve(TENANT_A, registeredId(), SENIOR_UNDERWRITER, APPROVED_AT);
        var command = new ApproveSubmissionCommand(NUMBER);

        // When
        var result = useCase.execute(UNDERWRITER, command);

        // Then
        assertThat(refusalNameOf(result)).isEqualTo("ApproverRegisteredSubmission");
    }

    @Test
    @DisplayName("SC-63: a stored row whose amount no longer parses is reported as not found, never approved (design 19.4, D12)")
    void shouldReportAStoredRowThatFailsToParseAsNotFound() {
        // Given: a row the schema would not admit today (three decimal places, BR-12)
        commandRepository.insert(new SubmissionSnapshot(UUID.randomUUID(), TENANT_A,
                "REF-2", new BigDecimal("100.123"), "uw-01",
                Instant.parse("2026-11-01T09:00:00Z"), 0L));
        var command = new ApproveSubmissionCommand(number("REF-2"));

        // When
        var result = useCase.execute(OTHER_UNDERWRITER, command);

        // Then
        assertThat(result).isEqualTo(Result.failure(new ApproveSubmissionError.SubmissionNotFound()));
        assertThat(eventPublisher.publishedEvents()).isEmpty();
    }

    private static SubmissionNumber number(String text) {
        return SubmissionNumber.parse(text).toOptional().orElseThrow();
    }

    /** The internal id of the registered submission, as the approval store sees it. */
    private SubmissionId registeredId() {
        return commandRepository.findOwnByNumber(TENANT_A, NUMBER)
                .map(snapshot -> new SubmissionId(snapshot.id()))
                .orElseThrow();
    }

    /**
     * The simple name of the refusal, or "no refusal". Names the variant without referring to it,
     * so this test compiles before the variant exists and fails for the right reason.
     */
    private static String refusalNameOf(Result<SubmissionNumber, ApproveSubmissionError> result) {
        return result.errorToOptional()
                .map(error -> error.getClass().getSimpleName())
                .orElse("no refusal");
    }

    private static final class InMemorySubmissionApprovalRepository implements SubmissionApprovalRepository {

        private final Map<UUID, SubmissionApproval> approvals = new ConcurrentHashMap<>();

        int approvalCount() {
            return approvals.size();
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
                    : Result.failure(new ApproveSubmissionError.SubmissionAlreadyApproved());
        }

        private record SubmissionApproval(UUID submissionId, UUID tenantId, String approvedBy, Instant approvedAt) { }
    }
}
