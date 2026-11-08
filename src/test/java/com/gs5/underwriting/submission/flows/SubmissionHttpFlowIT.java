package com.gs5.underwriting.submission.flows;

import com.gs5.underwriting.shared.kernel.ActorContext;
import com.gs5.underwriting.shared.kernel.ActorRole;
import com.gs5.underwriting.submission.adapters.access.AuthenticatedCaller;
import com.gs5.underwriting.submission.adapters.web.ActorContextResolver;
import com.gs5.underwriting.submission.domain.SubmissionDomainEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three business flows end to end over real HTTP: the whole application on an embedded Tomcat at
 * a random port, PostgreSQL in a container, and a plain {@link HttpClient} that sends each path
 * exactly as written. Nothing is mocked except where the design's Test contract allows it (TC5): the
 * caller identity at the edge, the clock, and a listener that records the announced events.
 *
 * <p>Why a real server and not MockMvc: the approval path carries the submission number, and the
 * review finding R-01 was about what happens to {@code ;} and {@code %2F} in that path. MockMvc never
 * runs the servlet container's own URI handling, so it cannot show what Tomcat does with those
 * characters before Spring sees them.
 *
 * <p>Isolation without deleting anything: every test works in an organisation of its own, a fresh
 * tenant id. The fact tables are append-only (BR-44), and this test never needs to remove a fact.
 *
 * <p>Uses only the Test contract: the HTTP endpoints of TC2 to TC4, the public
 * {@link ActorContextResolver} as the replaceable identity seam (G1, closed by revision 7), the
 * {@code Clock} bean, the published {@link SubmissionDomainEvent}s, and the columns of TC6.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SubmissionHttpFlowIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    private static final Instant T0 = Instant.parse("2026-11-01T09:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-02T12:30:00Z");
    private static final String TYPE = "urn:problem-type:submission:";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** A fresh organisation per test, so no test sees another's facts. */
    private final UUID tenant = UUID.randomUUID();
    private final ActorContext u1 = new ActorContext(tenant, "uw-01", ActorRole.UNDERWRITER);
    private final ActorContext u2 = new ActorContext(tenant, "uw-02", ActorRole.UNDERWRITER);
    private final ActorContext viewer = new ActorContext(tenant, "viewer-01", ActorRole.VIEWER);

    @LocalServerPort
    private int port;

    @Autowired
    private SwitchableResolver resolver;

    @Autowired
    private SettableClock clock;

    @Autowired
    private EventLog events;

    @Autowired
    private JdbcTemplate jdbc;

    // ------------------------------------------------------------------ register (TC2)

    @Test
    @DisplayName("SC-27: a successful registration answers 201 with the number only, and a Location "
            + "naming it (BR-15, BR-47)")
    void aRegistrationAnswers201WithTheNumberOnly() {
        var response = register(u1, "ABC-1", "1200.00");

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.header("Location")).contains("/api/submissions/ABC-1");
        var body = response.json();
        assertThat(fieldNames(body)).containsExactly("submissionNumber");
        assertThat(body.path("submissionNumber").asString()).isEqualTo("ABC-1");
        assertThat(storedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("SC-28: a registration without an identified caller answers 401, and nothing is "
            + "stored or announced (BR-01)")
    void aRegistrationWithoutACallerAnswers401() {
        var number = "NOBODY-" + tenant.toString().substring(0, 8);

        var response = post("/api/submissions", Optional.empty(), body(number, "1200.00"));

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.problemType()).isEqualTo(TYPE + "caller-not-identified");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM submissions WHERE submission_number = ?", Long.class, number))
                .isZero();
        assertThat(events.naming(number)).isEmpty();
    }

    static Stream<Arguments> bodiesAViewerSends() {
        return Stream.of(
                Arguments.of("a valid body", body("ABC-1", "1200.00")),
                Arguments.of("an empty number", body("", "1200.00")),
                Arguments.of("a zero amount", body("ABC-1", "0.00")));
    }

    @ParameterizedTest(name = "SC-29: a viewer sending {0} is refused as 403")
    @MethodSource("bodiesAViewerSends")
    @DisplayName("SC-29: a viewer is refused at the edge whatever the body, and nothing is stored (BR-02)")
    void aViewerIsRefusedWhateverTheBody(String description, String requestBody) {
        var response = post("/api/submissions", Optional.of(viewer), requestBody);

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.problemType()).isEqualTo(TYPE + "permission-denied");
        assertThat(storedCount()).isZero();
        assertThat(events.ofTenant(tenant)).isEmpty();
    }

    static Stream<Arguments> refusedNumbers() {
        return Stream.of(
                Arguments.of("empty", "", "number-missing"),
                Arguments.of("65 characters", "A".repeat(65), "number-too-long"),
                Arguments.of("AB;CD", "AB;CD", "number-contains-character-not-allowed"),
                Arguments.of("A B", "A B", "number-contains-character-not-allowed"),
                Arguments.of("A/B", "A/B", "number-contains-character-not-allowed"));
    }

    @ParameterizedTest(name = "SC-30: a number that is {0} is refused as {2}")
    @MethodSource("refusedNumbers")
    @DisplayName("SC-30: each number refusal answers 400 with its own problem type, and nothing is "
            + "stored (BR-04, BR-05, BR-29)")
    void eachNumberRefusalAnswers400WithItsOwnType(String description, String number, String type) {
        var response = register(u1, number, "1200.00");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.contentType()).startsWith("application/problem+json");
        assertThat(response.problemType()).isEqualTo(TYPE + type);
        assertThat(storedCount()).isZero();
    }

    static Stream<Arguments> refusedAmounts() {
        return Stream.of(
                Arguments.of("null", "insured-limit-missing", "null"),
                Arguments.of("-5.00", "insured-limit-not-positive", "5.00"),
                Arguments.of("1200.5", "insured-limit-precision-invalid", "1200"));
    }

    @ParameterizedTest(name = "SC-31: insuredLimit {0} is refused as {1}, and the body does not show it")
    @MethodSource("refusedAmounts")
    @DisplayName("SC-31: each amount refusal answers 400 with its own type, and no body shows the "
            + "amount (BR-10, BR-11, BR-12, BR-22)")
    void eachAmountRefusalAnswers400AndHidesTheAmount(String amount, String type, String amountText) {
        var response = register(u1, "ABC-1", amount);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.problemType()).isEqualTo(TYPE + type);
        assertThat(response.body()).doesNotContain(amountText);
        assertThat(storedCount()).isZero();
    }

    @Test
    @DisplayName("SC-32: a duplicate number in the same organisation answers 409, names the number, "
            + "and stores nothing more (BR-06, BR-23)")
    void aDuplicateNumberAnswers409() {
        register(u1, "ABC-1", "1200.00");

        var response = register(u2, "ABC-1", "900.00");

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.problemType()).isEqualTo(TYPE + "number-already-used");
        assertThat(response.json().path("detail").asString()).contains("'ABC-1'");
        assertThat(response.body()).doesNotContain("900");
        assertThat(storedCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ search (TC3)

    @ParameterizedTest(name = "SC-51: a {0} searching answers 200")
    @EnumSource(ActorRole.class)
    @DisplayName("SC-51: a search answers 200 with the page as JSON for every role, each row with "
            + "exactly the five business fields (BR-16, BR-24, BR-45, BR-47)")
    void aSearchAnswers200WithThePageForEveryRole(ActorRole role) {
        clock.set(T0);
        register(u1, "S-1", "100.00");
        clock.set(T1);
        register(u1, "S-2", "200.00");

        var response = get("/api/submissions",
                Optional.of(new ActorContext(tenant, "caller-01", role)));

        assertThat(response.status()).isEqualTo(200);
        var page = response.json();
        assertThat(page.path("totalMatchCount").asLong()).isEqualTo(2L);
        assertThat(page.path("page").asInt()).isZero();
        assertThat(page.path("size").asInt()).isEqualTo(100);
        var rows = page.path("submissions");
        assertThat(rows).hasSize(2);
        for (JsonNode row : rows) {
            assertThat(fieldNames(row)).containsExactlyInAnyOrder(
                    "submissionNumber", "insuredLimit", "registeredBy", "registeredAt", "state");
        }
        assertThat(rows.get(0).path("submissionNumber").asString()).isEqualTo("S-2");
        assertThat(rows.get(1).path("submissionNumber").asString()).isEqualTo("S-1");
    }

    @Test
    @DisplayName("SC-52: a search without an identified caller answers 401 (BR-01)")
    void aSearchWithoutACallerAnswers401() {
        var response = get("/api/submissions", Optional.empty());

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.problemType()).isEqualTo(TYPE + "caller-not-identified");
    }

    // ------------------------------------------------------------------ approve (TC4)

    @Test
    @DisplayName("SC-66: approval changes none of the registered facts: the search row keeps number, "
            + "amount, registrant and moment, now APPROVED (BR-38)")
    void approvalChangesNoneOfTheRegisteredFacts() {
        clock.set(T0);
        register(u1, "REF-1", "1200.00");
        clock.set(T1);
        assertThat(approve(u2, "/api/submissions/REF-1/approve").status()).isEqualTo(200);

        var row = onlyRow(get("/api/submissions?submissionNumber=REF-1", Optional.of(viewer)));

        assertThat(row.path("submissionNumber").asString()).isEqualTo("REF-1");
        assertThat(row.path("insuredLimit").decimalValue()).isEqualByComparingTo("1200.00");
        assertThat(row.path("registeredBy").asString()).isEqualTo("uw-01");
        assertThat(Instant.parse(row.path("registeredAt").asString())).isEqualTo(T0);
        assertThat(row.path("state").asString()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("SC-67: the recorded approval names the approver and the clock's moment, in the "
            + "caller's organisation, and the registration fact is unchanged (BR-37, BR-44)")
    void theRecordedApprovalNamesTheApproverAndTheMoment() {
        clock.set(T0);
        register(u1, "REF-1", "1200.00");
        var registrationBefore = registrationFact("REF-1");
        clock.set(T1);

        approve(u2, "/api/submissions/REF-1/approve");

        var approvals = jdbc.queryForList("""
                SELECT a.approved_by, a.approved_at, a.tenant_id
                FROM submission_approvals a
                JOIN submissions s ON s.id = a.submission_id AND s.tenant_id = a.tenant_id
                WHERE s.tenant_id = ? AND s.submission_number = ?
                """, tenant, "REF-1");
        assertThat(approvals).hasSize(1);
        var approval = approvals.getFirst();
        assertThat(approval.get("approved_by")).isEqualTo("uw-02");
        assertThat(((java.sql.Timestamp) approval.get("approved_at")).toInstant()).isEqualTo(T1);
        assertThat(approval.get("tenant_id")).isEqualTo(tenant);
        assertThat(registrationFact("REF-1")).isEqualTo(registrationBefore);
    }

    // ------------------------------------------------------------------ R-01 on a real servlet container

    @ParameterizedTest(name = "SC-64: POST /api/submissions/{0}/approve on Tomcat approves nothing")
    @ValueSource(strings = {"REF-1;x", "REF-1;", "REF-1;jsessionid=1"})
    @DisplayName("SC-64: on a real Tomcat, a path segment carrying ';' names no submission: 404 with "
            + "the unknown-number body, REF-1 still registered, nothing announced (BR-07, BR-35, BR-48)")
    void aSemicolonInThePathApprovesNothingOnTomcat(String segment) {
        register(u1, "REF-1", "1200.00");
        var unknown = approve(u2, "/api/submissions/UNKNOWN-1/approve");

        var response = approve(u2, "/api/submissions/" + segment + "/approve");

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body()).isEqualTo(unknown.body());
        assertNothingApproved("REF-1");
    }

    /**
     * No registrable number contains {@code /} (BR-29), so this path is never a real request, only a
     * probe. Which layer refuses it is not the business rule: Tomcat refuses an encoded {@code /}
     * itself ({@code encodedSolidusHandling=reject}, a {@code 400} before the application runs),
     * where MockMvc reaches the resource and gets its {@code 404}. The rule is that it approves
     * nothing and answers the same whether or not the submission exists (BR-35, BR-48).
     */
    @Test
    @DisplayName("SC-65: on a real Tomcat, a path carrying an encoded '/' is refused, the same for an "
            + "existing and an unknown number, and approves nothing (BR-29, BR-35, BR-48)")
    void anEncodedSlashInThePathApprovesNothingOnTomcat() {
        register(u1, "REF-1", "1200.00");
        var unknown = approve(u2, "/api/submissions/UNKNOWN-1%2Fx/approve");

        var response = approve(u2, "/api/submissions/REF-1%2Fx/approve");

        assertThat(response.status()).as("%s", response.body()).isBetween(400, 499);
        assertThat(response.status()).isEqualTo(unknown.status());
        assertThat(response.body()).isEqualTo(unknown.body());
        assertNothingApproved("REF-1");
    }

    // ------------------------------------------------------------------ helpers

    private void assertNothingApproved(String number) {
        var row = onlyRow(get("/api/submissions?submissionNumber=" + number, Optional.of(viewer)));
        assertThat(row.path("state").asString()).isEqualTo("REGISTERED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM submission_approvals WHERE tenant_id = ?", Long.class, tenant))
                .isZero();
        assertThat(events.ofTenant(tenant))
                .noneMatch(SubmissionDomainEvent.SubmissionApproved.class::isInstance);
    }

    private Response register(ActorContext caller, String number, String amountLiteral) {
        return post("/api/submissions", Optional.of(caller), body(number, amountLiteral));
    }

    private Response approve(ActorContext caller, String rawPath) {
        return post(rawPath, Optional.of(caller), "");
    }

    /** A JSON body with the amount written literally, so its scale is exactly what the test says. */
    private static String body(String number, String amountLiteral) {
        return "{\"submissionNumber\":" + JSON.writeValueAsString(number)
                + ",\"insuredLimit\":" + amountLiteral + "}";
    }

    private Response post(String rawPath, Optional<ActorContext> caller, String requestBody) {
        var request = HttpRequest.newBuilder(uri(rawPath))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        return send(request, caller);
    }

    private Response get(String rawPath, Optional<ActorContext> caller) {
        return send(HttpRequest.newBuilder(uri(rawPath)).GET().build(), caller);
    }

    /** The path exactly as written: {@link URI#create} neither encodes nor normalises it. */
    private URI uri(String rawPath) {
        return URI.create("http://localhost:" + port + rawPath);
    }

    private Response send(HttpRequest request, Optional<ActorContext> caller) {
        resolver.set(caller.map(SubmissionHttpFlowIT::authenticated));
        try {
            var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.headers().map(), response.body());
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        } finally {
            resolver.set(Optional.empty());
        }
    }

    private static AuthenticatedCaller authenticated(ActorContext actor) {
        return AuthenticatedCaller.parse(actor.tenantId().toString(), actor.actorId(),
                actor.role().name()).orElseThrow();
    }

    private long storedCount() {
        return jdbc.queryForObject("SELECT count(*) FROM submissions WHERE tenant_id = ?",
                Long.class, tenant);
    }

    private Map<String, Object> registrationFact(String number) {
        return jdbc.queryForMap("""
                SELECT id, tenant_id, submission_number, insured_limit, registered_by, registered_at
                FROM submissions WHERE tenant_id = ? AND submission_number = ?
                """, tenant, number);
    }

    private static JsonNode onlyRow(Response response) {
        assertThat(response.status()).isEqualTo(200);
        var rows = response.json().path("submissions");
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private static List<String> fieldNames(JsonNode node) {
        var names = new ArrayList<String>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    private record Response(int status, Map<String, List<String>> headers, String body) {

        Optional<String> header(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .flatMap(entry -> entry.getValue().stream())
                    .findFirst();
        }

        String contentType() {
            return header("Content-Type").orElse("");
        }

        JsonNode json() {
            return JSON.readTree(body);
        }

        String problemType() {
            return json().path("type").asString();
        }
    }

    // ------------------------------------------------------------------ the seams TC5 allows

    @TestConfiguration
    static class Seams {

        @Bean
        @Primary
        SwitchableResolver switchableResolver() {
            return new SwitchableResolver();
        }

        @Bean
        @Primary
        SettableClock settableClock() {
            return new SettableClock();
        }

        @Bean
        EventLog eventLog() {
            return new EventLog();
        }
    }

    /** Names whichever caller the current request is sent as. Production names nobody. */
    static final class SwitchableResolver extends ActorContextResolver {

        private final AtomicReference<Optional<AuthenticatedCaller>> caller =
                new AtomicReference<>(Optional.empty());

        void set(Optional<AuthenticatedCaller> next) {
            caller.set(next);
        }

        @Override
        public Optional<AuthenticatedCaller> resolve() {
            return caller.get();
        }
    }

    /** A clock the test moves, so registration and approval moments are known exactly. */
    static final class SettableClock extends Clock {

        private final AtomicReference<Instant> now = new AtomicReference<>(T0);

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    /** Every event the application announced, from whichever request thread announced it. */
    static final class EventLog {

        private final List<SubmissionDomainEvent> seen = new CopyOnWriteArrayList<>();

        @EventListener
        void on(SubmissionDomainEvent event) {
            seen.add(event);
        }

        List<SubmissionDomainEvent> ofTenant(UUID tenantId) {
            return seen.stream().filter(event -> event.tenantId().equals(tenantId)).toList();
        }

        List<SubmissionDomainEvent> naming(String number) {
            return seen.stream()
                    .filter(event -> event.submissionNumber().value().equals(number))
                    .toList();
        }
    }
}
