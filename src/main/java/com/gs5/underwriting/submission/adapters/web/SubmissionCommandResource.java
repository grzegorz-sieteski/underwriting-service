package com.gs5.underwriting.submission.adapters.web;

import module java.base;

import com.gs5.underwriting.shared.kernel.LogSafeText;

import com.gs5.underwriting.shared.kernel.ActorContext;
import com.gs5.underwriting.shared.kernel.Result;
import com.gs5.underwriting.submission.adapters.access.AccessRefusal;
import com.gs5.underwriting.submission.adapters.access.OperationGate;
import com.gs5.underwriting.submission.adapters.access.Permission;
import com.gs5.underwriting.submission.domain.RegisterSubmissionCommand;
import com.gs5.underwriting.submission.domain.RegisterSubmissionError;
import com.gs5.underwriting.submission.domain.RegisterSubmissionUseCase;
import com.gs5.underwriting.submission.domain.SubmissionNumber;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * {@code POST /api/submissions} — the write endpoint.
 *
 * <p>Two things about the shape of this class are load-bearing.
 *
 * <p><strong>The access gate runs before parsing.</strong> It returns an {@link
 * com.gs5.underwriting.submission.adapters.access.Admission} only after identifying the caller
 * and checking the role. The request body is parsed inside that admitted operation.
 *
 * <p><strong>Both folds are exhaustive switches with no {@code default} branch,</strong> so the
 * compiler reports a new business or access refusal instead of a 500 appearing in production.
 */
@RestController
@RequestMapping("/api/submissions")
class SubmissionCommandResource {

    private static final String PROBLEM_TYPE_PREFIX = "urn:problem-type:submission:";

    private final RegisterSubmissionUseCase useCase;
    private final ActorContextResolver actorContextResolver;
    private final OperationGate<RegisterSubmissionRequest,
            Result<SubmissionNumber, RegisterSubmissionError>> gate;

    SubmissionCommandResource(RegisterSubmissionUseCase useCase,
                                ActorContextResolver actorContextResolver,
                                Permission registrationPermission) {
        this.useCase = useCase;
        this.actorContextResolver = actorContextResolver;
        this.gate = new OperationGate<>(registrationPermission, this::registerForActor);
    }

    @PostMapping
    ResponseEntity<Object> register(@RequestBody RegisterSubmissionRequest request) {
        return gate.admit(actorContextResolver.resolve())
                .fold(admission -> admission.run(request)
                                .fold(SubmissionCommandResource::created,
                                        SubmissionCommandResource::refused),
                        SubmissionCommandResource::refusedAccess);
    }

    private Result<SubmissionNumber, RegisterSubmissionError> registerForActor(
            ActorContext actor, RegisterSubmissionRequest request) {
        return RegisterSubmissionCommand.parse(request.submissionNumber(), request.insuredLimit())
                .flatMap(command -> useCase.execute(actor, command)
                        .map(id -> command.submissionNumber()));
    }

    private static ResponseEntity<Object> created(SubmissionNumber number) {
        return ResponseEntity
                .created(UriComponentsBuilder.fromPath("/api/submissions/{number}")
                .buildAndExpand(number.value()).toUri())
                .body(new RegisteredSubmissionResponse(number.value()));
    }

    /**
     * Every {@code ProblemDetail} here carries a stable type and a user-safe message: no stack
     * trace, no SQL fragment, no internal id, <strong>no insured limit</strong> (BR-22) and nothing
     * that reveals another organisation's data. The three refusals that are <em>about</em> the
     * amount name the reason and never show the value, not even rounded.
     *
     * <p>{@code NumberAlreadyUsed} is safe to answer with {@code 409} precisely because
     * uniqueness never crosses organisations (BR-08): the conflicting submission is always one of
     * the caller's own.
     */
    private static ResponseEntity<Object> refused(RegisterSubmissionError error) {
        var problem = switch (error) {
            case RegisterSubmissionError.NumberMissing _ -> problemDetail(
                    HttpStatus.BAD_REQUEST, "number-missing",
                    "submissionNumber is required and must contain at least one character.");
            case RegisterSubmissionError.NumberTooLong(var maxLength, var actualLength) ->
                    problemDetail(HttpStatus.BAD_REQUEST, "number-too-long",
                            "submissionNumber must be at most " + maxLength
                                    + " characters, but was " + actualLength + ".");
            // BR-29: 400 like every other malformed-number refusal. The code point is rendered as
            // a number (U+XXXX) and the position as a code point index, never the raw character.
            case RegisterSubmissionError.NumberContainsControlCharacter(
                    var codePoint, var index) -> problemDetail(
                            HttpStatus.BAD_REQUEST, "number-contains-control-character",
                            "submissionNumber must not contain a control character, but "
                                    + String.format(Locale.ROOT, "U+%04X", codePoint)
                                    + " was found at code point index " + index + ".");
            case RegisterSubmissionError.InsuredLimitMissing _ -> problemDetail(
                    HttpStatus.BAD_REQUEST, "insured-limit-missing",
                    "insuredLimit is required.");
            case RegisterSubmissionError.InsuredLimitNotPositive _ -> problemDetail(
                    HttpStatus.BAD_REQUEST, "insured-limit-not-positive",
                    "insuredLimit must be greater than zero.");
            case RegisterSubmissionError.InsuredLimitPrecisionInvalid(
                    var requiredScale, var actualScale) -> problemDetail(
                            HttpStatus.BAD_REQUEST, "insured-limit-precision-invalid",
                            "insuredLimit must have exactly " + requiredScale
                                    + " decimal places, but had " + actualScale + ".");
            case RegisterSubmissionError.NumberAlreadyUsed(var submissionNumber) -> problemDetail(
                    HttpStatus.CONFLICT, "number-already-used",
                    "The submission number '" + LogSafeText.escapeControlCharacters(submissionNumber) + "' is already used by a submission of your "
                            + "organisation.");
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
                    "The role " + role + " may not register a submission.");
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
