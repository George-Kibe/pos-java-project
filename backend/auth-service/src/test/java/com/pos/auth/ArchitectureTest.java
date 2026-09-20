package com.pos.auth;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.Architectures;

/**
 * The structural rules from CLAUDE.md, enforced by the build rather than by review.
 *
 * <p>These are the conventions that decay silently. A controller reaching straight into a
 * repository still works, still passes its tests, and is only a problem months later when the
 * business rule it skipped turns out to matter. Catching it here costs nothing.
 *
 * <p>When the second service lands, promote this to a shared test module so every service inherits
 * the same rules instead of copying them.
 */
@AnalyzeClasses(
        packages = "com.pos.auth",
        importOptions = {ImportOption.DoNotIncludeTests.class})
class ArchitectureTest {

    @ArchTest
    static final ArchRule layersAreRespected =
            Architectures.layeredArchitecture()
                    .consideringOnlyDependenciesInLayers()
                    .layer("Api")
                    .definedBy("com.pos.auth.api..")
                    .layer("Service")
                    .definedBy("com.pos.auth.service..")
                    .layer("Repository")
                    .definedBy("com.pos.auth.repository..")
                    .layer("Domain")
                    .definedBy("com.pos.auth.domain..")
                    .layer("Config")
                    .definedBy("com.pos.auth.config..", "com.pos.auth.security..")
                    // Nothing may depend on the API layer: it is the outermost edge.
                    .whereLayer("Api")
                    .mayNotBeAccessedByAnyLayer()
                    .whereLayer("Service")
                    .mayOnlyBeAccessedByLayers("Api", "Config")
                    .whereLayer("Repository")
                    .mayOnlyBeAccessedByLayers("Service", "Config");

    @ArchTest
    static final ArchRule controllersMustNotUseRepositoriesForWrites =
            noClasses()
                    .that()
                    .resideInAPackage("com.pos.auth.api..")
                    .and()
                    .haveSimpleNameEndingWith("Controller")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("com.pos.auth.repository..")
                    .because(
                            "a controller that reaches past the service layer skips its transaction"
                                    + " boundary, its authorization checks and its audit trail");

    @ArchTest
    static final ArchRule noEntityLeavesThroughTheApi =
            methods()
                    .that()
                    .areDeclaredInClassesThat()
                    .resideInAPackage("com.pos.auth.api..")
                    .and()
                    .arePublic()
                    .should()
                    .notHaveRawReturnType(
                            com.tngtech.archunit.base.DescribedPredicate.describe(
                                    "a persistent entity",
                                    javaClass ->
                                            javaClass
                                                            .getPackageName()
                                                            .startsWith("com.pos.auth.domain")
                                                    && javaClass.isAnnotatedWith(
                                                            jakarta.persistence.Entity.class)))
                    .because(
                            "returning an entity ties the wire format to the schema and serialises"
                                    + " whatever happens to be loaded, including password hashes");

    @ArchTest
    static final ArchRule noFieldInjection =
            noFields()
                    .should()
                    .beAnnotatedWith(org.springframework.beans.factory.annotation.Autowired.class)
                    .because(
                            "constructor injection makes dependencies visible and the object"
                                    + " constructible in a test without a container");

    @ArchTest
    static final ArchRule domainDoesNotDependOnTheOuterLayers =
            noClasses()
                    .that()
                    .resideInAPackage("com.pos.auth.domain..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("com.pos.auth.api..", "com.pos.auth.service..")
                    .because("the domain must be usable without the machinery around it");

    @ArchTest
    static final ArchRule noLegacyDateTimeTypes =
            noClasses()
                    .should()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName("java.util.Date")
                    .orShould()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName("java.util.Calendar")
                    .orShould()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName("java.sql.Date")
                    .because(
                            "every timestamp in this system is an Instant stored as TIMESTAMPTZ in"
                                    + " UTC; the legacy types carry an implicit local time zone");

    @ArchTest
    static final ArchRule moneyIsNeverAFloatingPointNumber =
            noFields()
                    .that()
                    .areDeclaredInClassesThat()
                    .resideInAPackage("com.pos.auth.domain..")
                    .should()
                    .haveRawType(double.class)
                    .orShould()
                    .haveRawType(float.class)
                    .orShould()
                    .haveRawType(Double.class)
                    .orShould()
                    .haveRawType(Float.class)
                    .because("floating point loses cents, and a till must reconcile exactly");

    @ArchTest
    static final ArchRule repositoriesAreInterfacesInTheRepositoryPackage =
            fields().that()
                    .haveRawType(
                            com.tngtech.archunit.base.DescribedPredicate.describe(
                                    "a Spring Data repository",
                                    javaClass ->
                                            javaClass.isAssignableTo(
                                                    org.springframework.data.repository.Repository
                                                            .class)))
                    .should()
                    .beDeclaredInClassesThat()
                    .resideInAnyPackage(
                            "com.pos.auth.service..",
                            "com.pos.auth.config..",
                            "com.pos.auth.repository..")
                    .because("data access belongs behind the service layer");
}
