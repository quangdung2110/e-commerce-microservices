package com.ecommerce.checkout.problem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The error catalog exists in three places: the OpenAPI enum, the table in docs/checkout/interfaces.md and
 * {@link ProblemCode}. They must be the same set with the same status, retryable flag and action, so a
 * documentation or contract edit that is not followed by code (or the reverse) fails the build.
 */
class ProblemCatalogContractTest {

    private static final Path REPO = Paths.get("").toAbsolutePath().resolve("../../..").normalize();
    private static final Pattern ROW = Pattern.compile(
            "^\\| `([A-Z_]+)` \\| ([0-9]{3})(?: Preview / ([0-9]{3}) Submit)? \\| (true|false) \\| `([A-Z_]+)` \\|.*");

    @Test
    @DisplayName("ProblemCode equals the OpenAPI CheckoutProblemCode enum")
    void matchesOpenApiEnum() throws IOException {
        Path openapi = REPO.resolve("contracts/http/checkout/v1/openapi.yaml");
        Assumptions.assumeTrue(Files.isRegularFile(openapi), "contracts/ is only present inside the repository");
        JsonNode enumValues = new YAMLMapper().readTree(openapi.toFile())
                .path("components").path("schemas").path("CheckoutProblemCode").path("enum");
        Set<String> contract = new TreeSet<>();
        enumValues.forEach(value -> contract.add(value.asText()));

        Set<String> code = new TreeSet<>();
        Arrays.stream(ProblemCode.values()).forEach(value -> code.add(value.name()));

        assertThat(code).isEqualTo(contract);

        JsonNode actions = new YAMLMapper().readTree(openapi.toFile())
                .path("components").path("schemas").path("ProblemAction").path("enum");
        Set<String> contractActions = new TreeSet<>();
        actions.forEach(value -> contractActions.add(value.asText()));
        Set<String> codeActions = new TreeSet<>();
        Arrays.stream(ProblemCode.Action.values()).forEach(value -> codeActions.add(value.name()));
        assertThat(codeActions).isEqualTo(contractActions);
    }

    @Test
    @DisplayName("ProblemCode equals the error catalog table of docs/checkout/interfaces.md (status, retryable, action)")
    void matchesInterfacesTable() throws IOException {
        Path doc = REPO.resolve("docs/checkout/interfaces.md");
        Assumptions.assumeTrue(Files.isRegularFile(doc), "docs/ is only present inside the repository");
        List<String> rows = new ArrayList<>();
        for (String line : Files.readAllLines(doc)) {
            Matcher matcher = ROW.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            rows.add(matcher.group(1));
            ProblemCode code = ProblemCode.valueOf(matcher.group(1));
            assertThat(code.status()).as(code + " status").isEqualTo(Integer.parseInt(matcher.group(2)));
            assertThat(code.retryable()).as(code + " retryable").isEqualTo(Boolean.parseBoolean(matcher.group(4)));
            assertThat(code.action().name()).as(code + " action").isEqualTo(matcher.group(5));
        }

        assertThat(rows).doesNotHaveDuplicates();
        assertThat(rows).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(ProblemCode.values()).map(Enum::name).toList());
    }
}
