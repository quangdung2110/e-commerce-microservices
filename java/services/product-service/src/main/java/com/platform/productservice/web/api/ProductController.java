package com.platform.productservice.web.api;

import com.platform.productservice.domain.entity.ProductEntity;
import com.platform.productservice.domain.enums.ProductStatus;
import com.platform.productservice.service.ProductCommandService;
import com.platform.productservice.service.ProductQueryService;
import com.platform.productservice.web.dto.ProductDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * Admin API cho Product (contracts/http/product/v1/openapi.yaml).
 *
 * <p>Mọi mutation sau khi tạo yêu cầu {@code If-Match} theo ETag
 * {@code catalog_revision}; sai revision trả 412 PRODUCT_REVISION_MISMATCH.
 * API này không nhận, trả hay suy luận bất kỳ dữ liệu tồn kho nào.
 */
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
@Validated
public class ProductController {

    private final ProductCommandService commandService;
    private final ProductQueryService queryService;

    @GetMapping
    public ProductDtos.ProductPageResponse list(
            @RequestParam(name = "category_id", required = false) UUID categoryId,
            @RequestParam(name = "status", required = false) ProductStatus status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", defaultValue = "50") @Min(1) @Max(100) int limit) {
        return queryService.list(categoryId, status, cursor, limit);
    }

    @PostMapping
    public ResponseEntity<ProductDtos.ProductResponse> create(
            @Valid @RequestBody ProductDtos.CreateProductRequest request) {
        ProductDtos.ProductResponse product = commandService.create(request);
        return ResponseEntity
                .created(URI.create("/api/v1/products/" + product.productId()))
                .eTag(ProductEntity.etagOf(product.catalogRevision()))
                .body(product);
    }

    @GetMapping("/{product_id}")
    public ResponseEntity<ProductDtos.ProductResponse> get(@PathVariable("product_id") UUID productId) {
        ProductDtos.ProductResponse product = queryService.get(productId);
        return ResponseEntity.ok()
                .eTag(ProductEntity.etagOf(product.catalogRevision()))
                .body(product);
    }

    @PutMapping("/{product_id}")
    public ResponseEntity<ProductDtos.ProductResponse> replace(
            @PathVariable("product_id") UUID productId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody ProductDtos.ReplaceProductRequest request) {
        ProductDtos.ProductResponse product = commandService.replace(productId, ifMatch, request);
        return ResponseEntity.ok()
                .eTag(ProductEntity.etagOf(product.catalogRevision()))
                .body(product);
    }

    @PatchMapping("/{product_id}/status")
    public ResponseEntity<ProductDtos.ProductResponse> changeStatus(
            @PathVariable("product_id") UUID productId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody ProductDtos.ChangeProductStatusRequest request) {
        ProductDtos.ProductResponse product = commandService.changeStatus(productId, ifMatch, request.status());
        return ResponseEntity.ok()
                .eTag(ProductEntity.etagOf(product.catalogRevision()))
                .body(product);
    }

    @DeleteMapping("/{product_id}")
    public ResponseEntity<Void> delete(@PathVariable("product_id") UUID productId,
                                       @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch) {
        commandService.delete(productId, ifMatch);
        return ResponseEntity.noContent().build();
    }
}
