package com.gs5.underwriting.submission.adapters.access;

import com.gs5.underwriting.shared.kernel.ActorRole;
import com.gs5.underwriting.shared.kernel.Result;
import com.gs5.underwriting.submission.domain.InsuredLimit;
import com.gs5.underwriting.submission.domain.RegisterSubmissionError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate: a refused caller never reaches the operation, an admitted caller reaches it exactly
 * once with the admitted actor, and input is parsed only after admission.
 *
 * <p>{@link PoisonedAmount} is the probe for "judged before parsed". Every method on it counts as a
 * touch. A Viewer's amount must be touched zero times. An Underwriter's control must be touched, so
 * the zero cannot pass for the wrong reason.
 */
class OperationGateTest {

    private static final String TENANT = Callers.TENANT;

    /**
     * A {@link BigDecimal} that records every method call on it. {@code BigDecimal} is not final,
     * so this subclass can stand in for an amount and show whether anyone looked at it.
     */
    static final class PoisonedAmount extends BigDecimal {

        private final AtomicInteger touches = new AtomicInteger();

        PoisonedAmount(String literal) {
            super(literal);
        }

        int touches() {
            return touches.get();
        }

        private void touched() {
            touches.incrementAndGet();
        }

        @Override
        public int signum() {
            touched();
            return super.signum();
        }

        @Override
        public int scale() {
            touched();
            return super.scale();
        }

        @Override
        public int precision() {
            touched();
            return super.precision();
        }

        @Override
        public BigInteger unscaledValue() {
            touched();
            return super.unscaledValue();
        }

        @Override
        public int compareTo(BigDecimal other) {
            touched();
            return super.compareTo(other);
        }

        @Override
        public String toString() {
            touched();
            return super.toString();
        }

        @Override
        public boolean equals(Object other) {
            touched();
            return super.equals(other);
        }

        @Override
        public int hashCode() {
            touched();
            return super.hashCode();
        }

        @Override
        public BigDecimal stripTrailingZeros() {
            touched();
            return super.stripTrailingZeros();
        }

        @Override
        public BigDecimal setScale(int newScale) {
            touched();
            return super.setScale(newScale);
        }
    }

    /**
     * The register pipeline as the adapter writes it: admit first, and parse inside the admitted
     * branch. The operation touches the amount before anything else, so a run is always observable.
     */
    private static Result<Result<InsuredLimit, RegisterSubmissionError>, AccessRefusal>
            registerPipeline(Optional<AuthenticatedCaller> caller,
                             Set<ActorRole> admittedRoles,
                             PoisonedAmount amount) {
        var gate = new OperationGate<BigDecimal, Result<InsuredLimit, RegisterSubmissionError>>(
                Permission.registration(new FixedAccessPolicy(admittedRoles)),
                (actor, raw) -> {
                    raw.signum();
                    return InsuredLimit.parse(raw);
                });
        return gate.admit(caller).map(admission -> admission.run(amount));
    }

    @Test
    @DisplayName("a Viewer is refused for registration before its amount is looked at (BR-02)")
    void shouldRefuseViewerBeforeTouchingAmountWhenRegistering() {
        var amount = new PoisonedAmount("1200.00");

        // The policy admits every role except VIEWER. That is the BR-02 fact under test, supplied
        // by the test. It is not a production role list.
        var outcome = registerPipeline(Callers.optionalOf(ActorRole.VIEWER),
                Set.of(ActorRole.UNDERWRITER, ActorRole.SENIOR_UNDERWRITER), amount);

        assertThat(outcome).isEqualTo(
                Result.<Result<InsuredLimit, RegisterSubmissionError>, AccessRefusal>failure(
                        new AccessRefusal.PermissionDenied(ActorRole.VIEWER)));
        assertThat(amount.touches()).isZero();
    }

    @Test
    @DisplayName("a Viewer sending a malformed amount is refused for the role, not the amount, and "
            + "the amount is untouched")
    void shouldRefuseViewerForRoleNotAmountWhenAmountIsMalformed() {
        var amount = new PoisonedAmount("-0.00");

        var outcome = registerPipeline(Callers.optionalOf(ActorRole.VIEWER),
                Set.of(ActorRole.UNDERWRITER), amount);

        assertThat(outcome).isEqualTo(
                Result.<Result<InsuredLimit, RegisterSubmissionError>, AccessRefusal>failure(
                        new AccessRefusal.PermissionDenied(ActorRole.VIEWER)));
        assertThat(amount.touches()).isZero();
    }

