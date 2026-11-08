package com.gs5.underwriting.submission.adapters.access;

import com.gs5.underwriting.shared.kernel.ActorRole;
import com.gs5.underwriting.shared.kernel.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The role-by-operation decisions, driven off {@link ActorRole#values()} so that a fourth role is
 * covered the moment it exists, and checked against a policy the test supplies.
 *
 * <p>These tests pin the <em>contract around</em> the policy seam using test-specific policies.
 * The application policy admits underwriters and senior underwriters. Search consults no role at
 * all (BR-16), so its matrix has no policy to vary.
 */
class PermissionMatrixTest {

    private static ActorRole otherThan(ActorRole role) {
        return Arrays.stream(ActorRole.values())
                .filter(candidate -> candidate != role)
                .findFirst()
                .orElseThrow();
    }

    @ParameterizedTest(name = "SC-74: a policy admitting {0} admits a caller of that role")
    @EnumSource(ActorRole.class)
    @DisplayName("SC-74: registration admits a caller whose role the policy admits")
    void shouldAdmitCallerWhenPolicyAdmitsTheirRole(ActorRole role) {
        var permission = Permission.registration(new FixedAccessPolicy(Set.of(role)));

        var outcome = permission.check(Callers.optionalOf(role));

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome.orElse(null).role()).isEqualTo(role);
    }

    @ParameterizedTest(name = "SC-74: a policy admitting only another role refuses a caller of {0}")
    @EnumSource(ActorRole.class)
    @DisplayName("SC-74: registration refuses a caller whose role the policy does not admit, naming the "
            + "role and not the caller")
    void shouldRefuseCallerWhenPolicyAdmitsOnlyAnotherRole(ActorRole role) {
        var permission = Permission.registration(new FixedAccessPolicy(Set.of(otherThan(role))));

        assertThat(permission.check(Callers.optionalOf(role)))
                .isEqualTo(Result.failure(new AccessRefusal.PermissionDenied(role)));
    }

    @ParameterizedTest(name = "SC-74: a policy admitting nobody refuses a caller of {0}")
    @EnumSource(ActorRole.class)
    @DisplayName("SC-74: an empty policy refuses everyone: the seam fails closed")
    void shouldRefuseEveryCallerWhenPolicyAdmitsNobody(ActorRole role) {
        var permission = Permission.registration(new FixedAccessPolicy(Set.of()));

        assertThat(permission.check(Callers.optionalOf(role)))
                .isEqualTo(Result.failure(new AccessRefusal.PermissionDenied(role)));
    }

    @ParameterizedTest(name = "SC-74: approval admits {0} when the policy allows it")
    @EnumSource(value = ActorRole.class, names = {"UNDERWRITER", "SENIOR_UNDERWRITER"})
    @DisplayName("SC-74: approval admits underwriters allowed by the policy")
    void shouldAdmitApprovalWhenPolicyAllowsTheRole(ActorRole role) {
        var permission = Permission.approval(new FixedAccessPolicy(Set.of(role)));

        var outcome = permission.check(Callers.optionalOf(role));

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome.orElse(null).role()).isEqualTo(role);
    }

    @ParameterizedTest(name = "SC-74: approval refuses {0} when the policy denies it")
    @EnumSource(value = ActorRole.class, names = {"VIEWER"})
    @DisplayName("SC-74: approval refuses roles outside the configured policy")
    void shouldRefuseApprovalWhenPolicyDeniesTheRole(ActorRole role) {
        var permission = Permission.approval(
                new FixedAccessPolicy(Set.of(ActorRole.UNDERWRITER, ActorRole.SENIOR_UNDERWRITER)));

        assertThat(permission.check(Callers.optionalOf(role)))
                .isEqualTo(Result.failure(new AccessRefusal.PermissionDenied(role)));
    }

    @Test
    @DisplayName("SC-72: approval refuses an absent caller before checking its role")
    void shouldRefuseApprovalWhenCallerIsAbsent() {
        var permission = Permission.approval(
                new FixedAccessPolicy(Set.of(ActorRole.UNDERWRITER, ActorRole.SENIOR_UNDERWRITER)));

        assertThat(permission.check(Optional.empty()))
                .isEqualTo(Result.failure(new AccessRefusal.CallerNotIdentified()));
    }

    @Test
    @DisplayName("SC-70: registration refuses an absent caller as not identified, before any role is "
            + "considered")
    void shouldRefuseAsNotIdentifiedWhenCallerIsAbsentForRegistration() {
        var permission = Permission.registration(
                new FixedAccessPolicy(Set.of(ActorRole.values())));

        assertThat(permission.check(Optional.empty()))
                .isEqualTo(Result.failure(new AccessRefusal.CallerNotIdentified()));
    }

    @ParameterizedTest(name = "SC-76: every identified role, {0}, may search")
    @EnumSource(ActorRole.class)
    @DisplayName("SC-76: every identified caller may search, whatever its role (BR-16)")
    void shouldAdmitEveryIdentifiedRoleWhenSearching(ActorRole role) {
        var outcome = Permission.anyIdentifiedCaller().check(Callers.optionalOf(role));

        // Permission is not generic, so nothing in the type stops this permission from producing
        // PermissionDenied. This assertion is what stops it.
        assertThat(outcome).isNotEqualTo(Result.failure(new AccessRefusal.PermissionDenied(role)));
        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome.orElse(null).role()).isEqualTo(role);
    }

    @Test
    @DisplayName("SC-71: search refuses an absent caller as not identified, its only refusal (BR-01)")
    void shouldRefuseAsNotIdentifiedWhenCallerIsAbsentForSearch() {
        assertThat(Permission.anyIdentifiedCaller().check(Optional.empty()))
                .isEqualTo(Result.failure(new AccessRefusal.CallerNotIdentified()));
    }
}
