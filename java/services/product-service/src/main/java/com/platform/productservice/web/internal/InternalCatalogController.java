package com.platform.productservice.web.internal;

import com.platform.productservice.config.ProductProperties;
import com.platform.productservice.domain.entity.ProductEntity;
import com.platform.productservice.service.CatalogExportService;
import com.platform.productservice.service.ProductQueryService;
import com.platform.productservice.web.dto.CategoryDtos;
import com.platform.productservice.web.dto.ProductDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * API nội bộ của Product. Không expose qua Gateway; caller được xác thực bằng
 * service identity (mTLS) trên mạng nội bộ, và quyền tách theo nhóm route trong
 * {@link com.platform.productservice.config.TrustedIdentityFilter}.
 *
 * <p>Nhóm reindex phục vụ Search full reindex. Hai endpoint export mở một export
 * session và trả kèm {@code replay_from_offsets}: Search import trọn vẹn snapshot
 * rồi seek inclusive từ các offset đó để bắt update/delete phát sinh trong lúc
 * export. Không chụp được đủ mọi partition thì trả {@code 503} và Search giữ
 * alias cũ (contracts/http/product/v1/openapi.yaml, ADR-014).
 *
 * <p>Nhóm buyer-read phục vụ Product Detail BFF và chỉ trả catalog đã công bố,
 * không có field tồn kho nào (ADR-015).
 */
@RestController
@RequestMapping("/internal/v1")
@RequiredArgsConstructor
public class InternalCatalogController {

    private final CatalogExportService exportService;
    private final ProductQueryService productQueryService;
    private final ProductProperties properties;

    @GetMapping("/products/export")
    public ProductDtos.ProductExportPageResponse exportProducts(
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return exportService.exportProducts(cursor, boundedLimit(limit));
    }

    @GetMapping("/categories/export")
    public CategoryDtos.CategoryExportPageResponse exportCategories(
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return exportService.exportCategories(cursor, boundedLimit(limit));
    }

    @GetMapping("/products/{product_id}/snapshot")
    public ResponseEntity<ProductDtos.ProductResponse> snapshot(
            @PathVariable("product_id") UUID productId) {
        ProductDtos.ProductResponse product = productQueryService.get(productId);
        return ResponseEntity.ok()
                .eTag(ProductEntity.etagOf(product.catalogRevision()))
                .body(product);
    }

    /**
     * Buyer-read theo slug cho Product Detail BFF (WF-PRODUCT-007). ETag dùng
     * chung {@code catalog_revision} của aggregate như snapshot, nên BFF có thể
     * conditional-GET mà không cần khái niệm version riêng.
     */
    @GetMapping("/product-details/{slug}")
    public ResponseEntity<ProductDtos.ProductResponse> buyerProductDetail(
            @PathVariable("slug") String slug) {
        ProductDtos.ProductResponse product = productQueryService.getBuyerDetail(slug);
        return ResponseEntity.ok()
                .eTag(ProductEntity.etagOf(product.catalogRevision()))
                .body(product);
    }

    private int boundedLimit(Integer limit) {
        ProductProperties.Export export = properties.getExport();
        if (limit == null) {
            return export.getDefaultLimit();
        }
        return Math.clamp(limit, 1, export.getMaxLimit());
    }
}
