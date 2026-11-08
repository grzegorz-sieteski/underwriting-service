package com.gs5.underwriting.submission.adapters.web;

import com.gs5.underwriting.shared.kernel.ActorContext;
import com.gs5.underwriting.shared.kernel.ActorRole;
import com.gs5.underwriting.shared.kernel.Result;
import com.gs5.underwriting.submission.adapters.access.AccessPolicy;
import com.gs5.underwriting.submission.adapters.access.AuthenticatedCaller;
import com.gs5.underwriting.submission.adapters.access.Permission;
import com.gs5.underwriting.submission.domain.ApproveSubmissionError;
import com.gs5.underwriting.submission.domain.ApproveSubmissionUseCase;
import com.gs5.underwriting.submission.domain.FixedSubmissionIdGenerator;
import com.gs5.underwriting.submission.domain.RegisterSubmissionError;
import com.gs5.underwriting.submission.domain.SubmissionApprovalRepository;
import com.gs5.underwriting.submission.domain.SubmissionCommandRepository;
import com.gs5.underwriting.submission.domain.SubmissionDomainEvent;
import com.gs5.underwriting.submission.domain.SubmissionEventPublisher;
import com.gs5.underwriting.submission.domain.SubmissionId;
import com.gs5.underwriting.submission.domain.SubmissionNumber;
import com.gs5.underwriting.submission.domain.SubmissionSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The approval endpoint at the HTTP edge, addressed by submission number (BR-47, BR-48): the real
 * resource, the real use case over in-memory ports, and a resolver that names whichever caller the
 * test chooses. No Spring context, no database.
 *
 * <p>Findings pinned here:
 * <ul>
 *   <li>A Viewer naming any number is refused by role, before the number is parsed (BR-33).</li>
 *   <li>A text that cannot be a number, an unknown number and another organisation's number produce
 *       one body, with no echo of the input (BR-35, BR-48).</li>
 *   <li>The registrant is refused with its own problem type, before already-approved (BR-41).</li>
 *   <li>A success names the submission by its number only (BR-47).</li>
 * </ul>
 */
class SubmissionApprovalResourceTest {

    private static final UUID TENANT_A = UUID.fromString("0a000000-0000-0000-0000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("0b000000-0000-0000-0000-00000000000b");
    private static final Instant REGISTERED_AT = Instant.parse("2026-11-01T09:00:00Z");
    private static final Instant APPROVED_AT = Instant.parse("2026-11-02T12:00:00Z");

    private static final ActorContext OTHER_UNDERWRITER =
            new ActorContext(TENANT_A, "uw-02", ActorRole.UNDERWRITER);
    private static final ActorContext SENIOR_UNDERWRITER =
            new ActorContext(TENANT_A, "suw-01", ActorRole.SENIOR_UNDERWRITER);
    private static final ActorContext VIEWER = new ActorContext(TENANT_A, "viewer-01", ActorRole.VIEWER);

    private static final String PROBLEM_TYPE_PREFIX = "urn:problem-type:submission:";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private InMemoryStore store;
    private StubResolver resolver;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
        resolver = new StubResolver();
        var useCase = new ApproveSubmissionUseCase(store, store, store,
                new FixedSubmissionIdGenerator(), Clock.fixed(APPROVED_AT, ZoneOffset.UTC));
        var policy = new AccessPolicy() {
            @Override
            public boolean mayRegister(ActorRole role) {
                return false;
            }

            @Override
            public boolean mayApprove(ActorRole role) {
                return role.canDecide();
            }

            @Override
            public Optional<String> tenantIdFrom(Map<String, String> identityClaims) {
                return Optional.empty();
            }
        };
        var resource = new SubmissionApprovalResource(useCase, resolver,
                Permission.approval(policy));
        mvc = MockMvcBuilders.standaloneSetup(resource).build();

        // Registered by uw-01, who is therefore NOT a valid approver of it in the tests below.
        store.seed("REF-1", TENANT_A, "uw-01");
    }

