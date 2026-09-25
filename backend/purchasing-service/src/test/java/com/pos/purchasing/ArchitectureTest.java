package com.pos.purchasing;

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
 * <p>The two purity rules below matter most here. The landed-cost allocator and the three-way
 * matcher decide what stock is worth and whether a bill gets paid, and keeping them free of Spring
 * and JPA is what lets every awkward split and every shape of over-billing be tested directly.
 */
@AnalyzeClasses(
        packages = "com.pos.purchasing",
        importOptions = {ImportOption.DoNotIncludeTests.class})
class ArchitectureTest {

    @ArchTest
    static final ArchRule layersAreRespected =
            Architectures.layeredArchitecture()
                    .consideringOnlyDependenciesInLayers()
                    .layer("Api")
                    .definedBy("com.pos.purchasing.api..")
                    .layer("Service")
                    .definedBy("com.pos.purchasing.service..")
                    .layer("Messaging")
                    .definedBy("com.pos.purchasing.messaging..")
                    .layer("Client")
                    .definedBy("com.pos.purchasing.client..")
                    .layer("Repository")
                    .definedBy("com.pos.purchasing.repository..")
                    .layer("Domain")
                    .definedBy("com.pos.purchasing.domain..")
                    .whereLayer("Api")
                    .mayNotBeAccessedByAnyLayer()
                    .whereLayer("Service")
                    .mayOnlyBeAccessedByLayers("Api", "Messaging")
                    .whereLayer("Client")
                    .mayOnlyBeAccessedByLayers("Service")
                    .whereLayer("Repository")
                    .mayOnlyBeAccessedByLayers("Service");

    @ArchTest
    static final ArchRule theLandedCostAllocatorStaysPure =
            noClasses()
                    .that()
                    .resideInAPackage("com.pos.purchasing.domain.cost..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.springframework..",
                            "jakarta.persistence..",
                            "com.pos.purchasing.repository..",
                            "com.pos.purchasing.service..")
                    .because(
                            "what stock is worth is arithmetic over a delivery's charges; a"
                                    + " dependency on Spring or JPA would put a database and a context"
                                    + " between the test and the money");

    @ArchTest
    static final ArchRule theMatcherStaysPure =
            noClasses()
                    .that()
                    .resideInAPackage("com.pos.purchasing.domain.matching..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.springframework..",
                            "jakarta.persistence..",
                            "com.pos.purchasing.repository..",
                            "com.pos.purchasing.service..")
                    .because(
                            "whether a supplier gets paid is decided by comparing three documents,"
                                    + " and every shape of over-billing should be testable without a"
                                    + " database");

    @ArchTest
    static final ArchRule controllersDoNotReachPastTheServiceLayer =
            noClasses()
                    .that()
                    .resideInAPackage("com.pos.purchasing.api..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("com.pos.purchasing.repository..")
                    .because(
                            "a controller that reaches past the service layer skips its transaction"
                                    + " boundary, its status machine and its events");

    @ArchTest
    static final ArchRule eventsGoThroughTheOutbox =
            noClasses()
                    .that()
                    .resideOutsideOfPackage("com.pos.purchasing.messaging..")
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
                    .resideInAPackage("com.pos.purchasing.domain..")
                    .should()
                    .haveRawType(double.class)
                    .orShould()
                    .haveRawType(float.class)
                    .orShould()
                    .haveRawType(Double.class)
                    .orShould()
                    .haveRawType(Float.class)
                    .because(
                            "freight split across a dozen lines in floating point loses cents, and"
                                    + " the residual then has nowhere to go");

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
