package com.gs5.underwriting.submission.adapters.web;

import com.gs5.underwriting.shared.kernel.ActorContext;
import com.gs5.underwriting.shared.kernel.ActorRole;
import com.gs5.underwriting.shared.kernel.Result;
import com.gs5.underwriting.submission.adapters.access.AccessPolicy;
import com.gs5.underwriting.submission.adapters.access.AuthenticatedCaller;
import com.gs5.underwriting.submission.adapters.access.Permission;
import com.gs5.underwriting.submission.domain.ApproveSubmissionError;
import com.gs5.underwriting.submission.domain.ApproveSubmissionUseCase;
import com.gs5.underwriting.submission.domain.RegisterSubmissionError;
import com.gs5.underwriting.submission.domain.SubmissionApprovalRepository;
import com.gs5.underwriting.submission.domain.SubmissionCommandRepository;
import com.gs5.underwriting.submission.domain.SubmissionDomainEvent;
import com.gs5.underwriting.submission.domain.SubmissionEventPublisher;
import com.gs5.underwriting.submission.domain.SubmissionId;
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
 * The approval endpoint at the HTTP edge: the real controller, the real use case over in-memory
 * ports, and a resolver that names whichever caller the test chooses. No Spring context, no database.
 *
 * <p>Findings pinned here:
 * <ul>
 *   <li>D1: a malformed identity from a Viewer is refused by role (403), not by the framework (400).</li>
 *   <li>D2: a lenient UUID spelling does not name a submission.</li>
 *   <li>D3 and D4: the registrant is refused with its own problem type, before already-approved.</li>
 *   <li>D5 and D6: malformed, unknown and other-organisation identities produce one body, with no echo.</li>
 * </ul>
 * Tests named "guard" pass at HEAD and must keep passing, so that a fix cannot over-refuse.
 */
class SubmissionApprovalControllerTest {

    private static final UUID TENANT_A = UUID.fromString("0a000000-0000-0000-0000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("0b000000-0000-0000-0000-00000000000b");
    private static final UUID SUBMISSION = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TENANT_SUBMISSION = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID REGISTERED_BY_OTHER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    /** What Spring's lenient UUID converter makes of the text "1-1-1-1-1". */
    private static final UUID LENIENT_TARGET = UUID.fromString("00000001-0001-0001-0001-000000000001");
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
                Clock.fixed(APPROVED_AT, ZoneOffset.UTC));
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
        var controller = new SubmissionApprovalController(useCase, resolver,
                Permission.approval(policy));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();

