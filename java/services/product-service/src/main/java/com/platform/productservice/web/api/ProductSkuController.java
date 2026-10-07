package com.platform.productservice.web.api;

import com.platform.productservice.service.CommandResult;
import com.platform.productservice.service.SkuCommandService;
import com.platform.productservice.web.dto.ProductDtos;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * Quản lý SKU của một Product. Client không gửi selling price và không gửi dữ
 * liệu tồn kho; ETag trả về là revision mới của cả Product aggregate.
 */
@RestController
@RequestMapping("/api/v1/products/{product_id}/skus")
@RequiredArgsConstructor
public class ProductSkuController {

    private final SkuCommandService skuCommandService;

    @PostMapping
    public ResponseEntity<ProductDtos.SkuResponse> create(
            @PathVariable("product_id") UUID productId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody ProductDtos.CreateSkuRequest request) {
        CommandResult<ProductDtos.SkuResponse> result =
                skuCommandService.create(productId, ifMatch, request);
        return ResponseEntity
                .created(URI.create("/api/v1/products/" + productId + "/skus/" + result.body().skuId()))
                .eTag(result.etag())
                .body(result.body());
    }

    @PutMapping("/{sku_id}")
    public ResponseEntity<ProductDtos.SkuResponse> replace(
            @PathVariable("product_id") UUID productId,
            @PathVariable("sku_id") UUID skuId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody ProductDtos.ReplaceSkuRequest request) {
        CommandResult<ProductDtos.SkuResponse> result =
                skuCommandService.replace(productId, skuId, ifMatch, request);
        return ResponseEntity.ok().eTag(result.etag()).body(result.body());
    }

    /** Không xóa được SKU cuối cùng của Product; xóa thành công phát tombstone. */
    @DeleteMapping("/{sku_id}")
    public ResponseEntity<Void> delete(@PathVariable("product_id") UUID productId,
                                       @PathVariable("sku_id") UUID skuId,
                                       @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch) {
        String etag = skuCommandService.delete(productId, skuId, ifMatch);
        return ResponseEntity.noContent().eTag(etag).build();
    }
}
