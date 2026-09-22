package com.pos.payment;

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
 * <p>The purity rule matters most here: rounding to whole shillings, refund allocation and
 * statement reconciliation decide whose money is where, and each is tested directly without a
 * database. The provider rule keeps Daraja behind the port, so no controller can call it in a
 * request thread.
 */
@AnalyzeClasses(
        packages = "com.pos.payment",
        importOptions = {ImportOption.DoNotIncludeTests.class})
class ArchitectureTest {

    @ArchTest
    static final ArchRule layersAreRespected =
            Architectures.layeredArchitecture()
                    .consideringOnlyDependenciesInLayers()
                    .layer("Api")
                    .definedBy("com.pos.payment.api..")
                    .layer("Service")
                    .definedBy("com.pos.payment.service..")
                    .layer("Messaging")
                    .definedBy("com.pos.payment.messaging..")
                    .layer("Provider")
                    .definedBy("com.pos.payment.provider..")
                    .layer("Client")
                    .definedBy("com.pos.payment.client..")
                    .layer("Repository")
                    .definedBy("com.pos.payment.repository..")
                    .layer("Domain")
                    .definedBy("com.pos.payment.domain..")
                    .whereLayer("Api")
                    .mayNotBeAccessedByAnyLayer()
                    .whereLayer("Service")
                    .mayOnlyBeAccessedByLayers("Api", "Messaging")
                    .whereLayer("Provider")
                    .mayOnlyBeAccessedByLayers("Service")
                    .whereLayer("Client")
                    .mayOnlyBeAccessedByLayers("Provider", "Service")
                    .whereLayer("Repository")
                    .mayOnlyBeAccessedByLayers("Service");

    @ArchTest
    static final ArchRule thePoliciesStayPure =
            noClasses()
                    .that()
                    .resideInAPackage("com.pos.payment.domain.policy..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.springframework..",
                            "jakarta.persistence..",
                            "com.pos.payment.repository..",
                            "com.pos.payment.service..",
                            "com.pos.payment.client..")
                    .because(
                            "rounding, refund allocation and reconciliation decide whose money is"
                                    + " where; each must be testable without a database");

    @ArchTest
    static final ArchRule controllersDoNotReachPastTheServiceLayer =
            noClasses()
                    .that()
                    .resideInAPackage("com.pos.payment.api..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "com.pos.payment.repository..",
                            "com.pos.payment.client..",
                            "com.pos.payment.provider..")
                    .because(
                            "a controller that reaches past the service layer skips its transaction"
                                    + " boundary, its status machine and its events");

    @ArchTest
    static final ArchRule eventsGoThroughTheOutbox =
            noClasses()
                    .that()
                    .resideOutsideOfPackage("com.pos.payment.messaging..")
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
                    .resideInAPackage("com.pos.payment.domain..")
                    .should()
                    .haveRawType(double.class)
                    .orShould()
                    .haveRawType(float.class)
                    .orShould()
                    .haveRawType(Double.class)
                    .orShould()
                    .haveRawType(Float.class)
                    .because(
                            "a payment ledger in floating point stops reconciling against the"
                                    + " statement a cent at a time");

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
