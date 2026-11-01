package com.example.service.shared.kernel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the Java 25 ScopedValue binding actually works at runtime.
 *
 * <p>Worth its own test class because the removed {@code ScopedValue.callWhere(KEY, value, op)}
 * form is still what most training data contains. If someone "corrects" {@link ActorContext}
 * back to it, this file stops compiling and the Checkstyle rule fires — in that order.
 */
class ActorContextTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    @DisplayName("binds the actor for the duration of the call and returns the value")
    void binds_actor_during_call() {
        var actor = new ActorContext(TENANT, "uw-01", ActorRole.UNDERWRITER);

        var seen = ActorContext.callWith(actor, () -> ActorContext.current().orElse(null));

        assertThat(seen).isEqualTo(actor);
    }

    @Test
    @DisplayName("unbinds automatically once the call returns")
    void unbinds_after_call() {
        var actor = new ActorContext(TENANT, "uw-01", ActorRole.UNDERWRITER);

        ActorContext.callWith(actor, () -> "ignored");

        assertThat(ActorContext.current()).isEmpty();
    }

    @Test
    @DisplayName("current() is empty outside any binding instead of throwing")
    void current_is_empty_when_unbound() {
        assertThat(ActorContext.current()).isEmpty();
    }

    @Test
    @DisplayName("runWith binds the actor for a side-effecting operation")
    void run_with_binds_actor() {
        var actor = new ActorContext(TENANT, "uw-02", ActorRole.SENIOR_UNDERWRITER);
        var captured = new AtomicReference<ActorContext>();

        ActorContext.runWith(actor, () -> captured.set(ActorContext.current().orElse(null)));

        assertThat(captured).hasValue(actor);
    }

    @Test
    @DisplayName("tenant check is the basis of every BOLA rule")
    void tenant_check_distinguishes_tenants() {
        var actor = new ActorContext(TENANT, "viewer-01", ActorRole.VIEWER);

        assertThat(actor.belongsToTenant(TENANT)).isTrue();
        assertThat(actor.belongsToTenant(OTHER_TENANT)).isFalse();
    }

    @Test
    @DisplayName("only underwriter roles may decide")
    void only_underwriters_may_decide() {
        assertThat(ActorRole.VIEWER.canDecide()).isFalse();
        assertThat(ActorRole.UNDERWRITER.canDecide()).isTrue();
        assertThat(ActorRole.SENIOR_UNDERWRITER.canDecide()).isTrue();
    }
}
