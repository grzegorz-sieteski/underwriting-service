package com.gs5.underwriting.submission.adapters.web;

import module java.base;

import com.gs5.underwriting.shared.kernel.ActorContext;
import com.gs5.underwriting.shared.kernel.Result;
import com.gs5.underwriting.submission.adapters.access.Admission;
import com.gs5.underwriting.submission.adapters.access.AccessRefusal;
import com.gs5.underwriting.submission.adapters.access.OperationGate;
import com.gs5.underwriting.submission.adapters.access.Permission;
import com.gs5.underwriting.submission.domain.ApproveSubmissionCommand;
import com.gs5.underwriting.submission.domain.ApproveSubmissionError;
import com.gs5.underwriting.submission.domain.ApproveSubmissionUseCase;
import com.gs5.underwriting.submission.domain.SubmissionNumber;
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

@RestController
@RequestMapping("/api/submissions")
class SubmissionApprovalResource {

    private static final String PROBLEM_TYPE_PREFIX = "urn:problem-type:submission:";
    /** A constant instance, so no problem detail copies the request path (BR-47). */
    private static final String APPROVAL_INSTANCE = "/api/submissions/approval";

    private final ApproveSubmissionUseCase useCase;
    private final ActorContextResolver actorContextResolver;
    private final OperationGate<ApproveSubmissionCommand,
            Result<SubmissionNumber, ApproveSubmissionError>> gate;

    SubmissionApprovalResource(ApproveSubmissionUseCase useCase,
                               ActorContextResolver actorContextResolver,
                               @Qualifier("approvalPermission") Permission approvalPermission) {
        this.useCase = useCase;
        this.actorContextResolver = actorContextResolver;
        this.gate = new OperationGate<>(approvalPermission, this::approveForActor);
    }

    /**
     * The submission number arrives as text and is parsed only after the caller is admitted, so a
     * Viewer naming any number is refused by role (BR-33). A text that cannot be a number is then
     * refused exactly as an unknown one is (BR-35, BR-48).
     */
    @PostMapping("/{number}/approve")
    ResponseEntity<Object> approve(@PathVariable String number) {
        return gate.admit(actorContextResolver.resolve())
                .fold(admission -> approveAdmitted(admission, number),
                        access -> refused(new ApproveRefusal.Access(access)));
    }

    private Result<SubmissionNumber, ApproveSubmissionError> approveForActor(
            ActorContext actor, ApproveSubmissionCommand command) {
        return useCase.execute(actor, command);
    }

    private static ResponseEntity<Object> approveAdmitted(
            Admission<ApproveSubmissionCommand, Result<SubmissionNumber, ApproveSubmissionError>> admission,
            String number) {
        return ApproveSubmissionCommand.parse(number)
                .fold(command -> admission.run(command)
                                .fold(SubmissionApprovalResource::approved,
                                        error -> refused(new ApproveRefusal.Rejected(error))),
                        error -> refused(new ApproveRefusal.Rejected(error)));
    }

    private static ResponseEntity<Object> approved(SubmissionNumber number) {
        return ResponseEntity.ok(new ApprovedSubmissionResponse(number.value()));
    }

    /** The one exhaustive mapping of every approval refusal (design section 19.3, D10). */
    private static ResponseEntity<Object> refused(ApproveRefusal refusal) {
        var problem = switch (refusal) {
            case ApproveRefusal.Access(AccessRefusal.CallerNotIdentified _) -> problemDetail(
                    HttpStatus.UNAUTHORIZED, "caller-not-identified",
                    "The caller could not be identified.");
            case ApproveRefusal.Access(AccessRefusal.PermissionDenied(var role)) -> problemDetail(
                    HttpStatus.FORBIDDEN, "permission-denied",
                    "The role " + role + " may not approve a submission.");
            case ApproveRefusal.Rejected(ApproveSubmissionError.SubmissionNotFound _) -> problemDetail(
                    HttpStatus.NOT_FOUND, "submission-not-found",
                    "The submission was not found.");
            case ApproveRefusal.Rejected(ApproveSubmissionError.ApproverRegisteredSubmission _) -> problemDetail(
                    HttpStatus.FORBIDDEN, "approver-registered-submission",
                    "The caller registered this submission and may not approve it.");
            case ApproveRefusal.Rejected(ApproveSubmissionError.SubmissionAlreadyApproved _) -> problemDetail(
                    HttpStatus.CONFLICT, "submission-already-approved",
                    "The submission is already approved.");
        };
        return problemResponse(problem);
    }

    private static ResponseEntity<Object> problemResponse(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    /**
     * {@code instance} is set to a constant, not left to Spring, which would copy the request path
     * and so echo the submission number back (BR-47, D6).
     */
    private static ProblemDetail problemDetail(HttpStatus status, String code, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(PROBLEM_TYPE_PREFIX + code));
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(URI.create(APPROVAL_INSTANCE));
        return problem;
    }
}
