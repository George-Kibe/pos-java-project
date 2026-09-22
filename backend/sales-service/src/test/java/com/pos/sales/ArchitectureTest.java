package com.pos.sales;

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
 * <p>The purity rules matter most here. The totals calculator decides what a customer pays and what
 * tax the shop owes, and the till policies decide change, refund eligibility and drawer variance.
 * Keeping them free of Spring and JPA is what lets every rounding edge be tested directly.
 */
@AnalyzeClasses(
        packages = "com.pos.sales",
        importOptions = {ImportOption.DoNotIncludeTests.class})
class ArchitectureTest {

    @ArchTest
    static final ArchRule layersAreRespected =
            Architectures.layeredArchitecture()
                    .consideringOnlyDependenciesInLayers()
                    .layer("Api")
                    .definedBy("com.pos.sales.api..")
                    .layer("Service")
                    .definedBy("com.pos.sales.service..")
                    .layer("Messaging")
                    .definedBy("com.pos.sales.messaging..")
                    .layer("Client")
                    .definedBy("com.pos.sales.client..")
                    .layer("Repository")
                    .definedBy("com.pos.sales.repository..")
                    .layer("Domain")
                    .definedBy("com.pos.sales.domain..")
                    .whereLayer("Api")
                    .mayNotBeAccessedByAnyLayer()
                    .whereLayer("Service")
                    .mayOnlyBeAccessedByLayers("Api", "Messaging")
                    .whereLayer("Client")
                    .mayOnlyBeAccessedByLayers("Service")
                    .whereLayer("Repository")
                    .mayOnlyBeAccessedByLayers("Service");

    @ArchTest
    static final ArchRule theTotalsCalculatorStaysPure =
            noClasses()
                    .that()
                    .resideInAPackage("com.pos.sales.domain.totals..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.springframework..",
                            "jakarta.persistence..",
                            "com.pos.sales.repository..",
                            "com.pos.sales.service..",
                            "com.pos.sales.client..")
                    .because(
                            "what the customer pays and what tax is owed is arithmetic over priced"
                                    + " lines; a dependency on Spring or JPA would put a database"
                                    + " between the test and the money");

    @ArchTest
    static final ArchRule theTillPoliciesStayPure =
            noClasses()
                    .that()
                    .resideInAPackage("com.pos.sales.domain.policy..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.springframework..",
                            "jakarta.persistence..",
                            "com.pos.sales.repository..",
                            "com.pos.sales.service..",
                            "com.pos.sales.client..")
                    .because(
                            "change due, the returns window and drawer variance are rules a"
                                    + " cashier disputes; each should be testable without a database");

    @ArchTest
    static final ArchRule controllersDoNotReachPastTheServiceLayer =
            noClasses()
                    .that()
                    .resideInAPackage("com.pos.sales.api..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("com.pos.sales.repository..", "com.pos.sales.client..")
                    .because(
                            "a controller that reaches past the service layer skips its transaction"
                                    + " boundary, its status machine and its events");

    @ArchTest
    static final ArchRule eventsGoThroughTheOutbox =
            noClasses()
                    .that()
                    .resideOutsideOfPackage("com.pos.sales.messaging..")
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
                    .resideInAPackage("com.pos.sales.domain..")
                    .should()
                    .haveRawType(double.class)
                    .orShould()
                    .haveRawType(float.class)
                    .orShould()
                    .haveRawType(Double.class)
                    .orShould()
                    .haveRawType(Float.class)
                    .because(
                            "a till that totals in floating point is out by a cent often enough to"
                                    + " fail a Z-report reconciliation");

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