    @Test
    @DisplayName("SC-57: a viewer naming any number is refused by role as 403, before the number is parsed (BR-33)")
    void aViewerNamingAnyNumberIsRefusedByRoleFirst() throws Exception {
        var result = approveAs(VIEWER, "bad\tnumber");

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(problemType(result)).isEqualTo(PROBLEM_TYPE_PREFIX + "permission-denied");
    }

    @Test
    @DisplayName("SC-56: a text that cannot be a number names no submission and approves nothing (BR-35, BR-48)")
    void aTextThatIsNotANumberNamesNoSubmission() throws Exception {
        var result = approveAs(OTHER_UNDERWRITER, "bad\tnumber");

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(store.approvalCount()).isZero();
        assertThat(store.publishedEvents()).isEmpty();
    }

    @Test
    @DisplayName("SC-56: a text that is not a number and an unknown number produce the same refusal, byte for byte, with no echo (BR-35, BR-47)")
    void aMalformedAndAnUnknownNumberProduceTheSameBody() throws Exception {
        var malformed = approveAs(OTHER_UNDERWRITER, "bad\tnumber");
        var unknown = approveAs(OTHER_UNDERWRITER, "UNKNOWN-1");

        assertThat(malformed.getResponse().getStatus()).isEqualTo(404);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(404);
        assertThat(malformed.getResponse().getContentAsString())
                .isEqualTo(unknown.getResponse().getContentAsString());
        assertThat(malformed.getResponse().getContentAsString()).doesNotContain("bad");
    }

