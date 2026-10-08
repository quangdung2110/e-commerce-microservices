package com.platform.productservice.contract;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ngoại lệ có chủ đích của "service không copy .proto" (contracts/README.md): Docker build
 * context của Product chỉ gồm thư mục service nên {@code protobuf-maven-plugin} đọc
 * {@code proto/} cục bộ. Bản copy chỉ được phép là bản sao từng byte của nguồn chuẩn dưới
 * {@code contracts/}; test này là thứ giữ cho ngoại lệ không biến thành lệch contract.
 *
 * <p>Sửa proto thì sửa ở {@code contracts/} rồi chép lại; không bao giờ sửa trực tiếp bản copy.
 */
class ProtoCopyDriftTest {

    private static final Path SERVICE_ROOT = Paths.get("").toAbsolutePath();
    private static final Path CONTRACTS = SERVICE_ROOT.resolve("../../../contracts").normalize();

    @Test
    @DisplayName("product_service.proto trong service là bản sao của contracts/grpc")
    void productServiceProtoMatchesContract() throws IOException {
        assertCopyMatches(
                "proto/ecommerce/product/v1/product_service.proto",
                "grpc/ecommerce/product/v1/product_service.proto");
    }

    @Test
    @DisplayName("money.proto trong service là bản sao của contracts/shared/proto")
    void moneyProtoMatchesContract() throws IOException {
        assertCopyMatches(
                "proto/ecommerce/common/v1/money.proto",
                "shared/proto/ecommerce/common/v1/money.proto");
    }

    private void assertCopyMatches(String servicePath, String contractPath) throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(CONTRACTS),
                "contracts/ chỉ có mặt khi test chạy trong repository");

        Path copy = SERVICE_ROOT.resolve(servicePath);
        Path source = CONTRACTS.resolve(contractPath);
        assertThat(copy).as("bản copy %s", servicePath).isRegularFile();
        assertThat(source).as("nguồn chuẩn contracts/%s", contractPath).isRegularFile();

        assertThat(normalized(copy))
                .as("%s phải giống hệt contracts/%s; sửa ở contracts/ rồi chép lại", servicePath, contractPath)
                .isEqualTo(normalized(source));
    }

    /** Checkout trên Windows có thể mang CRLF; chỉ nội dung proto mới quan trọng. */
    private String normalized(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
