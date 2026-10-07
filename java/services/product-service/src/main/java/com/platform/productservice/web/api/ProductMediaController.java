package com.platform.productservice.web.api;

import com.platform.productservice.service.CommandResult;
import com.platform.productservice.service.MediaCommandService;
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
 * Media cấp Product hoặc media gắn với một SKU thuộc Product.
 * {@code sku_id} null nghĩa là media cấp Product.
 */
@RestController
@RequestMapping("/api/v1/products/{product_id}/media")
@RequiredArgsConstructor
public class ProductMediaController {

    private final MediaCommandService mediaCommandService;

    @PostMapping
    public ResponseEntity<ProductDtos.MediaResponse> create(
            @PathVariable("product_id") UUID productId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody ProductDtos.CreateMediaRequest request) {
        CommandResult<ProductDtos.MediaResponse> result =
                mediaCommandService.create(productId, ifMatch, request);
        return ResponseEntity
                .created(URI.create("/api/v1/products/" + productId + "/media/" + result.body().mediaId()))
                .eTag(result.etag())
                .body(result.body());
    }

    @PutMapping("/{media_id}")
    public ResponseEntity<ProductDtos.MediaResponse> replace(
            @PathVariable("product_id") UUID productId,
            @PathVariable("media_id") UUID mediaId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody ProductDtos.ReplaceMediaRequest request) {
        CommandResult<ProductDtos.MediaResponse> result =
                mediaCommandService.replace(productId, mediaId, ifMatch, request);
        return ResponseEntity.ok().eTag(result.etag()).body(result.body());
    }

    @DeleteMapping("/{media_id}")
    public ResponseEntity<Void> delete(@PathVariable("product_id") UUID productId,
                                       @PathVariable("media_id") UUID mediaId,
                                       @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch) {
        String etag = mediaCommandService.delete(productId, mediaId, ifMatch);
        return ResponseEntity.noContent().eTag(etag).build();
    }
}
