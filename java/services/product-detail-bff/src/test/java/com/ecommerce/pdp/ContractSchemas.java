package com.ecommerce.pdp;

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
 * Validate JSON theo chính các OpenAPI trong {@code contracts/}: response của BFF theo
 * contract public, và fixture WireMock theo contract của provider tương ứng. {@code $ref}
 * tương đối (Problem trỏ sang shared schema) resolve theo vị trí file.
 */
public final class ContractSchemas {

    public static final String BFF = "http/product-detail-bff/v1/openapi.yaml";
    public static final String PRODUCT = "http/product/v1/openapi.yaml";
    public static final String INVENTORY = "http/inventory/read/v1/openapi.yaml";
    public static final String REVIEW = "http/review/internal/v1/openapi.yaml";

    private static final Path CONTRACTS = Paths.get("").toAbsolutePath().resolve("../../../contracts").normalize();

    private ContractSchemas() {
    }

    /** Component trong contract public của BFF. */
    public static Set<ValidationMessage> validate(String component, JsonNode body) {
        return validate(BFF, component, body);
    }

    public static Set<ValidationMessage> validate(String contract, String component, JsonNode body) {
        Path openapi = CONTRACTS.resolve(contract);
        Assumptions.assumeTrue(Files.isRegularFile(openapi), "contracts/ chỉ có mặt khi test chạy trong repository");
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(SchemaLocation.of(openapi.toUri() + "#/components/schemas/" + component));
        return schema.validate(body);
    }
}
