package com.platform.productservice.contract;

import com.platform.productservice.infrastructure.grpc.ProductGrpcError;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * contracts ADR-006: the reasons Product can return are exactly the ones listed in
 * {@code contracts/grpc/ecommerce/product/v1/errors.yaml}, with the same status and retryability.
 */
class ProductGrpcErrorCatalogTest {

    private static final Path CATALOG = Paths.get("").toAbsolutePath()
            .resolve("../../../contracts/grpc/ecommerce/product/v1/errors.yaml").normalize();

    @Test
    @DisplayName("enum ProductGrpcError khớp errors.yaml: reason, status, retryable")
    @SuppressWarnings("unchecked")
    void enumMatchesTheCatalog() throws IOException {
        Assumptions.assumeTrue(Files.isRegularFile(CATALOG), "contracts/ chỉ có mặt khi test chạy trong repository");

        Map<String, Object> doc;
        try (Reader reader = Files.newBufferedReader(CATALOG, StandardCharsets.UTF_8)) {
            doc = new Yaml().load(reader);
        }
        assertThat(doc.get("domain")).isEqualTo(ProductGrpcError.DOMAIN);

        Map<String, String> declared = new LinkedHashMap<>();
        for (Map<String, Object> entry : (List<Map<String, Object>>) doc.get("errors")) {
            declared.put((String) entry.get("reason"), entry.get("status") + "/" + entry.get("retryable"));
        }
        Map<String, String> implemented = new LinkedHashMap<>();
        for (ProductGrpcError error : ProductGrpcError.values()) {
            implemented.put(error.name(), error.status().name() + "/" + error.retryable());
        }

        assertThat(implemented).isEqualTo(declared);
    }
}