    @Test
    @DisplayName("an absent caller is refused as not identified before its amount is looked at")
    void shouldRefuseAbsentCallerBeforeTouchingAmountWhenRegistering() {
        var amount = new PoisonedAmount("1200.00");

        var outcome = registerPipeline(Optional.empty(),
                Set.of(ActorRole.UNDERWRITER), amount);

        assertThat(outcome).isEqualTo(
                Result.<Result<InsuredLimit, RegisterSubmissionError>, AccessRefusal>failure(
                        new AccessRefusal.CallerNotIdentified()));
        assertThat(amount.touches()).isZero();
    }

    @Test
    @DisplayName("control: an Underwriter's amount is touched, so the Viewer's zero is meaningful")
    void shouldTouchAmountWhenUnderwriterRegisters() {
        var amount = new PoisonedAmount("1200.00");

        var outcome = registerPipeline(Callers.optionalOf(ActorRole.UNDERWRITER),
                Set.of(ActorRole.UNDERWRITER), amount);

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(amount.touches()).isPositive();
    }

    /**
     * Search's gate is built over {@link Permission#anyIdentifiedCaller()}. Its refusal type is the
     * shared {@link AccessRefusal}, so the search controller's {@code switch} has a
     * {@code PermissionDenied} arm. That arm must be unreachable, and with two type parameters only a
     * test can say so.
     */
    @ParameterizedTest(name = "search never refuses {0} on role, and runs the search for it")
    @EnumSource(ActorRole.class)
    @DisplayName("the search gate never produces PermissionDenied for any role: its second switch arm "
            + "is unreachable (BR-16)")
    void shouldNeverRefuseOnRoleWhenSearchGateAdmitsAnyRole(ActorRole role) {
        // Given
        var calls = new AtomicInteger();
        var searchGate = new OperationGate<String, String>(
                Permission.anyIdentifiedCaller(),
                (actor, filter) -> {
                    calls.incrementAndGet();
                    return filter;
                });

        // When
        var outcome = searchGate.admit(Callers.optionalOf(role))
                .map(admission -> admission.run("AB-1"));

        // Then
        assertThat(outcome).isNotEqualTo(
                Result.<String, AccessRefusal>failure(new AccessRefusal.PermissionDenied(role)));
        assertThat(outcome).isEqualTo(Result.<String, AccessRefusal>success("AB-1"));
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("the search gate's only refusal is caller not identified, and the search never runs")
    void shouldRefuseOnlyAsNotIdentifiedWhenSearchCallerIsAbsent() {
        var calls = new AtomicInteger();
        var searchGate = new OperationGate<String, String>(
                Permission.anyIdentifiedCaller(),
                (actor, filter) -> {
                    calls.incrementAndGet();
                    return filter;
                });

        var outcome = searchGate.admit(Optional.empty()).map(admission -> admission.run("AB-1"));

        assertThat(outcome).isEqualTo(
                Result.<String, AccessRefusal>failure(new AccessRefusal.CallerNotIdentified()));
        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("a refused caller never reaches the operation")
    void shouldNotRunOperationWhenCallerIsRefused() {
        var calls = new AtomicInteger();
        var gate = new OperationGate<String, String>(
                Permission.registration(new FixedAccessPolicy(Set.of())),
                (actor, input) -> {
                    calls.incrementAndGet();
                    return input;
                });

        var outcome = gate.admit(Callers.optionalOf(ActorRole.UNDERWRITER))
                .map(admission -> admission.run("AB-1"));

        assertThat(outcome).isEqualTo(
                Result.<String, AccessRefusal>failure(
                        new AccessRefusal.PermissionDenied(ActorRole.UNDERWRITER)));
        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("an admitted caller reaches the operation exactly once, with the admitted actor and "
            + "the input it was given")
    void shouldRunOperationOnceWithAdmittedActorWhenCallerIsAdmitted() {
        var calls = new AtomicInteger();
        var seen = new AtomicReference<com.gs5.underwriting.shared.kernel.ActorContext>();
        var gate = new OperationGate<String, String>(
                Permission.registration(new FixedAccessPolicy(Set.of(ActorRole.UNDERWRITER))),
                (actor, input) -> {
                    calls.incrementAndGet();
                    seen.set(actor);
                    return input;
                });

        var outcome = gate.admit(Callers.optionalOf(ActorRole.UNDERWRITER))
                .map(admission -> admission.run("AB-1"));

        assertThat(outcome).isEqualTo(Result.<String, AccessRefusal>success("AB-1"));
        assertThat(calls).hasValue(1);
        assertThat(seen.get().actorId()).isEqualTo(Callers.ACTOR);
        assertThat(seen.get().tenantId().toString()).isEqualTo(TENANT);
    }
}
