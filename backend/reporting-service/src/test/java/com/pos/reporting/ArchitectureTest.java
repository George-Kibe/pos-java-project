package com.pos.reporting;

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
 * <p>The purity rule matters most here: the till's arithmetic is what a Z-report reconciles
 * against, so it is stated once, without Spring or a database, and tested directly.
 */
@AnalyzeClasses(
        packages = "com.pos.reporting",
        importOptions = {ImportOption.DoNotIncludeTests.class})
class ArchitectureTest {

    @ArchTest
    static final ArchRule layersAreRespected =
            Architectures.layeredArchitecture()
                    .consideringOnlyDependenciesInLayers()
                    .layer("Api")
                    .definedBy("com.pos.reporting.api..")
                    .layer("Service")
                    .definedBy("com.pos.reporting.service..")
                    .layer("Messaging")
                    .definedBy("com.pos.reporting.messaging..")
                    // No repository layer: the read models are plain SQL over fact tables, run by
                    // the
                    // services that own the queries. ArchUnit rejects a layer with nothing in it.
                    .layer("Domain")
                    .definedBy("com.pos.reporting.domain..")
                    .whereLayer("Api")
                    .mayNotBeAccessedByAnyLayer()
                    .whereLayer("Service")
                    .mayOnlyBeAccessedByLayers("Api", "Messaging");

    @ArchTest
    static final ArchRule thePoliciesStayPure =
            noClasses()
                    .that()
                    .resideInAPackage("com.pos.reporting.domain.policy..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.springframework..",
                            "jakarta.persistence..",
                            "com.pos.reporting.repository..",
                            "com.pos.reporting.service..")
                    .because(
                            "the till's arithmetic is what a Z-report reconciles against; it"
                                    + " must be testable without a database");

    @ArchTest
    static final ArchRule controllersDoNotReachPastTheServiceLayer =
            noClasses()
                    .that()
                    .resideInAPackage("com.pos.reporting.api..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("com.pos.reporting.repository..")
                    .because(
                            "a controller that reaches past the service layer skips its transaction"
                                    + " boundary, its status machine and its events");

    @ArchTest
    static final ArchRule eventsGoThroughTheOutbox =
            noClasses()
                    .that()
                    .resideOutsideOfPackage("com.pos.reporting.messaging..")
                    .should()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName("org.springframework.kafka.core.KafkaTemplate")
                    .because(
                            "a send from inside business logic publishes an event for a"
                                    + " transaction that may still roll back; the outbox is what ties"
                                    + " the two together");

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
                    .resideInAPackage("com.pos.reporting.domain..")
                    .should()
                    .haveRawType(double.class)
                    .orShould()
                    .haveRawType(float.class)
                    .orShould()
                    .haveRawType(Double.class)
                    .orShould()
                    .haveRawType(Float.class)
                    .because(
                            "a report in floating point stops agreeing with the till a cent at"
                                    + " a time");

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
