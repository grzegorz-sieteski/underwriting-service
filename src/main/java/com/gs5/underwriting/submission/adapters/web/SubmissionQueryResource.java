package com.gs5.underwriting.submission.adapters.web;

import module java.base;

import com.gs5.underwriting.shared.kernel.ActorContext;
import com.gs5.underwriting.submission.adapters.access.AccessRefusal;
import com.gs5.underwriting.submission.adapters.access.OperationGate;
import com.gs5.underwriting.submission.adapters.access.Permission;
import com.gs5.underwriting.submission.domain.SearchSubmissionsQuery;
import com.gs5.underwriting.submission.domain.SubmissionPageView;
import com.gs5.underwriting.submission.domain.SearchSubmissionsUseCase;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/submissions} — the read endpoint. Five optional filters (BR-19) combined with
 * AND (BR-18), plus a page request.
 *
 * <p><strong>The transport defaults are {@code page=0} and {@code size=100}.</strong> Zero is the
 * first page, and "size not stated" means "as much as is allowed" — which keeps the design to one
 * page-size number instead of inventing a second default the business never saw. Both are clamped
 * again inside {@link SearchSubmissionsQuery}, so the response always echoes what was applied.
 *
 * <p>A malformed page parameter such as {@code size=abc} is rejected by Spring's type conversion
 * as a transport-level {@code 400}. It is not a business refusal.
 *
 * <p>This controller touches nothing on the write side, which is an architecture rule and not a
 * convention: a query endpoint talks to a {@code *UseCase} and nothing else.
 */
@RestController
@RequestMapping("/api/submissions")
public class SubmissionQueryResource {

    private static final String PROBLEM_TYPE_PREFIX = "urn:problem-type:submission:";

    private final SearchSubmissionsUseCase useCase;
    private final ActorContextResolver actorContextResolver;
    private final OperationGate<RawSearchRequest, SubmissionPageView> gate;

    public SubmissionQueryResource(SearchSubmissionsUseCase useCase,
                              ActorContextResolver actorContextResolver) {
        this.useCase = useCase;
        this.actorContextResolver = actorContextResolver;
        this.gate = new OperationGate<>(Permission.anyIdentifiedCaller(), this::searchForActor);
    }

    @GetMapping
    ResponseEntity<Object> search(
            @RequestParam(required = false) @Nullable String submissionNumber,
            @RequestParam(required = false) @Nullable BigDecimal insuredLimitFrom,
            @RequestParam(required = false) @Nullable BigDecimal insuredLimitTo,
            @RequestParam(required = false) @Nullable Instant registeredFrom,
            @RequestParam(required = false) @Nullable Instant registeredTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        var request = new RawSearchRequest(submissionNumber, insuredLimitFrom, insuredLimitTo,
                registeredFrom, registeredTo, page, size);
        return gate.admit(actorContextResolver.resolve())
                .fold(admission -> found(admission.run(request)),
                        SubmissionQueryResource::refused);
    }

    private SubmissionPageView searchForActor(ActorContext actor, RawSearchRequest request) {
        return useCase.ask(actor, SearchSubmissionsQuery.parse(request.submissionNumber(),
                request.insuredLimitFrom(), request.insuredLimitTo(), request.registeredFrom(),
                request.registeredTo(), request.page(), request.size()));
    }

    private static ResponseEntity<Object> found(SubmissionPageView page) {
        return ResponseEntity.ok(page);
    }

    /**
     * Exhaustive over the one refusal reason Search has, with no {@code default} branch. Matching
     * nothing, a contradictory range and a page past the end are all successes and never arrive
     * here.
     */
    private static ResponseEntity<Object> refused(AccessRefusal refusal) {
        var problem = switch (refusal) {
            case AccessRefusal.CallerNotIdentified _ -> problemDetail(
                    HttpStatus.UNAUTHORIZED, "caller-not-identified",
                    "The caller could not be identified.");
            case AccessRefusal.PermissionDenied _ -> problemDetail(
                    HttpStatus.FORBIDDEN, "permission-denied",
                    "The caller may not perform this operation.");
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

    private record RawSearchRequest(@Nullable String submissionNumber,
                                    @Nullable BigDecimal insuredLimitFrom,
                                    @Nullable BigDecimal insuredLimitTo,
                                    @Nullable Instant registeredFrom,
                                    @Nullable Instant registeredTo,
                                    int page,
                                    int size) { }
}
