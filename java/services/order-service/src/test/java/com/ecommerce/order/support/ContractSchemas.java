package com.ecommerce.order.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Assumptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provider contract check: validates a response/event against a schema of the published
 * OpenAPI 3.1 / JSON Schema documents under {@code contracts/} (JSON Schema 2020-12).
 */
public final class ContractSchemas {

    public static final String PUBLIC = "http/order/v1/openapi.yaml";
    public static final String INTERNAL = "http/order/internal/v1/openapi.yaml";

    private static final String PREFIX = "https://contracts.ecommerce.local/";
    private static final Map<String, JsonSchema> CACHE = new ConcurrentHashMap<>();
    private static JsonSchemaFactory factory;

    private ContractSchemas() {
    }

    private static synchronized JsonSchemaFactory factory() {
        if (factory == null) {
            Path contracts = Paths.get("..", "..", "..", "contracts").toAbsolutePath().normalize();
            Assumptions.assumeTrue(Files.isDirectory(contracts), "contracts/ is present only inside the repository");
            factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012, builder ->
                    builder.schemaMappers(m -> m.mapPrefix(PREFIX, contracts.toUri().toString())));
        }
        return factory;
    }

    /** Asserts {@code body} matches {@code #/components/schemas/{name}} of an OpenAPI document. */
    public static void assertComponent(String document, String name, JsonNode body) {
        assertThat(errors(document, name, body)).as(name + " " + body).isEmpty();
    }

    public static Set<ValidationMessage> errors(String document, String name, JsonNode body) {
        JsonSchema schema = CACHE.computeIfAbsent(document + "#" + name, key -> factory().getSchema(
                SchemaLocation.of(PREFIX + document + "#/components/schemas/" + name)));
        return schema.validate(body);
    }
}