        // Registered by uw-01, who is therefore NOT a valid approver of it in the tests below.
        store.seed(SUBMISSION, TENANT_A, "uw-01");
    }

    @Test
    @DisplayName("a viewer naming a malformed identity is refused by role as 403, not as a framework 400 (BR-33, D1)")
    void aViewerNamingAMalformedIdentityIsRefusedByRoleFirst() throws Exception {
        var result = approveAs(VIEWER, "not-a-uuid");

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(problemType(result)).isEqualTo(PROBLEM_TYPE_PREFIX + "permission-denied");
    }

    @Test
    @DisplayName("a lenient UUID spelling names no submission, so it is refused as not found and approves nothing (BR-35, D2)")
    void aLenientUuidSpellingIsNotAcceptedAsTheSubmissionItNames() throws Exception {
        store.seed(LENIENT_TARGET, TENANT_A, "uw-01");

        var result = approveAs(OTHER_UNDERWRITER, "1-1-1-1-1");

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(store.approvalCount()).isZero();
        assertThat(store.publishedEvents()).isEmpty();
    }

    @Test
    @DisplayName("a malformed identity and an unknown one produce the same refusal, byte for byte, with no echo (BR-35, D5, D6)")
    void aMalformedAndAnUnknownIdentityProduceTheSameBody() throws Exception {
        var malformed = approveAs(OTHER_UNDERWRITER, "not-a-uuid");
        var unknown = approveAs(OTHER_UNDERWRITER, UUID.randomUUID().toString());

        assertThat(malformed.getResponse().getStatus()).isEqualTo(404);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(404);
        assertThat(malformed.getResponse().getContentAsString())
                .isEqualTo(unknown.getResponse().getContentAsString());
        assertThat(malformed.getResponse().getContentAsString()).doesNotContain("not-a-uuid");
    }

    @Test
    @DisplayName("another organisation's submission is refused with the same body as an unknown one (BR-35, D5, D6)")
    void anotherOrganisationsSubmissionIsIndistinguishableFromAnUnknownOne() throws Exception {
        store.seed(OTHER_TENANT_SUBMISSION, TENANT_B, "uw-09");

        var otherTenant = approveAs(OTHER_UNDERWRITER, OTHER_TENANT_SUBMISSION.toString());
        var unknown = approveAs(OTHER_UNDERWRITER, UUID.randomUUID().toString());

        assertThat(otherTenant.getResponse().getStatus()).isEqualTo(404);
        assertThat(otherTenant.getResponse().getContentAsString())
                .isEqualTo(unknown.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("an approver who registered the submission is refused as approver-registered-submission, and nothing is recorded (BR-41, D3, D4)")
    void anApproverWhoRegisteredTheSubmissionIsRefusedAndNothingIsRecorded() throws Exception {
        store.seed(REGISTERED_BY_OTHER, TENANT_A, "uw-02");

        var result = approveAs(OTHER_UNDERWRITER, REGISTERED_BY_OTHER.toString());

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(problemType(result)).isEqualTo(PROBLEM_TYPE_PREFIX + "approver-registered-submission");
        assertThat(store.approvalCount()).isZero();
        assertThat(store.publishedEvents()).isEmpty();
    }

    @Test
    @DisplayName("the registrant is refused before already-approved, so an earlier approval does not change the answer (BR-41, D3)")
    void theRegistrantIsRefusedBeforeAlreadyApproved() throws Exception {
        store.seed(REGISTERED_BY_OTHER, TENANT_A, "uw-02");
        store.approve(TENANT_A, new SubmissionId(REGISTERED_BY_OTHER), SENIOR_UNDERWRITER, APPROVED_AT);

        var result = approveAs(OTHER_UNDERWRITER, REGISTERED_BY_OTHER.toString());

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(problemType(result)).isEqualTo(PROBLEM_TYPE_PREFIX + "approver-registered-submission");
    }

    @Test
    @DisplayName("guard: an underwriter who did not register the submission may approve it, once, with one event (BR-33, BR-40)")
    void aNonRegistrantUnderwriterMayApprove() throws Exception {
        var result = approveAs(OTHER_UNDERWRITER, SUBMISSION.toString());

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(store.approvalCount()).isEqualTo(1);
        assertThat(store.publishedEvents()).hasSize(1);
    }

    @Test
    @DisplayName("guard: a viewer naming a well-formed identity is refused by role and records nothing (BR-33)")
    void aViewerIsRefusedByRoleForAWellFormedIdentity() throws Exception {
        var result = approveAs(VIEWER, SUBMISSION.toString());

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(problemType(result)).isEqualTo(PROBLEM_TYPE_PREFIX + "permission-denied");
        assertThat(store.approvalCount()).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private MvcResult approveAs(ActorContext actor, String pathId) throws Exception {
        resolver.caller = Optional.of(AuthenticatedCaller.parse(
                actor.tenantId().toString(), actor.actorId(), actor.role().name()).orElseThrow());
        return mvc.perform(post("/api/submissions/{id}/approve", pathId)).andReturn();
    }

    private static String problemType(MvcResult result) throws Exception {
        JsonNode problem = JSON.readTree(result.getResponse().getContentAsString());
        return problem.path("type").asString();
    }

    /** Resolves whichever caller the current test set. The production resolver never does. */
    private static final class StubResolver extends ActorContextResolver {

        private Optional<AuthenticatedCaller> caller = Optional.empty();

        @Override
        Optional<AuthenticatedCaller> resolve() {
            return caller;
        }
    }

    /** One in-memory store for all three outbound ports, so a test can see what was recorded. */
    private static final class InMemoryStore implements SubmissionCommandRepository,
            SubmissionApprovalRepository, SubmissionEventPublisher {

        private final Map<UUID, SubmissionSnapshot> submissions = new ConcurrentHashMap<>();
        private final Map<UUID, String> approvals = new ConcurrentHashMap<>();
        private final List<SubmissionDomainEvent> events = new CopyOnWriteArrayList<>();

        void seed(UUID id, UUID tenantId, String registeredBy) {
            submissions.put(id, new SubmissionSnapshot(id, tenantId, "REF-" + id.toString().substring(0, 8),
                    new BigDecimal("100.00"), registeredBy, REGISTERED_AT, 0L));
        }

        @Override
        public Result<SubmissionId, RegisterSubmissionError.ReferenceAlreadyUsed> insert(
                SubmissionSnapshot snapshot) {
            submissions.put(snapshot.id(), snapshot);
            return Result.success(new SubmissionId(snapshot.id()));
        }

        @Override
        public Optional<SubmissionSnapshot> findOwn(UUID tenantId, UUID id) {
            return Optional.ofNullable(submissions.get(id))
                    .filter(snapshot -> snapshot.tenantId().equals(tenantId));
        }

        @Override
        public boolean isApproved(UUID tenantId, SubmissionId submissionId) {
            return approvals.containsKey(submissionId.value());
        }

        @Override
        public Result<SubmissionId, ApproveSubmissionError> approve(UUID tenantId,
                                                                    SubmissionId submissionId,
                                                                    ActorContext actor,
                                                                    Instant approvedAt) {
            var existing = approvals.putIfAbsent(submissionId.value(), actor.actorId());
            return existing == null
                    ? Result.success(submissionId)
                    : Result.failure(new ApproveSubmissionError.SubmissionAlreadyApproved(submissionId));
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
