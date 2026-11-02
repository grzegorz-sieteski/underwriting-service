package com.example.service.submission.adapters.web;

import module java.base;

import com.example.service.shared.kernel.ActorContext;
import com.example.service.shared.kernel.Result;
import com.example.service.submission.domain.RegisterSubmissionCommand;
import com.example.service.submission.domain.RegisterSubmissionError;
import com.example.service.submission.domain.SubmissionCommandFacade;
import com.example.service.submission.domain.SubmissionId;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/submissions} — the write endpoint.
 *
 * <p>Two things about the shape of this class are load-bearing.
 *
 * <p><strong>The actor is bound around the facade call, fail-closed.</strong> When
 * {@link ActorContextResolver} names a caller, the facade runs inside
 * {@code ActorContext.callWith(actor, ...)}; when it does not, the facade is called
 * <em>unbound</em>, so {@code CallerNotIdentified} is produced in the domain, in one place, for web
 * and non-web callers alike. The controller does not decide BR-01.
 *
 * <p><strong>The fold is an exhaustive switch with no {@code default} branch,</strong> so the
 * compiler reports a future ninth refusal reason instead of a 500 appearing in production.
 */
@RestController
@RequestMapping("/api/submissions")
class SubmissionCommandController {

    private static final String PROBLEM_TYPE_PREFIX = "urn:problem-type:submission:";

    private final SubmissionCommandFacade facade;
    private final ActorContextResolver actorContextResolver;

    SubmissionCommandController(SubmissionCommandFacade facade,
                                ActorContextResolver actorContextResolver) {
        this.facade = Objects.requireNonNull(facade, "facade");
        this.actorContextResolver =
                Objects.requireNonNull(actorContextResolver, "actorContextResolver");
    }

    @PostMapping
    ResponseEntity<Object> register(@RequestBody RegisterSubmissionRequest request) {
        var command = new RegisterSubmissionCommand(request.externalRef(), request.insuredLimit());
        return registerAsResolvedCaller(command)
                .fold(SubmissionCommandController::created,
                        SubmissionCommandController::refused);
    }

    private Result<SubmissionId, RegisterSubmissionError> registerAsResolvedCaller(
            RegisterSubmissionCommand command) {
        return actorContextResolver.resolve()
                .map(actor -> ActorContext.callWith(actor, () -> facade.register(command)))
                .orElseGet(() -> facade.register(command));
    }

    private static ResponseEntity<Object> created(SubmissionId id) {
        return ResponseEntity
                .created(URI.create("/api/submissions/" + id.value()))
                .body(new RegisteredSubmissionResponse(id.value()));
    }

    /**
     * Every {@code ProblemDetail} here carries a stable type and a user-safe message: no stack
     * trace, no SQL fragment, no internal id, <strong>no insured limit</strong> (BR-22) and nothing
     * that reveals another organisation's data. The three refusals that are <em>about</em> the
     * amount name the reason and never show the value, not even rounded.
     *
     * <p>{@code ReferenceAlreadyUsed} is safe to answer with {@code 409} precisely because
     * uniqueness never crosses organisations (BR-08): the conflicting submission is always one of
     * the caller's own.
     */
    private static ResponseEntity<Object> refused(RegisterSubmissionError error) {
        var problem = switch (error) {
            case RegisterSubmissionError.CallerNotIdentified _ -> problemDetail(
                    HttpStatus.UNAUTHORIZED, "caller-not-identified",
                    "The caller could not be identified.");
            case RegisterSubmissionError.RoleMayNotRegister(var role) -> problemDetail(
                    HttpStatus.FORBIDDEN, "role-may-not-register",
                    "The role " + role + " may not register a submission.");
            case RegisterSubmissionError.ReferenceMissing _ -> problemDetail(
                    HttpStatus.BAD_REQUEST, "reference-missing",
                    "externalRef is required and must contain at least one character.");
            case RegisterSubmissionError.ReferenceTooLong(var maxLength, var actualLength) ->
                    problemDetail(HttpStatus.BAD_REQUEST, "reference-too-long",
                            "externalRef must be at most " + maxLength
                                    + " characters, but was " + actualLength + ".");
            // ADDED BY @test-designer, 2026-11-02, OUTSIDE ITS WRITE BOUNDARY — see the report.
            // BR-29's ninth variant made this switch non-exhaustive and therefore made the whole
            // module uncompilable, which is the no-default design working as intended. This branch
            // is the minimum needed to restore compilation; it invents no mapping, since 400 is
            // what every other malformed-reference refusal already answers. @developer owns it and
            // should review the wording. The code point is rendered as a NUMBER (U+XXXX): putting
            // the character itself in a ProblemDetail is what the variant exists to prevent.
            case RegisterSubmissionError.ReferenceContainsControlCharacter(
                    var codePoint, var index) -> problemDetail(
                            HttpStatus.BAD_REQUEST, "reference-contains-control-character",
                            "externalRef must not contain a control character, but "
                                    + String.format("U+%04X", codePoint)
                                    + " was found at index " + index + ".");
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
            case RegisterSubmissionError.ReferenceAlreadyUsed(var externalRef) -> problemDetail(
                    HttpStatus.CONFLICT, "reference-already-used",
                    "The reference '" + externalRef + "' is already used by a submission of your "
                            + "organisation.");
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
