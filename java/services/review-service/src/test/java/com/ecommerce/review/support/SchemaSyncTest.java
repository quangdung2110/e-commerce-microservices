package com.ecommerce.review.support;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Flyway migration and the documented schema must never drift apart.
 *
 * <p>{@code docs/review/schema/0001_initial_review_schema.sql} is the executable source
 * of truth the design documents are written against, and {@code V1__init_review_schema.sql}
 * is what actually runs. Keeping them byte-identical is the only way "the docs describe
 * the deployed schema" stays a fact rather than an intention.
 */
class SchemaSyncTest {

    @Test
    void theMigrationIsTheDocumentedSchema() throws IOException {
        assertIdentical("V1__init_review_schema.sql", "0001_initial_review_schema.sql");
    }

    @Test
    void theProductSnapshotMigrationIsTheDocumentedSchema() throws IOException {
        assertIdentical("V2__eligibility_product_snapshot.sql", "0002_eligibility_product_snapshot.sql");
    }

    @Test
    void thePrivacySweepMigrationIsTheDocumentedSchema() throws IOException {
        assertIdentical("V3__privacy_sweep_includes_failed.sql", "0003_privacy_sweep_includes_failed.sql");
    }

    private static void assertIdentical(String migrationName, String documentedName) throws IOException {
        Path documented = ContractSchemas.repositoryRoot().resolve("docs/review/schema/" + documentedName);
        Path migration = Path.of("src/main/resources/db/migration/" + migrationName).toAbsolutePath();

        assertThat(documented).exists();
        assertThat(migration).exists();
        assertThat(Files.readAllBytes(migration))
                .as(migrationName + " must stay byte-identical to docs/review/schema/" + documentedName)
                .isEqualTo(Files.readAllBytes(documented));
    }
}
