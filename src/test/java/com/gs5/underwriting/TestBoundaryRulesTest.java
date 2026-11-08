package com.gs5.underwriting;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Tests drive business flows through the public contract of a feature (design section "Test
 * contract"), never through the developer's internal structure. This class makes the part of that
 * rule a compiler cannot see a build failure.
 *
 * <p>What it checks: no test class depends on a production class that is not {@code public}. A
 * package-private class (the aggregate, a mapper, a JDBC entity, an SQL adapter) is internal by
 * construction, so a test that reaches it pins the developer's structure.
 *
 * <p>What it does not check: a {@code public} class that the design leaves out of the Test
 * contract. Nothing in the bytecode says which public types the design lists; that half stays
 * with review against the design.
 *
 * <p>The rule is <strong>frozen</strong>. Tests written before it existed reach internals, and
 * rewriting them all is test-design work of its own. Their violations are recorded in
 * {@code src/test/resources/archunit_store}; a violation that is not recorded fails the build,
 * and one that disappears is removed from the store. The store may only shrink: creating or
 * extending it is disabled in {@code archunit.properties}.
 */
@AnalyzeClasses(
        packages = "com.gs5.underwriting",
        importOptions = ImportOption.OnlyIncludeTests.class
)
class TestBoundaryRulesTest {

    /** A class compiled from src/main: its class file is not under test-classes. */
    private static final DescribedPredicate<JavaClass> PRODUCTION_CLASS =
            DescribedPredicate.describe("production classes", javaClass -> javaClass.getSource()
                    .map(source -> source.getUri().toString())
                    .map(uri -> uri.contains("/classes/") && !uri.contains("/test-classes/"))
                    .orElse(false));

    private static final DescribedPredicate<JavaClass> NOT_PUBLIC =
            DescribedPredicate.describe("are not public",
                    javaClass -> !javaClass.getModifiers().contains(JavaModifier.PUBLIC));

    @ArchTest
    static final ArchRule tests_use_only_the_public_contract_of_production_code =
            FreezingArchRule.freeze(noClasses()
                    .should().dependOnClassesThat(PRODUCTION_CLASS.and(NOT_PUBLIC))
                    .as("tests use only the public contract of production code, never a "
                            + "package-private internal"));
}
