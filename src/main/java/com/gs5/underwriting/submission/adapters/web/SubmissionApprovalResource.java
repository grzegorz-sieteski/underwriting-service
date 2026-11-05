package com.gs5.underwriting.submission.adapters.web;

import module java.base;

import com.gs5.underwriting.shared.kernel.ActorContext;
import com.gs5.underwriting.shared.kernel.Result;
import com.gs5.underwriting.submission.adapters.access.AccessRefusal;
import com.gs5.underwriting.submission.adapters.access.OperationGate;
import com.gs5.underwriting.submission.adapters.access.Permission;
import com.gs5.underwriting.submission.domain.ApproveSubmissionCommand;
import com.gs5.underwriting.submission.domain.ApproveSubmissionError;
import com.gs5.underwriting.submission.domain.ApproveSubmissionUseCase;
import com.gs5.underwriting.submission.domain.SubmissionId;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/submissions")
class SubmissionApprovalController {

    private static final String PROBLEM_TYPE_PREFIX = "urn:problem-type:submission:";

    private final ApproveSubmissionUseCase useCase;
    private final ActorContextResolver actorContextResolver;
    private final OperationGate<ApproveSubmissionCommand,
            Result<SubmissionId, ApproveSubmissionError>> gate;

    SubmissionApprovalController(ApproveSubmissionUseCase useCase,
                                ActorContextResolver actorContextResolver,
                                @Qualifier("approvalPermission") Permission approvalPermission) {
        this.useCase = useCase;
        this.actorContextResolver = actorContextResolver;
        this.gate = new OperationGate<>(approvalPermission, this::approveForActor);
    }

    @PostMapping("/{id}/approve")
    ResponseEntity<Object> approve(@PathVariable UUID id) {
        var command = new ApproveSubmissionCommand(new SubmissionId(id));
        return gate.admit(actorContextResolver.resolve())
                .fold(admission -> admission.run(command)
                                .fold(SubmissionApprovalController::approved,
                                        SubmissionApprovalController::refused),
                        SubmissionApprovalController::refusedAccess);
    }

    private Result<SubmissionId, ApproveSubmissionError> approveForActor(
            ActorContext actor, ApproveSubmissionCommand command) {
        return useCase.execute(actor, command);
    }

    private static ResponseEntity<Object> approved(SubmissionId id) {
        return ResponseEntity.ok(new ApprovedSubmissionResponse(id.value()));
    }

    private static ResponseEntity<Object> refused(ApproveSubmissionError error) {
        var problem = switch (error) {
            case ApproveSubmissionError.SubmissionNotFound(var submissionId) -> problemDetail(
                    HttpStatus.NOT_FOUND, "submission-not-found",
                    "Submission " + submissionId.value() + " was not found.");
            case ApproveSubmissionError.SubmissionAlreadyApproved(var submissionId) -> problemDetail(
                    HttpStatus.CONFLICT, "submission-already-approved",
                    "Submission " + submissionId.value() + " is already approved.");
        };
        return ResponseEntity.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private static ResponseEntity<Object> refusedAccess(AccessRefusal refusal) {
        var problem = switch (refusal) {
            case AccessRefusal.CallerNotIdentified _ -> problemDetail(
                    HttpStatus.UNAUTHORIZED, "caller-not-identified",
                    "The caller could not be identified.");
            case AccessRefusal.PermissionDenied(var role) -> problemDetail(
                    HttpStatus.FORBIDDEN, "permission-denied",
                    "The role " + role + " may not approve a submission.");
        };
        return ResponseEntity.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private static ProblemDetail problemDetail(HttpStatus status, String code, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(PROBLEM_TYPE_PREFIX + code));
        problem.setTitle(status.getReasonPhrase());
        return problem;
    }
}
