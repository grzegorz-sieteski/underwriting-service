package com.example.service.shared.kernel;

import module java.base;

/**
 * The authenticated caller, propagated to the domain without a framework dependency.
 *
 * <p>The service runs on virtual threads, so {@code ThreadLocal} is the wrong tool: it pins state
 * to a carrier thread and leaks across tasks. Java 25 finalised {@link ScopedValue} (JEP 506),
 * which binds a value for the dynamic extent of one call and unbinds it automatically.
 *
 * <p><strong>Always bind through {@link #callWith} / {@link #runWith}.</strong> The static
 * {@code ScopedValue.callWhere(...)} and {@code ScopedValue.runWhere(...)} helpers that older
 * material still shows were <em>removed from the JDK in 24</em>; the final API is
 * {@code ScopedValue.where(KEY, value).call(op)}. These two methods wrap it so no caller has to
 * remember which form is current, and a Checkstyle rule fails the build on the removed one.
 *
 * @param tenantId organisation the caller belongs to; the basis of every BOLA check
 * @param actorId  stable identifier of the caller, written to audit fields
 * @param role     what the caller is allowed to do
 */
public record ActorContext(UUID tenantId, String actorId, ActorRole role) {

    public static final ScopedValue<ActorContext> CURRENT = ScopedValue.newInstance();

    public ActorContext {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(role, "role");
    }

    /**
     * Runs {@code operation} with this actor bound, and returns its value.
     *
     * <p>This is the correct Java 25 form of what used to be written
     * {@code ScopedValue.callWhere(CURRENT, actor, op)}.
     */
    public static <R> R callWith(ActorContext actor, Supplier<R> operation) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(operation, "operation");
        return ScopedValue.where(CURRENT, actor).call(operation::get);
    }

    /** Runs {@code operation} with this actor bound. */
    public static void runWith(ActorContext actor, Runnable operation) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(operation, "operation");
        ScopedValue.where(CURRENT, actor).run(operation);
    }

    /**
     * The actor bound to the current extent, or empty outside one.
     *
     * <p>Returns an {@code Optional} rather than calling {@code CURRENT.get()}, which throws
     * when unbound. Callers stay on the railway: {@code Result.fromOptional(current(), ...)}.
     */
    public static Optional<ActorContext> current() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }

    /** True when this actor may read data belonging to {@code otherTenantId}. */
    public boolean belongsToTenant(UUID otherTenantId) {
        return tenantId.equals(otherTenantId);
    }
}