    @Test
    @DisplayName("SC-55: another organisation's submission is refused with the same body as an unknown one (BR-35, BR-47)")
    void anotherOrganisationsSubmissionIsIndistinguishableFromAnUnknownOne() throws Exception {
        store.seed("OTHER-1", TENANT_B, "uw-09");

        var otherTenant = approveAs(OTHER_UNDERWRITER, "OTHER-1");
        var unknown = approveAs(OTHER_UNDERWRITER, "UNKNOWN-1");

        assertThat(otherTenant.getResponse().getStatus()).isEqualTo(404);
        assertThat(otherTenant.getResponse().getContentAsString())
                .isEqualTo(unknown.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("SC-58: an approver who registered the submission is refused as approver-registered-submission, and nothing is recorded (BR-41)")
    void anApproverWhoRegisteredTheSubmissionIsRefusedAndNothingIsRecorded() throws Exception {
        store.seed("MINE-1", TENANT_A, "uw-02");

        var result = approveAs(OTHER_UNDERWRITER, "MINE-1");

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(problemType(result)).isEqualTo(PROBLEM_TYPE_PREFIX + "approver-registered-submission");
        assertThat(store.approvalCount()).isZero();
        assertThat(store.publishedEvents()).isEmpty();
    }

    @Test
    @DisplayName("SC-59: the registrant is refused before already-approved, so an earlier approval does not change the answer (BR-41)")
    void theRegistrantIsRefusedBeforeAlreadyApproved() throws Exception {
        store.seed("MINE-1", TENANT_A, "uw-02");
        store.approve(TENANT_A, store.idOf("MINE-1"), SENIOR_UNDERWRITER, APPROVED_AT);

        var result = approveAs(OTHER_UNDERWRITER, "MINE-1");

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(problemType(result)).isEqualTo(PROBLEM_TYPE_PREFIX + "approver-registered-submission");
    }

    @Test
    @DisplayName("SC-53: guard: an underwriter who did not register the submission may approve it, once, and the response names it by its number only (BR-33, BR-40, BR-47)")
    void aNonRegistrantUnderwriterMayApprove() throws Exception {
        var result = approveAs(OTHER_UNDERWRITER, "REF-1");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("\"submissionNumber\":\"REF-1\"");
        assertThat(store.approvalCount()).isEqualTo(1);
        assertThat(store.publishedEvents()).hasSize(1);
    }

    @Test
    @DisplayName("SC-57: guard: a viewer naming a well-formed number is refused by role and records nothing (BR-33)")
    void aViewerIsRefusedByRoleForAWellFormedNumber() throws Exception {
        var result = approveAs(VIEWER, "REF-1");

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(problemType(result)).isEqualTo(PROBLEM_TYPE_PREFIX + "permission-denied");
        assertThat(store.approvalCount()).isZero();
    }

    @Test
    @DisplayName("SC-64: a path segment REF-1;x names no submission: the edge must not strip ';x' and "
            + "approve REF-1 instead, and nothing is recorded (BR-07, BR-35, BR-36, BR-48)")
    void aSemicolonInThePathDoesNotApproveAnotherSubmission() throws Exception {
        var result = approveAs(OTHER_UNDERWRITER, "REF-1;x");
        var unknown = approveAs(OTHER_UNDERWRITER, "UNKNOWN-1");

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentAsString())
                .isEqualTo(unknown.getResponse().getContentAsString());
        assertThat(store.approvalCount()).isZero();
        assertThat(store.publishedEvents()).isEmpty();
    }

    @Test
    @DisplayName("SC-64: a path segment ending in a bare ';' names no submission either")
    void aTrailingSemicolonInThePathDoesNotApproveAnotherSubmission() throws Exception {
        var result = approveAs(OTHER_UNDERWRITER, "REF-1;");

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(store.approvalCount()).isZero();
    }

    @Test
    @DisplayName("SC-65: a number carrying an encoded '/' names no submission and approves nothing")
    void anEncodedSlashInThePathNamesNoSubmission() throws Exception {
        var result = approveRawAs(OTHER_UNDERWRITER, "/api/submissions/REF-1%2Fx/approve");

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(store.approvalCount()).isZero();
    }

    @Test
    @DisplayName("SC-69: a second approval of the same submission is refused as 409 and records "
            + "nothing more (BR-36)")
    void aSecondApprovalIsRefusedAsAlreadyApproved() throws Exception {
        approveAs(OTHER_UNDERWRITER, "REF-1");

        var second = approveAs(SENIOR_UNDERWRITER, "REF-1");

        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        assertThat(problemType(second)).isEqualTo(PROBLEM_TYPE_PREFIX + "submission-already-approved");
        assertThat(store.approvalCount()).isEqualTo(1);
        assertThat(store.publishedEvents()).hasSize(1);
    }

    @Test
    @DisplayName("SC-68: an unidentified caller is refused as 401 and records nothing (BR-01)")
    void anUnidentifiedCallerIsRefused() throws Exception {
        resolver.caller = Optional.empty();

        var result = mvc.perform(post("/api/submissions/{number}/approve", "REF-1")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(store.approvalCount()).isZero();
    }

    @Test
    @DisplayName("SC-96: the announced approval names the submission by number, the organisation, the "
            + "approver and the clock's moment, with the generated event id (BR-37, BR-40, BR-47)")
    void theAnnouncedApprovalCarriesExactlyWhatHappened() throws Exception {
        approveAs(OTHER_UNDERWRITER, "REF-1");

        assertThat(store.publishedEvents()).containsExactly(
                new SubmissionDomainEvent.SubmissionApproved(
                        new FixedSubmissionIdGenerator().nextEventId(),
                        SubmissionNumber.parse("REF-1").orElse(null),
                        TENANT_A,
                        OTHER_UNDERWRITER.actorId(),
                        APPROVED_AT));
    }

    @Test
    @DisplayName("SC-97: the announced approval, printed, does not disclose the approver (BR-42)")
    void theAnnouncedApprovalDoesNotPrintTheApprover() throws Exception {
        approveAs(OTHER_UNDERWRITER, "REF-1");

        assertThat(store.publishedEvents()).singleElement()
                .satisfies(event -> assertThat(event.toString())
                        .doesNotContain(OTHER_UNDERWRITER.actorId())
                        .contains("REF-1"));
    }

    // ------------------------------------------------------------------ helpers

    private MvcResult approveAs(ActorContext actor, String pathNumber) throws Exception {
        resolver.caller = Optional.of(AuthenticatedCaller.parse(
                actor.tenantId().toString(), actor.actorId(), actor.role().name()).orElseThrow());
        return mvc.perform(post("/api/submissions/{number}/approve", pathNumber)).andReturn();
    }

    /** The path exactly as written, for a test whose point is an encoding the template would add. */
    private MvcResult approveRawAs(ActorContext actor, String rawPath) throws Exception {
        resolver.caller = Optional.of(AuthenticatedCaller.parse(
                actor.tenantId().toString(), actor.actorId(), actor.role().name()).orElseThrow());
        return mvc.perform(post(java.net.URI.create(rawPath))).andReturn();
    }

    private static String problemType(MvcResult result) throws Exception {
        JsonNode problem = JSON.readTree(result.getResponse().getContentAsString());
        return problem.path("type").asString();
    }

    /** Resolves whichever caller the current test set. The production resolver never does. */
    private static final class StubResolver extends ActorContextResolver {

        private Optional<AuthenticatedCaller> caller = Optional.empty();

        @Override
        public Optional<AuthenticatedCaller> resolve() {
            return caller;
        }
    }

    /** One in-memory store for all three outbound ports, so a test can see what was recorded. */
    private static final class InMemoryStore implements SubmissionCommandRepository,
            SubmissionApprovalRepository, SubmissionEventPublisher {

        private final Map<String, SubmissionSnapshot> byKey = new ConcurrentHashMap<>();
        private final Map<UUID, String> approvals = new ConcurrentHashMap<>();
        private final List<SubmissionDomainEvent> events = new CopyOnWriteArrayList<>();

        void seed(String number, UUID tenantId, String registeredBy) {
            var snapshot = new SubmissionSnapshot(UUID.randomUUID(), tenantId, number,
                    new BigDecimal("100.00"), registeredBy, REGISTERED_AT, 0L);
            byKey.put(tenantId + "/" + number, snapshot);
        }

        SubmissionId idOf(String number) {
            return byKey.values().stream()
                    .filter(snapshot -> snapshot.submissionNumber().equals(number))
                    .map(snapshot -> new SubmissionId(snapshot.id()))
                    .findFirst()
                    .orElseThrow();
        }

        @Override
        public Result<SubmissionId, RegisterSubmissionError.NumberAlreadyUsed> insert(
                SubmissionSnapshot snapshot) {
            byKey.put(snapshot.tenantId() + "/" + snapshot.submissionNumber(), snapshot);
            return Result.success(new SubmissionId(snapshot.id()));
        }

        @Override
        public Optional<SubmissionSnapshot> findOwnByNumber(UUID tenantId,
                                                            SubmissionNumber submissionNumber) {
            return Optional.ofNullable(byKey.get(tenantId + "/" + submissionNumber.value()));
        }

        @Override
        public Result<SubmissionId, ApproveSubmissionError> approve(UUID tenantId,
                                                                    SubmissionId submissionId,
                                                                    ActorContext actor,
                                                                    Instant approvedAt) {
            var existing = approvals.putIfAbsent(submissionId.value(), actor.actorId());
            return existing == null
                    ? Result.success(submissionId)
                    : Result.failure(new ApproveSubmissionError.SubmissionAlreadyApproved());
        }

        @Override
        public void publish(SubmissionDomainEvent event) {
            events.add(event);
        }

        List<SubmissionDomainEvent> publishedEvents() {
            return List.copyOf(events);
        }

        int approvalCount() {
            return approvals.size();
        }
    }
}
