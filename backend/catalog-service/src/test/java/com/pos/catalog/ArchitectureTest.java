package com.pos.catalog;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.Architectures;

/**
 * The structural rules from CLAUDE.md, enforced by the build.
 *
 * <p>The pricing rule below is the one that matters most here: the engine must stay free of Spring
 * and persistence, because that is what lets every tax and promotion combination be tested directly
 * rather than through a database and a context.
 */
@AnalyzeClasses(
        packages = "com.pos.catalog",
        importOptions = {ImportOption.DoNotIncludeTests.class})
class ArchitectureTest {

    @ArchTest
    static final ArchRule layersAreRespected =
            Architectures.layeredArchitecture()
                    .consideringOnlyDependenciesInLayers()
                    .layer("Api")
                    .definedBy("com.pos.catalog.api..")
                    .layer("Service")
                    .definedBy("com.pos.catalog.service..")
                    .layer("Messaging")
                    .definedBy("com.pos.catalog.messaging..")
                    .layer("Repository")
                    .definedBy("com.pos.catalog.repository..")
                    .layer("Domain")
                    .definedBy("com.pos.catalog.domain..")
                    .whereLayer("Api")
                    .mayNotBeAccessedByAnyLayer()
                    .whereLayer("Service")
                    .mayOnlyBeAccessedByLayers("Api")
                    .whereLayer("Repository")
                    .mayOnlyBeAccessedByLayers("Service", "Api");

    @ArchTest
    static final ArchRule thePricingEngineStaysPure =
            noClasses()
                    .that()
                    .resideInAPackage("com.pos.catalog.domain.pricing..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.springframework..",
                            "jakarta.persistence..",
                            "com.pos.catalog.repository..",
                            "com.pos.catalog.service..")
                    .because(
                            "the engine is tested by calling it directly with every combination of"
                                    + " tax treatment, quantity and promotion; a dependency on Spring or"
                                    + " JPA would put a database and a context between the test and the"
                                    + " arithmetic");

    @ArchTest
    static final ArchRule theBarcodeDecoderStaysPure =
            noClasses()
                    .that()
                    .haveSimpleNameEndingWith("Decoder")
                    .or()
                    .haveSimpleName("Ean13")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("org.springframework..", "com.pos.catalog.repository..")
                    .because("every scale barcode layout should be testable without a database");

    @ArchTest
    static final ArchRule controllersMustNotUseRepositoriesForWrites =
            noClasses()
                    .that()
                    .resideInAPackage("com.pos.catalog.api..")
                    .and()
                    .haveSimpleNameEndingWith("Controller")
                    .and()
                    .haveSimpleNameNotEndingWith("ReferenceDataController")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("com.pos.catalog.repository..")
                    .because(
                            "a controller that reaches past the service layer skips its transaction"
                                    + " boundary and its events");

    @ArchTest
    static final ArchRule noFieldInjection =
            noFields()
                    .should()
                    .beAnnotatedWith(org.springframework.beans.factory.annotation.Autowired.class)
                    .because("constructor injection keeps dependencies visible and testable");

    @ArchTest
    static final ArchRule moneyIsNeverAFloatingPointNumber =
            noFields()
                    .that()
                    .areDeclaredInClassesThat()
                    .resideInAPackage("com.pos.catalog.domain..")
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
    static final ArchRule noLegacyDateTimeTypes =
            noClasses()
                    .should()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName("java.util.Date")
                    .orShould()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName("java.util.Calendar")
                    .because("timestamps are Instants stored as TIMESTAMPTZ in UTC");
}
