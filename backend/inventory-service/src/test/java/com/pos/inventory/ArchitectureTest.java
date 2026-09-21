package com.pos.inventory;

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
 * <p>The FEFO rule below is the one that matters most here: the allocator decides which physical
 * carton a sale comes out of, and keeping it free of Spring and JPA is what lets every expiry,
 * partial-batch and shortfall case be tested directly rather than through a database.
 */
@AnalyzeClasses(
        packages = "com.pos.inventory",
        importOptions = {ImportOption.DoNotIncludeTests.class})
class ArchitectureTest {

    @ArchTest
    static final ArchRule layersAreRespected =
            Architectures.layeredArchitecture()
                    .consideringOnlyDependenciesInLayers()
                    .layer("Api")
                    .definedBy("com.pos.inventory.api..")
                    .layer("Service")
                    .definedBy("com.pos.inventory.service..")
                    .layer("Messaging")
                    .definedBy("com.pos.inventory.messaging..")
                    .layer("Repository")
                    .definedBy("com.pos.inventory.repository..")
                    .layer("Domain")
                    .definedBy("com.pos.inventory.domain..")
                    .whereLayer("Api")
                    .mayNotBeAccessedByAnyLayer()
                    .whereLayer("Service")
                    .mayOnlyBeAccessedByLayers("Api", "Messaging")
                    .whereLayer("Repository")
                    .mayOnlyBeAccessedByLayers("Service");

    @ArchTest
    static final ArchRule theFefoAllocatorStaysPure =
            noClasses()
                    .that()
                    .resideInAPackage("com.pos.inventory.domain.fefo..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.springframework..",
                            "jakarta.persistence..",
                            "com.pos.inventory.repository..",
                            "com.pos.inventory.service..")
                    .because(
                            "which carton a sale comes out of is decided by arithmetic over expiry"
                                    + " dates; a dependency on Spring or JPA would put a database and a"
                                    + " context between the test and that decision");

    @ArchTest
    static final ArchRule controllersDoNotReachPastTheServiceLayer =
            noClasses()
                    .that()
                    .resideInAPackage("com.pos.inventory.api..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("com.pos.inventory.repository..")
                    .because(
                            "a controller that reaches past the service layer skips its transaction"
                                    + " boundary, its ledger entries and its events");

    @ArchTest
    static final ArchRule theLedgerIsOnlyWrittenThroughItsService =
            noClasses()
                    .that()
                    .resideOutsideOfPackages(
                            "com.pos.inventory.service..", "com.pos.inventory.repository..")
                    .should()
                    .accessClassesThat()
                    .haveFullyQualifiedName("com.pos.inventory.repository.StockMovementRepository")
                    .because(
                            "the movement ledger is the source of truth for every quantity, and"
                                    + " StockLedgerService is what keeps the derived on-hand cache in step"
                                    + " with it");

    @ArchTest
    static final ArchRule noFieldInjection =
            noFields()
                    .should()
                    .beAnnotatedWith(org.springframework.beans.factory.annotation.Autowired.class)
                    .because("constructor injection keeps dependencies visible and testable");

    @ArchTest
    static final ArchRule quantitiesAreNeverFloatingPointNumbers =
            noFields()
                    .that()
                    .areDeclaredInClassesThat()
                    .resideInAPackage("com.pos.inventory.domain..")
                    .should()
                    .haveRawType(double.class)
                    .orShould()
                    .haveRawType(float.class)
                    .orShould()
                    .haveRawType(Double.class)
                    .orShould()
                    .haveRawType(Float.class)
                    .because(
                            "weighed goods are fractional, and a stock count that drifts by a"
                                    + " rounding error is worse than no count at all");

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
