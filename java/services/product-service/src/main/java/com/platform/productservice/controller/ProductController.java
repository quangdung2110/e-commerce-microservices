package com.platform.productservice.controller;

import com.platform.productservice.domain.entity.Product;
import com.platform.productservice.domain.entity.Sku;
import com.platform.productservice.dto.mapper.ProductMapper;
import com.platform.productservice.dto.mapper.SkuMapper;
import com.platform.productservice.dto.request.ChangeStatusRequest;
import com.platform.productservice.dto.request.CreateProductRequest;
import com.platform.productservice.dto.request.CreateSkuRequest;
import com.platform.productservice.dto.request.ProductSearchRequest;
import com.platform.productservice.dto.request.UpdateProductRequest;
import com.platform.productservice.dto.request.UpdateSkuRequest;
import com.platform.productservice.dto.response.PagedResponse;
import com.platform.productservice.dto.response.ProductResponse;
import com.platform.productservice.dto.response.SkuResponse;
import com.platform.productservice.port.inbound.ProductServicePort;
import com.platform.productservice.port.inbound.SkuServicePort;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Admin REST controller for product and SKU management.
 * All endpoints are prefixed with /api/v1/products.
 */
@RestController
@RequestMapping("/api/v1/products")
@Validated
public class ProductController {

    private static final Logger log = LoggerFactory.getLogger(ProductController.class);

    private final ProductServicePort productService;
    private final SkuServicePort skuService;
    private final ProductMapper productMapper;
    private final SkuMapper skuMapper;

    public ProductController(
            ProductServicePort productService,
            SkuServicePort skuService,
            ProductMapper productMapper,
            SkuMapper skuMapper) {
        this.productService = productService;
        this.skuService = skuService;
        this.productMapper = productMapper;
        this.skuMapper = skuMapper;
    }

    @PostMapping
    public ResponseEntity<ProductResponse> createProduct(
            @Valid @RequestBody CreateProductRequest request,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {

        log.info("REST: createProduct - name={}, categoryId={}, idempotencyKey={}",
                request.name(), request.categoryId(), idempotencyKey);

        Product product = productMapper.toDomain(request);
        Product created = productService.create(product);
        ProductResponse response = productMapper.toResponse(created);

        log.info("REST: createProduct - created productId={}", created.getId().getValue());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> getProduct(@PathVariable String id) {
        log.info("REST: getProduct - productId={}", id);

        Product product = productService.findById(id);
        ProductResponse response = productMapper.toResponse(product);

        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<PagedResponse<ProductResponse>> searchProducts(
            @Valid ProductSearchRequest request) {

        log.info("REST: searchProducts - categoryId={}, status={}, search={}, page={}, size={}",
                request.categoryId(), request.status(), request.search(),
                request.page(), request.size());

        ProductServicePort.ProductSearchCriteria criteria = new ProductServicePort.ProductSearchCriteria(
                request.categoryId(),
                request.status(),
                request.search(),
                request.page() - 1,
                request.size(),
                request.sort()
        );

        Page<Product> page = productService.search(criteria);
        List<ProductResponse> items = page.getContent().stream()
                .map(productMapper::toSummaryResponse)
                .collect(Collectors.toList());

        PagedResponse<ProductResponse> response = PagedResponse.of(
                items,
                request.page(),
                request.size(),
                page.getTotalElements()
        );

        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProductResponse> updateProduct(
            @PathVariable String id,
            @Valid @RequestBody UpdateProductRequest request,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {

        log.info("REST: updateProduct - productId={}, idempotencyKey={}", id, idempotencyKey);

        Product product = productService.findById(id);
        productMapper.toDomainUpdate(request, product);
        Product updated = productService.update(id, product);
        ProductResponse response = productMapper.toResponse(updated);

        log.info("REST: updateProduct - updated productId={}", id);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProduct(@PathVariable String id) {
        log.info("REST: deleteProduct - productId={}", id);

        productService.delete(id);

        log.info("REST: deleteProduct - deleted productId={}", id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<ProductResponse> changeStatus(
            @PathVariable String id,
            @Valid @RequestBody ChangeStatusRequest request) {

        log.info("REST: changeStatus - productId={}, command={}", id, request.command());

        Product product = productService.findById(id);
        Product result;

        switch (request.command().toUpperCase()) {
            case "SUBMIT" -> result = productService.submit(id);
            case "HIDE" -> result = productService.hide(id);
            case "SHOW" -> result = productService.show(id);
            default -> throw new IllegalArgumentException("Invalid command: " + request.command());
        }

        ProductResponse response = productMapper.toResponse(result);

        log.info("REST: changeStatus - productId={}, newStatus={}", id, result.getStatus());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/skus")
    public ResponseEntity<SkuResponse> addSku(
            @PathVariable String id,
            @Valid @RequestBody CreateSkuRequest request,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {

        log.info("REST: addSku - productId={}, skuCode={}, idempotencyKey={}",
                id, request.skuCode(), idempotencyKey);

        Sku sku = skuMapper.toDomain(request, id);
        Sku created = skuService.createSku(id, sku);
        SkuResponse response = skuMapper.toResponse(created);

        log.info("REST: addSku - created skuId={} for productId={}", created.getId().getValue(), id);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}/skus/{skuId}")
    public ResponseEntity<SkuResponse> updateSku(
            @PathVariable String id,
            @PathVariable String skuId,
            @Valid @RequestBody UpdateSkuRequest request,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {

        log.info("REST: updateSku - productId={}, skuId={}, idempotencyKey={}", id, skuId, idempotencyKey);

        Sku existingSku = skuService.findById(skuId);
        skuMapper.toDomainUpdate(request, existingSku);
        Sku updated = skuService.updateSku(id, skuId, existingSku);
        SkuResponse response = skuMapper.toResponse(updated);

        log.info("REST: updateSku - updated skuId={}", skuId);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}/skus/{skuId}")
    public ResponseEntity<Void> deleteSku(
            @PathVariable String id,
            @PathVariable String skuId) {

        log.info("REST: deleteSku - productId={}, skuId={}", id, skuId);

        skuService.deleteSku(id, skuId);

        log.info("REST: deleteSku - deleted skuId={}", skuId);
        return ResponseEntity.noContent().build();
    }
}
