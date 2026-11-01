package com.example.service;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

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
