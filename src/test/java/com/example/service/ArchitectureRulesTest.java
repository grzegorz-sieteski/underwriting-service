package com.example.service;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

/**
 * The structural half of the architecture gate.
 *
 * <p>This covers what is visible in declared types and dependencies. It does <em>not</em> cover
 * statements: ArchUnit cannot see a {@code throw new IllegalArgumentException(...)}, because that
 * class belongs to the JDK and nobody declared it here. Checkstyle
 * ({@code config/checkstyle/checkstyle.xml}) closes that half. Neither gate alone is enough.
 */
@AnalyzeClasses(
        packages = "com.example.service",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class ArchitectureRulesTest {

    // ---------------------------------------------------------------- purity

    /*
     * KNOWN BLIND SPOT, found the hard way: javac inlines compile-time constants, so ArchUnit
     * cannot see a cross-package dependency that exists only as a `static final` primitive or
     * String constant. A class that reads `OtherPackage.SOME_INT` compiles to the literal value
     * and leaves NO reference to the declaring class in the bytecode, so there is nothing for
     * ArchUnit to find and the rule below passes while the leak is real.
     *
     * This bit once already: the first probe of this rule "passed" for exactly that reason, which
     * looked like proof the rule worked. It was proof of nothing.
     *
     * Consequences for anyone trusting this class: a constant leaking across a package boundary
     * (query/ reading a constant declared in domain/, say) is invisible here. Only a dependency
     * carried by a type — a field, parameter, return type, annotation, instantiation or method
     * call — is visible. Catching the constant case needs a source-level gate: Checkstyle's
     * IllegalImport, or a plain grep, not ArchUnit.
     */
    @ArchTest
    static final ArchRule write_and_read_models_stay_pure_java =
            noClasses()
                    .that().resideInAnyPackage("..domain..", "..query..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..adapters..",
                            "org.springframework..",
                            "com.fasterxml.jackson..",
                            "tools.jackson..",
                            "org.apache.kafka..",
                            "jakarta.persistence..",
                            "javax.persistence..",
                            "org.hibernate..",
                            "com.querydsl..",
                            "org.jooq..")
                    .because("domain/ and query/ may use plain Java, shared.kernel and JSpecify only; "
                            + "anything else makes the business rules untestable without a container");

    @ArchTest
    static final ArchRule orm_and_query_builders_are_absent_everywhere =
            noClasses()
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "jakarta.persistence..",
                            "javax.persistence..",
                            "org.hibernate..",
                            "com.querydsl..",
                            "org.jooq..")
                    .because("persistence is plain Spring Data JDBC with native SQL mapped to records; "
                            + "models reach for @Entity and JPAQueryFactory on reflex, so block them outright");

    // ------------------------------------------------------------------ CQRS

    @ArchTest
    static final ArchRule read_model_does_not_see_write_model =
            noClasses()
                    .that().resideInAPackage("..query..")
                    .should().dependOnClassesThat().resideInAPackage("..domain..")
                    .because("a projection that can touch the aggregate will eventually load it");

    @ArchTest
    static final ArchRule write_model_does_not_see_read_model =
            noClasses()
                    .that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAPackage("..query..")
                    .because("the write side must not be shaped by what a screen happens to display");

    /*
     * `archRule.failOnEmptyShould` is TRUE globally (src/test/resources/archunit.properties), with
     * no per-rule exceptions: every rule in this class matches at least one class, so a rule that
     * stops matching is a typo and fails the build rather than passing silently.
     *
     * These two rules carried a temporary `allowEmptyShould(true)` while the RED phase had written
     * domain/ and query/ but no adapters/web/. The controllers now exist, so both allowances are
     * gone. They were confirmed redundant by breaking the rule on purpose — pointing
     * SubmissionQueryController at domain/ produced "query_controllers_touch_only_the_read_side ...
     * was violated (2 times)" and a BUILD FAILURE — which is the only way to know a structural
     * gate is actually looking at something.
     */
    @ArchTest
    static final ArchRule command_controllers_touch_only_the_write_side =
            noClasses()
                    .that().haveSimpleNameEndingWith("CommandController")
                    .should().dependOnClassesThat().resideInAPackage("..query..")
                    .because("a command endpoint talks to *CommandFacade and nothing else");

    @ArchTest
    static final ArchRule query_controllers_touch_only_the_read_side =
            noClasses()
                    .that().haveSimpleNameEndingWith("QueryController")
                    .should().dependOnClassesThat().resideInAPackage("..domain..")
                    .because("a query endpoint talks to *QueryFacade and nothing else");

    @ArchTest
    static final ArchRule commands_and_queries_are_standalone_records =
            classes()
                    .that().haveSimpleNameEndingWith("Command")
                    .or().haveSimpleNameEndingWith("Query")
                    .should().beRecords()
                    .because("a command is an immutable bag of input, not a node in a command bus; "
                            + "a shared Command interface forces every operation into one return type "
                            + "and one bag of errors");

    // ------------------------------------------------------- no hidden layers

    @ArchTest
    static final ArchRule no_procedural_service_layer =
            noClasses()
                    .should().haveSimpleNameEndingWith("Service")
                    .because("entry points are *CommandFacade and *QueryFacade; a *Service class is how "
                            + "an anaemic model and a god object grow back");

    @ArchTest
    static final ArchRule no_grab_bag_classes =
            noClasses()
                    .should().haveSimpleNameEndingWith("Util")
                    .orShould().haveSimpleNameEndingWith("Utils")
                    .orShould().haveSimpleNameEndingWith("Helper")
                    .orShould().haveSimpleNameEndingWith("Manager")
                    .because("behaviour belongs on the type that owns the data");

    // ---------------------------------------------------- dependency injection

    @ArchTest
    static final ArchRule no_field_injection =
            noFields()
                    .should().beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
                    .orShould().beAnnotatedWith("jakarta.inject.Inject")
                    .because("dependencies must be injected through constructors; "
                            + "field injection hides dependencies and bypasses compile-time checks");

    // ------------------------------------------------------- failures as data

    @ArchTest
    static final ArchRule no_custom_throwables =
            noClasses()
                    .should().beAssignableTo(Throwable.class)
                    .because("business failures are values returned in Result.failure(...), "
                            + "not control flow. NOTE: this only catches declared exception types. "
                            + "`throw new IllegalArgumentException(...)` is invisible here and is "
                            + "caught by the Checkstyle noThrowNew rule instead");

    // --------------------------------------------------- virtual-thread safety

    @ArchTest
    static final ArchRule no_thread_local_on_virtual_threads =
            noClasses()
                    .should().dependOnClassesThat().areAssignableTo(ThreadLocal.class)
                    .because("the service runs on virtual threads; use ActorContext / ScopedValue");

    // ----------------------------------------------------------- messaging

    @ArchTest
    static final ArchRule kafka_is_reachable_only_from_the_outbound_adapter =
            noClasses()
                    .that().resideOutsideOfPackages("..adapters.messaging..", "..adapters.config..")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("org.springframework.kafka.core.KafkaTemplate")
                    .because("publishing goes through the Transactional Outbox relay; a KafkaTemplate "
                            + "injected into business code is a dual write waiting to lose a message");
}
