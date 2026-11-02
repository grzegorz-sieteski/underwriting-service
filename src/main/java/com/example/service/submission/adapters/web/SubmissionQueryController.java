package com.example.service.submission.adapters.web;

import module java.base;

import com.example.service.shared.kernel.ActorContext;
import com.example.service.shared.kernel.Result;
import com.example.service.submission.query.SearchSubmissionsError;
import com.example.service.submission.query.SearchSubmissionsQuery;
import com.example.service.submission.query.SubmissionPageView;
import com.example.service.submission.query.SubmissionQueryFacade;
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
 * before the facade is reached, as a transport-level {@code 400}. That is not a business refusal
 * and deliberately does not become a second {@link SearchSubmissionsError} variant.
 *
 * <p>This controller touches nothing on the write side, which is an architecture rule and not a
 * convention: a query endpoint talks to a {@code *QueryFacade} and nothing else.
 */
@RestController
@RequestMapping("/api/submissions")
class SubmissionQueryController {

    private static final String PROBLEM_TYPE_PREFIX = "urn:problem-type:submission:";

    private final SubmissionQueryFacade facade;
    private final ActorContextResolver actorContextResolver;

    SubmissionQueryController(SubmissionQueryFacade facade,
                              ActorContextResolver actorContextResolver) {
        this.facade = Objects.requireNonNull(facade, "facade");
        this.actorContextResolver =
                Objects.requireNonNull(actorContextResolver, "actorContextResolver");
    }

    @GetMapping
    ResponseEntity<Object> search(
            @RequestParam(required = false) @Nullable String reference,
            @RequestParam(required = false) @Nullable BigDecimal insuredLimitFrom,
            @RequestParam(required = false) @Nullable BigDecimal insuredLimitTo,
            @RequestParam(required = false) @Nullable Instant registeredFrom,
            @RequestParam(required = false) @Nullable Instant registeredTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        var query = new SearchSubmissionsQuery(reference, insuredLimitFrom, insuredLimitTo,
                registeredFrom, registeredTo, page, size);
        return searchAsResolvedCaller(query)
                .fold(SubmissionQueryController::found, SubmissionQueryController::refused);
    }

    private Result<SubmissionPageView, SearchSubmissionsError> searchAsResolvedCaller(
            SearchSubmissionsQuery query) {
        return actorContextResolver.resolve()
                .map(actor -> ActorContext.callWith(actor, () -> facade.search(query)))
                .orElseGet(() -> facade.search(query));
    }

    private static ResponseEntity<Object> found(SubmissionPageView page) {
        return ResponseEntity.ok(page);
    }

    /**
     * Exhaustive over the one refusal reason Search has, with no {@code default} branch. Matching
     * nothing, a contradictory range and a page past the end are all successes and never arrive
     * here.
     */
    private static ResponseEntity<Object> refused(SearchSubmissionsError error) {
        var problem = switch (error) {
            case SearchSubmissionsError.CallerNotIdentified _ -> problemDetail(
                    HttpStatus.UNAUTHORIZED, "caller-not-identified",
                    "The caller could not be identified.");
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
