package com.gs5.underwriting;

import com.gs5.underwriting.shared.kernel.ActorContext;
import com.gs5.underwriting.submission.domain.SubmissionDomainEvent;
import com.gs5.underwriting.submission.domain.SubmissionId;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleName;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
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
        packages = "com.gs5.underwriting",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class ArchitectureRulesTest {

    // ---------------------------------------------------------------- purity

    /*
     * KNOWN BLIND SPOT, found the hard way: javac inlines compile-time constants, so ArchUnit
     * cannot see a cross-package dependency that exists only as a `static final` primitive or
     * String constant. A class that reads `OtherPackage.SOME_INT` compiles to the literal value
     * and leaves NO number to the declaring class in the bytecode, so there is nothing for
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
                    .that().resideInAPackage("..domain..")
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
                    .because("domain/, which holds both the write and the read side, may use plain Java, "
                            + "shared.kernel and JSpecify only; anything else makes the business rules "
                            + "untestable without a container");

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

    // -------------------------------------------------- CQS, without folders (design §5.6, §15.10)

    /*
     * `archRule.failOnEmptyShould` is TRUE globally (src/test/resources/archunit.properties), with
     * no per-rule exceptions: every rule in this class matches at least one class, so a rule that
     * stops matching is a typo and fails the build rather than passing silently.
     *
     * Command-query separation here is a separation of RESPONSIBILITY, not of folders. The read side
     * lives in domain/ beside the write side, and a read use case is told from a write use case by
     * the one public method it exposes — `ask` or `execute` — never by its package and never by a
     * name prefix. A prefix is a hint to a reader; the method is what the build reads.
     *
     * Removed with the query/ package, because each one keyed on it: read_model_does_not_see_write_model,
     * write_model_does_not_see_read_model, the two package-based controller rules
     * (command_controllers_touch_only_the_write_side, query_controllers_touch_only_the_read_side),
     * and use_cases_have_exactly_one_public_method_named_for_their_side, which inferred `ask` from
     * the `.query` package.
     *
     * API NOTE, checked against ArchUnit 1.5.1 with javac: the design writes the forbidden targets as
     * `dependOnClassesThat().haveSimpleName(..).or().haveSimpleNameEndingWith(..)`. That does NOT
     * compile — `or()` does not exist on ClassesShouldConjunction. The targets are therefore one
     * combined predicate passed to `dependOnClassesThat(DescribedPredicate)`, which does exist and
     * means exactly what the design intends.
     */

    /** A public method with this name. A use case's one public method is what makes it read or write. */
    private static DescribedPredicate<JavaClass> exposingPublicMethodNamed(String name) {
        return new DescribedPredicate<>("exposing a public method named '" + name + "'") {
            @Override
            public boolean test(JavaClass type) {
                return type.getMethods().stream().anyMatch(method ->
                        method.getName().equals(name)
                                && method.getModifiers().contains(JavaModifier.PUBLIC));
            }
        };
    }

    /** A use case, read or write by the method it exposes. Selecting on both keeps JDK and Spring types out. */
    private static DescribedPredicate<JavaClass> useCaseExposing(String name) {
        return simpleNameEndingWith("UseCase").and(exposingPublicMethodNamed(name));
    }

    @ArchTest
    static final ArchRule no_query_package_exists =
            noClasses()
                    .should().resideInAPackage("..query..")
                    .because("CQS is a separation of responsibility, not of folders. Read use cases live in "
                            + "domain/ beside the write side. A query/ package reintroduces the import ban "
                            + "that forced duplication and primitive obsession");

    @ArchTest
    static final ArchRule read_use_cases_do_not_depend_on_the_write_side =
            noClasses()
                    .that().haveSimpleNameEndingWith("UseCase")
                    .and(exposingPublicMethodNamed("ask"))
                    .should().dependOnClassesThat(
                            simpleName("Submission")
                                    .or(simpleNameEndingWith("CommandRepository"))
                                    .or(simpleNameEndingWith("EventPublisher"))
                                    .or(simpleNameEndingWith("IdGenerator"))
                                    .or(simpleNameEndingWith("Command")))
                    .because("a read use case (ask) must not touch the aggregate, a command port, or a "
                            + "command: a read cannot change state, and the rule keeps it that way");

    @ArchTest
    static final ArchRule write_use_cases_do_not_depend_on_the_read_side =
            noClasses()
                    .that().haveSimpleNameEndingWith("UseCase")
                    .and(exposingPublicMethodNamed("execute"))
                    .should().dependOnClassesThat(
                            simpleNameEndingWith("QueryRepository")
                                    .or(simpleNameEndingWith("View"))
                                    .or(simpleNameEndingWith("Query"))
                                    .or(simpleName("SubmissionFilter"))
                                    .or(simpleName("SubmissionSearchCriteria")))
                    .because("a write use case (execute) must not read: a write that depends on a query "
                            + "port or a view is deciding on data it did not load through its own port");

    @ArchTest
    static final ArchRule aggregate_does_not_depend_on_read_types =
            noClasses()
                    .that().haveSimpleName("Submission")
                    .should().dependOnClassesThat(
                            simpleNameEndingWith("View")
                                    .or(simpleNameEndingWith("Query"))
                                    .or(simpleName("SubmissionFilter"))
                                    .or(simpleName("SubmissionSearchCriteria")))
                    .because("the aggregate protects the write model; a projection must not shape it");

    /*
     * One UseCase = one business operation = one public method, and that method declares whether
     * it reads or writes.
     *
     * DEVIATION from the design's verbatim text, on purpose: §15.10 requires `parameters.size() == 1`,
     * but §5.5 and §3 give the target signatures as `ask(ActorContext, SearchSubmissionsQuery)` and
     * `execute(ActorContext, RegisterSubmissionCommand)` — two parameters, because the admitted actor
     * is passed in. The verbatim rule would fail the design's own target forever. This version
     * requires exactly one *Query (ask) or *Command (execute) parameter, and allows the admitted
     * ActorContext beside it and nothing else. Both today's one-parameter form and the target pass.
     */
    @ArchTest
    static final ArchRule use_cases_expose_exactly_one_public_method_named_ask_or_execute =
            classes()
                    .that().haveSimpleNameEndingWith("UseCase")
                    .should(new ArchCondition<JavaClass>(
                            "expose exactly one public method, named 'ask' (taking a *Query) or "
                                    + "'execute' (taking a *Command), optionally beside the ActorContext") {
                        @Override
                        public void check(JavaClass useCase, ConditionEvents events) {
                            var publicMethods = useCase.getMethods().stream()
                                    .filter(method -> method.getModifiers().contains(JavaModifier.PUBLIC))
                                    .toList();
                            if (publicMethods.size() != 1) {
                                events.add(SimpleConditionEvent.violated(useCase,
                                        useCase.getName() + " must expose exactly one public method, but has "
                                                + publicMethods.stream().map(JavaMethod::getName).toList()));
                                return;
                            }
                            var method = publicMethods.getFirst();
                            var suffix = switch (method.getName()) {
                                case "ask" -> "Query";
                                case "execute" -> "Command";
                                default -> "";
                            };
                            var parameters = method.getRawParameterTypes();
                            var operationParameters = parameters.stream()
                                    .filter(parameter -> !suffix.isEmpty()
                                            && parameter.getSimpleName().endsWith(suffix))
                                    .count();
                            var othersAreTheActor = parameters.stream()
                                    .filter(parameter -> suffix.isEmpty()
                                            || !parameter.getSimpleName().endsWith(suffix))
                                    .allMatch(parameter -> parameter.isEquivalentTo(ActorContext.class));
                            if (suffix.isEmpty() || operationParameters != 1 || !othersAreTheActor) {
                                events.add(SimpleConditionEvent.violated(useCase,
                                        useCase.getName() + "." + method.getName() + " must be 'ask(*Query)' or "
                                                + "'execute(*Command)', optionally with the ActorContext"));
                            }
                        }
                    })
                    .because("each UseCase is a single business operation with one reason to change, and its "
                            + "one public method declares whether it reads or writes");

    @ArchTest
    static final ArchRule query_controllers_depend_on_no_write_use_case =
            noClasses()
                    .that().haveSimpleNameEndingWith("QueryResource")
                    .should().dependOnClassesThat(useCaseExposing("execute"))
                    .because("a query endpoint may call only an ask use case; calling execute from it would "
                            + "let a read request change state");

    @ArchTest
    static final ArchRule command_controllers_depend_on_no_read_use_case =
            noClasses()
                    .that().haveSimpleNameEndingWith("CommandResource")
                    .should().dependOnClassesThat(useCaseExposing("ask"))
                    .because("a command endpoint may call only an execute use case; calling ask from it would "
                            + "let a write request depend on a read");

    // ------------------------------------------- parse, don't validate (design §15.9)

    /**
     * The {@code valueOf(String)} that javac generates in every enum. It is not input: it is the
     * enum's own name lookup, which the compiler writes and nobody calls on raw business text.
     */
    private static final DescribedPredicate<JavaMethod> ENUM_GENERATED_VALUE_OF =
            DescribedPredicate.describe("the compiler-generated valueOf of an enum",
                    method -> method.getOwner().isEnum() && method.getName().equals("valueOf"));

    /** A method with at least one String or BigDecimal parameter: raw input. */
    private static final DescribedPredicate<JavaMethod> TAKING_RAW_INPUT =
            new DescribedPredicate<>("taking a String or BigDecimal parameter") {
                @Override
                public boolean test(JavaMethod method) {
                    return method.getRawParameterTypes().stream().anyMatch(parameter ->
                            parameter.isEquivalentTo(String.class)
                                    || parameter.isEquivalentTo(BigDecimal.class));
                }
            };

    /** SubmissionFilter itself, or a record nested in it. */
    private static final DescribedPredicate<JavaClass> SUBMISSION_FILTER_OR_NESTED =
            new DescribedPredicate<>("SubmissionFilter or a type nested in it") {
                @Override
                public boolean test(JavaClass type) {
                    return type.getSimpleName().equals("SubmissionFilter")
                            || type.getEnclosingClass()
                                    .map(enclosing -> enclosing.getSimpleName().equals("SubmissionFilter"))
                                    .orElse(false);
                }
            };

    /*
     * `methods()` excludes constructors, so a record's canonical constructor is never caught: that is
     * how views, the snapshot, the event, the error records and the criteria hold String and
     * BigDecimal without tripping this. Synthetic methods are excluded too — a lambda body compiles
     * to a synthetic `lambda$...` method whose parameters are its captures, which nobody calls and
     * nobody can name `parse`. That exclusion is not in the design's text and is flagged as such.
     */
    @ArchTest
    static final ArchRule raw_input_enters_only_through_parse =
            methods()
                    .that().areDeclaredInClassesThat().resideInAPackage("..domain..")
                    .and().doNotHaveModifier(JavaModifier.SYNTHETIC)
                    .and(DescribedPredicate.not(ENUM_GENERATED_VALUE_OF))
                    .and(TAKING_RAW_INPUT)
                    .should().haveName("parse")
                    .because("raw input enters the domain only as the parameter of a method named parse; "
                            + "anywhere else a String or BigDecimal parameter is an unparsed value reaching "
                            + "business code");

    /*
     * Input objects only: commands, queries and the search filter. Output records — views, criteria,
     * snapshot — are out of scope by design (§5.3). Static fields are excluded: a constant is not
     * input. The filter's BigDecimal and Instant bounds are allowed on purpose, since every value of
     * those types is a legal comparison value; its number is an SubmissionNumber, so a String
     * there would mean the parse was bypassed.
     *
     * The command/query selector is ONE combined predicate passed to areDeclaredInClassesThat(...),
     * not `.or()` on the fluent chain, which would widen the whole rule.
     */
    @ArchTest
    static final ArchRule commands_and_queries_carry_only_parsed_values =
            CompositeArchRule.of(
                            noFields()
                                    .that().areDeclaredInClassesThat(
                                            simpleNameEndingWith("Command").or(simpleNameEndingWith("Query")))
                                    .and().areNotStatic()
                                    .should().haveRawType(String.class)
                                    .orShould().haveRawType(BigDecimal.class))
                    .and(noFields()
                            .that().areDeclaredInClassesThat(SUBMISSION_FILTER_OR_NESTED)
                            .and().areNotStatic()
                            .should().haveRawType(String.class))
                    .because("domain objects hold no raw input fields: a command, a query or the filter "
                            + "holds only values that went through parse");

    /*
     * Rule 1 of §15.1: a type that guards an invariant is a final class with a private constructor,
     * never a record, because a record's canonical constructor is public. A record cannot satisfy
     * this rule, so an invariant guarded by a record is caught.
     */
    @ArchTest
    static final ArchRule guarded_types_have_no_public_constructor =
            classes()
                    .that().arePublic()
                    .and().areNotRecords()
                    .and().areNotInterfaces()
                    .and().areNotEnums()
                    .and().resideInAnyPackage("..domain..", "..shared.kernel..", "..adapters.access..")
                    .and().haveSimpleNameNotEndingWith("UseCase")
                    .and().doNotHaveSimpleName("OperationGate")
                    .should(new ArchCondition<JavaClass>("have no public constructor") {
                        @Override
                        public void check(JavaClass type, ConditionEvents events) {
                            type.getConstructors().stream()
                                    .filter(constructor -> constructor.getModifiers().contains(JavaModifier.PUBLIC))
                                    .forEach(constructor -> events.add(SimpleConditionEvent.violated(type,
                                            type.getName() + " has a public constructor, so its invariant "
                                                    + "can be bypassed: " + constructor.getFullName())));
                        }
                    })
                    .because("raw input must enter a guarded type only through parse; a public "
                            + "constructor is a second way in");

    // ------------------------------------------------------------- records

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
    static final ArchRule no_procedural_service_or_facade_layer =
            noClasses()
                    .should().haveSimpleNameEndingWith("Service")
                    .orShould().haveSimpleNameEndingWith("Facade")
                    .because("entry points are single-operation *UseCase classes; a *Service or *Facade "
                            + "collects unrelated operations, every new feature edits the same file, and "
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

    // ------------------------------------------------ approval (design §19.10, rules A to C)

    /*
     * API NOTE, checked against ArchUnit 1.5.1 with javac: rule B in the design chains `.or()` after
     * `dependOnClassesThat().haveSimpleNameEndingWith(..)`. That does not compile, because `or()` is not
     * on ClassesShouldConjunction. Rule B passes one combined predicate, as the CQS rules above do.
     * Rules A and C compile exactly as written in the design.
     *
     * All three pass at HEAD, which is correct for guards: HEAD violates none of them. Each must be
     * shown to fail by breaking the code on purpose once, as ADR section 19.10 requires.
     *
     * Rule C as written in the design, `callConstructor(SubmissionApproved.class)`, compiles and
     * passes even when a violation exists. ArchUnit reads that overload as "a constructor taking no
     * parameters", and SubmissionApproved has none, so the rule could never match. The canonical
     * constructor's parameter types are therefore stated. Caught by the deliberate break, not by review.
     */

    @ArchTest
    static final ArchRule no_operation_reverses_or_rewrites_a_recorded_fact =
            noMethods()
                    .that().areDeclaredInClassesThat().resideInAPackage("..domain..")
                    .should().haveNameMatching(
                            "(?i).*(unapprove|revoke|withdraw|reverse|cancel|amend|delete|update|remove).*")
                    .because("an approval is final (BR-36) and a registered fact never changes (BR-38); "
                            + "an operation that reverses or rewrites either must not exist, because a port "
                            + "method is the only way a write can happen");

    @ArchTest
    static final ArchRule approval_use_case_depends_on_no_read_type =
            noClasses()
                    .that().haveSimpleName("ApproveSubmissionUseCase")
                    .should().dependOnClassesThat(
                            simpleNameEndingWith("QueryRepository")
                                    .or(simpleNameEndingWith("View"))
                                    .or(simpleNameEndingWith("Query"))
                                    .or(simpleName("SubmissionFilter"))
                                    .or(simpleName("SubmissionSearchCriteria")))
                    .because("approval is a write (execute): it decides on the aggregate through its own port, "
                            + "and a read type in its dependencies would mean it decides on loaded projections");

    @ArchTest
    static final ArchRule only_the_approval_use_case_announces_an_approval =
            noClasses()
                    .that().doNotHaveSimpleName("ApproveSubmissionUseCase")
                    .should().callConstructor(SubmissionDomainEvent.SubmissionApproved.class,
                            UUID.class, SubmissionId.class, UUID.class, String.class, Instant.class)
                    .because("BR-40: an approval is announced exactly when an approval succeeds, and the single place "
                            + "that knows it succeeded is the use case, after the insert");

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
