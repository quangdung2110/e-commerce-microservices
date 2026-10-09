package com.ecommerce.review.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.networknt.schema.resource.PrefixSchemaMapper;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A real OpenAPI 3.1 document under {@code contracts/http}, usable as a test oracle.
 *
 * <p>The tests exercise the running service over HTTP and ask this class two questions:
 * does the contract declare this status for this operation, and does the body conform to
 * the schema the contract declares for it. Both answers come from the published file, so a
 * route, a status code or a field that drifts from it fails a test instead of waiting for
 * someone to compare the two by eye.
 *
 * <p>Component schemas are validated in place: the whole document is the schema resource,
 * so a {@code $ref} such as {@code #/components/schemas/Uuid} and the relative
 * {@code ../../../shared/json-schema/v1/problem.schema.json} both resolve exactly as they
 * do for a reader of the contract.
 */
public final class OpenApiContract {

    private static final String CONTRACT_BASE = "https://contracts.ecommerce.local/";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "patch");

    private final String documentUri;
    private final JsonNode root;
    private final JsonSchemaFactory factory;
    private final SchemaValidatorsConfig config;
    private final Map<String, JsonSchema> cache = new HashMap<>();

    private OpenApiContract(String relativePath, JsonNode root) {
        this.documentUri = CONTRACT_BASE + relativePath;
        this.root = root;
        String contractsUri = ContractSchemas.contractsDirectory().toUri().toString();
        this.factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012, builder ->
                builder.schemaMappers(mappers -> mappers.add(new PrefixSchemaMapper(CONTRACT_BASE, contractsUri))));
        this.config = new SchemaValidatorsConfig();
        this.config.setFormatAssertionsEnabled(true);
    }

    /** @param relativePath for example {@code http/review/v1/openapi.yaml} */
    public static OpenApiContract load(String relativePath) {
        Path file = ContractSchemas.contractsDirectory().resolve(relativePath);
        try (Reader reader = Files.newBufferedReader(file)) {
            Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
            return new OpenApiContract(relativePath, JSON.valueToTree(parsed));
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + file, e);
        }
    }

    /** Every operation as {@code "GET /api/v1/reviews/{}"}: parameter names are not compared. */
    public Set<String> operations() {
        Set<String> operations = new LinkedHashSet<>();
        root.path("paths").fields().forEachRemaining(path ->
                path.getValue().fieldNames().forEachRemaining(method -> {
                    if (METHODS.contains(method)) {
                        operations.add(method.toUpperCase() + " " + normalize(path.getKey()));
                    }
                }));
        return operations;
    }

    public static String normalize(String path) {
        return path.replaceAll("\\{[^}]+}", "{}");
    }

    /** Whether the operation lists {@code status} among its responses. */
    public boolean declares(String method, String path, int status) {
        return operation(method, path).path("responses").has(Integer.toString(status));
    }

    /**
     * Validates a response body against the schema the contract declares for this status,
     * or reports that the contract declares a body-less response and a body arrived.
     *
     * @return the violations; empty when the body conforms
     */
    public Set<String> violations(String method, String path, int status, JsonNode body) {
        JsonNode response = resolve(operation(method, path).path("responses").path(Integer.toString(status)));
        JsonNode content = response.path("content");
        if (content.isMissingNode() || content.isEmpty()) {
            return body == null || body.isMissingNode() || body.isNull()
                    ? Set.of()
                    : Set.of("the contract declares no body for " + method + " " + path + " -> " + status);
        }
        JsonNode media = content.has("application/json") ? content.get("application/json")
                : content.has("application/problem+json") ? content.get("application/problem+json")
                : content.elements().next();
        return validate(media.path("schema"), body);
    }

    /** Validates a request body against the schema the contract declares for the operation. */
    public Set<String> requestViolations(String method, String path, JsonNode body) {
        JsonNode schema = operation(method, path).path("requestBody").path("content")
                .path("application/json").path("schema");
        if (schema.isMissingNode()) {
            throw new IllegalArgumentException(method + " " + path + " declares no JSON request body");
        }
        return validate(schema, body);
    }

    /** The named component schema, for bodies that are not tied to one operation. */
    public Set<String> violationsOfSchema(String componentName, JsonNode body) {
        ObjectNode ref = JSON.createObjectNode();
        ref.put("$ref", "#/components/schemas/" + componentName);
        return validate(ref, body);
    }

    private Set<String> validate(JsonNode schemaNode, JsonNode body) {
        JsonSchema schema = cache.computeIfAbsent(schemaNode.toString(), key -> compile(schemaNode));
        Set<String> violations = new LinkedHashSet<>();
        for (ValidationMessage message : schema.validate(body)) {
            violations.add(message.getMessage());
        }
        return violations;
    }

    /**
     * The document itself becomes the schema resource, with the requested schema as its
     * only constraint. Everything else in the document is ignored by the validator.
     */
    private JsonSchema compile(JsonNode schemaNode) {
        ObjectNode wrapper = ((ObjectNode) root).deepCopy();
        wrapper.remove("paths");
        wrapper.put("$id", documentUri);
        wrapper.putArray("allOf").add(schemaNode);
        return factory.getSchema(wrapper, config);
    }

    private JsonNode operation(String method, String path) {
        JsonNode operation = root.path("paths").path(path).path(method.toLowerCase());
        if (operation.isMissingNode()) {
            throw new IllegalArgumentException("the contract has no operation " + method + " " + path);
        }
        return operation;
    }

    /** Follows a local {@code #/...} reference, as the contract uses for shared responses. */
    private JsonNode resolve(JsonNode node) {
        JsonNode current = node;
        while (current.has("$ref") && current.get("$ref").asText().startsWith("#/")) {
            current = root.at(current.get("$ref").asText().substring(1));
        }
        return current;
    }

    /** The first path of the document whose normalised form equals {@code normalised}. */
    public Optional<String> pathFor(String normalised) {
        Set<String> paths = new LinkedHashSet<>();
        root.path("paths").fieldNames().forEachRemaining(paths::add);
        return paths.stream().filter(path -> normalize(path).equals(normalised)).findFirst();
    }
}
