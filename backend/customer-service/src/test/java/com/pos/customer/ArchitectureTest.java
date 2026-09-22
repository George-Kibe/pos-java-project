package com.pos.customer;

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
 * <p>The purity rule matters most here: what a shilling earns, which tier it reaches and which
 * points are spent first are the rules a member will argue about, and each is tested directly
 * without a database.
 */
@AnalyzeClasses(
        packages = "com.pos.customer",
        importOptions = {ImportOption.DoNotIncludeTests.class})
class ArchitectureTest {

    @ArchTest
    static final ArchRule layersAreRespected =
            Architectures.layeredArchitecture()
                    .consideringOnlyDependenciesInLayers()
                    .layer("Api")
                    .definedBy("com.pos.customer.api..")
                    .layer("Service")
                    .definedBy("com.pos.customer.service..")
                    .layer("Messaging")
                    .definedBy("com.pos.customer.messaging..")
                    .layer("Repository")
                    .definedBy("com.pos.customer.repository..")
                    .layer("Domain")
                    .definedBy("com.pos.customer.domain..")
                    .whereLayer("Api")
                    .mayNotBeAccessedByAnyLayer()
                    .whereLayer("Service")
                    .mayOnlyBeAccessedByLayers("Api", "Messaging")
                    .whereLayer("Repository")
                    .mayOnlyBeAccessedByLayers("Service");

    @ArchTest
    static final ArchRule thePoliciesStayPure =
            noClasses()
                    .that()
                    .resideInAPackage("com.pos.customer.domain.policy..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.springframework..",
                            "jakarta.persistence..",
                            "com.pos.customer.repository..",
                            "com.pos.customer.service..")
                    .because(
                            "what a shilling earns, which tier it reaches and which points go"
                                    + " first are rules a member argues about; each must be"
                                    + " testable without a database");

    @ArchTest
    static final ArchRule controllersDoNotReachPastTheServiceLayer =
            noClasses()
                    .that()
                    .resideInAPackage("com.pos.customer.api..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("com.pos.customer.repository..")
                    .because(
                            "a controller that reaches past the service layer skips its transaction"
                                    + " boundary, its status machine and its events");

    @ArchTest
    static final ArchRule eventsGoThroughTheOutbox =
            noClasses()
                    .that()
                    .resideOutsideOfPackage("com.pos.customer.messaging..")
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
                    .resideInAPackage("com.pos.customer.domain..")
                    .should()
                    .haveRawType(double.class)
                    .orShould()
                    .haveRawType(float.class)
                    .orShould()
                    .haveRawType(Double.class)
                    .orShould()
                    .haveRawType(Float.class)
                    .because(
                            "spend decides a tier and a tier decides what is earned; floating"
                                    + " point puts a member on the wrong rung");

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
