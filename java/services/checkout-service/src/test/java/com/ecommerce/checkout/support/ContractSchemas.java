package com.ecommerce.checkout.support;

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
import java.util.Set;

/**
 * Validates JSON against the OpenAPI documents in {@code contracts/}: Checkout's own responses against
 * the public contract, and every WireMock fixture against the contract of the provider it imitates, so a
 * test cannot pass by teaching Checkout about a Cart or Order that does not exist. Relative {@code $ref}s
 * (Problem points at a shared schema) resolve by file location.
 */
public final class ContractSchemas {

    public static final String CHECKOUT = "http/checkout/v1/openapi.yaml";
    public static final String CART = "http/cart/v1/openapi.yaml";
    public static final String USER = "http/user/v1/openapi.yaml";
    public static final String INVENTORY = "http/inventory/workflow/v1/openapi.yaml";
    public static final String PROMOTION = "http/promotion/v1/openapi.yaml";
    public static final String ORDER = "http/order/internal/v1/openapi.yaml";

    private static final Path CONTRACTS = Paths.get("").toAbsolutePath().resolve("../../../contracts").normalize();

    private ContractSchemas() {
    }

    /** A component of Checkout's public contract. */
    public static Set<ValidationMessage> validate(String component, JsonNode body) {
        return validate(CHECKOUT, component, body);
    }

    public static Set<ValidationMessage> validate(String contract, String component, JsonNode body) {
        Path openapi = CONTRACTS.resolve(contract);
        Assumptions.assumeTrue(Files.isRegularFile(openapi), "contracts/ is only present inside the repository");
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(SchemaLocation.of(openapi.toUri() + "#/components/schemas/" + component));
        return schema.validate(body);
    }
}
