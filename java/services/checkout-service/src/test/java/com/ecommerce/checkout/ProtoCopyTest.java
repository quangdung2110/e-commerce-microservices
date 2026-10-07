package com.ecommerce.checkout;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The module keeps a copy of the Product proto so its Docker build context is the service directory. A copy
 * that drifts from {@code contracts/} is how Product once stopped serving {@code category_ids} unnoticed:
 * the copy it compiled against had no such field. This test fails the build on any difference.
 */
class ProtoCopyTest {

    private static final Path MODULE = Paths.get("").toAbsolutePath();
    private static final Path CONTRACTS = MODULE.resolve("../../../contracts").normalize();

    @Test
    @DisplayName("proto/ equals the canonical Product and Money protos in contracts/")
    void protoCopiesEqualTheCanonicalContracts() throws IOException {
        assertSame("proto/ecommerce/product/v1/product_service.proto",
                "grpc/ecommerce/product/v1/product_service.proto");
        assertSame("proto/ecommerce/common/v1/money.proto", "shared/proto/ecommerce/common/v1/money.proto");
    }

    private static void assertSame(String copy, String canonical) throws IOException {
        Path canonicalFile = CONTRACTS.resolve(canonical);
        Assumptions.assumeTrue(Files.isRegularFile(canonicalFile), "contracts/ is only present inside the repository");
        assertThat(normalized(MODULE.resolve(copy))).as(copy + " must equal contracts/" + canonical)
                .isEqualTo(normalized(canonicalFile));
    }

    private static String normalized(Path file) throws IOException {
        return Files.readString(file).replace("\r\n", "\n");
    }
}
