package com.ecommerce.review.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.networknt.schema.resource.PrefixSchemaMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Loads the real JSON Schemas from {@code contracts/} and validates envelopes against
 * them.
 *
 * <p>The schemas declare absolute {@code $id}s under
 * {@code https://contracts.ecommerce.local/}, and their {@code $ref}s resolve against
 * that base. Mapping the prefix onto the repository directory is what lets the tests use
 * the published contracts unchanged instead of a hand-copied duplicate that could drift.
 */
public final class ContractSchemas {

    private static final String CONTRACT_BASE = "https://contracts.ecommerce.local/";
    private static final String REVIEW_SCHEMA_DIR = "events/review/v1/schemas/";

    private static final Map<String, JsonSchema> CACHE = new HashMap<>();

    private ContractSchemas() {
    }

    /** The repository {@code contracts/} directory, found from the module directory. */
    public static Path contractsDirectory() {
        Path candidate = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 8 && candidate != null; depth++) {
            Path contracts = candidate.resolve("contracts");
            if (Files.isDirectory(contracts.resolve("events").resolve("review"))) {
                return contracts;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("could not locate the repository contracts directory");
    }

    /** The repository root, used by the schema-drift test. */
    public static Path repositoryRoot() {
        return contractsDirectory().getParent();
    }

    public static Set<ValidationMessage> validate(String schemaFileName, JsonNode envelope) {
        return CACHE.computeIfAbsent(schemaFileName, ContractSchemas::load).validate(envelope);
    }

    private static JsonSchema load(String schemaFileName) {
        String contractsUri = contractsDirectory().toUri().toString();
        JsonSchemaFactory factory = JsonSchemaFactory
                .getInstance(SpecVersion.VersionFlag.V202012, builder ->
                        builder.schemaMappers(mappers ->
                                mappers.add(new PrefixSchemaMapper(CONTRACT_BASE, contractsUri))));
        return factory.getSchema(SchemaLocation.of(CONTRACT_BASE + REVIEW_SCHEMA_DIR + schemaFileName));
    }
}
