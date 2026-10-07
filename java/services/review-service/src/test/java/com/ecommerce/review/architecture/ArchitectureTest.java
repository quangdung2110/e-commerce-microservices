package com.ecommerce.review.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The architecture constraints that are cheap to state and expensive to discover late.
 */
class ArchitectureTest {

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.ecommerce.review");
    }

    /**
     * REVIEW-INV-007. Every outbound business event goes through the outbox, so that it
     * commits with the fact that made it true. The only Kafka producer Review owns
     * publishes dead letters, and it lives in the Kafka configuration.
     */
    @Test
    void noBusinessCodePublishesToKafkaDirectly() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(
                        "com.ecommerce.review.service..",
                        "com.ecommerce.review.controller..",
                        "com.ecommerce.review.repository..",
                        "com.ecommerce.review.scheduler..")
                .should().dependOnClassesThat().haveFullyQualifiedName(
                        "org.springframework.kafka.core.KafkaTemplate")
                .because("every outbound business event must leave through review_outbox and Debezium (ADR-003)");

        rule.check(productionClasses);
    }

    /** ADR-002: no cache in the read path, in any form. */
    @Test
    void noCacheSitsInFrontOfTheReadPath() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.ecommerce.review..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.data.redis..",
                        "org.springframework.cache..")
                .because("Review reads PostgreSQL directly; a cache layer needs a new ADR (ADR-002)");

        rule.check(productionClasses);
    }

    /**
     * Controllers state what the API does; they must not reach into SQL. Every command
     * boundary, and therefore every transaction boundary, belongs to a service.
     */
    @Test
    void controllersDoNotTouchRepositories() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.ecommerce.review.controller..")
                .should().dependOnClassesThat().resideInAPackage("com.ecommerce.review.repository..")
                .because("a command boundary is a service, not a controller");

        rule.check(productionClasses);
    }

    /**
     * No storage call may sit inside a domain transaction, so no repository may know
     * about object storage at all (ADR-011).
     */
    @Test
    void repositoriesDoNotCallObjectStorage() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.ecommerce.review.repository..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.ecommerce.review.storage..",
                        "software.amazon.awssdk..")
                .because("bytes are deleted after the transaction that recorded DELETE_PENDING commits");

        rule.check(productionClasses);
    }

    /**
     * The domain model is plain data. Letting persistence or web annotations in is how a
     * schema detail ends up in a wire contract by accident.
     */
    @Test
    void theDomainModelStaysFrameworkFree() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.ecommerce.review.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "com.fasterxml.jackson..")
                .because("domain records describe the business, not a table or a response body");

        rule.check(productionClasses);
    }
}
