package com.platform.productservice.web.api;

import com.platform.productservice.service.AttributeService;
import com.platform.productservice.web.dto.AttributeDtos;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * Attribute cấu hình dùng cho catalog. Attribute và option không phát event ở
 * v1; consumer nhận cấu hình qua Category snapshot.
 */
@RestController
@RequestMapping("/api/v1/attributes")
@RequiredArgsConstructor
public class AttributeController {

    private final AttributeService attributeService;

    @GetMapping
    public AttributeDtos.AttributeListResponse list() {
        return attributeService.list();
    }

    @PostMapping
    public ResponseEntity<AttributeDtos.AttributeResponse> create(
            @Valid @RequestBody AttributeDtos.CreateAttributeRequest request) {
        AttributeDtos.AttributeResponse attribute = attributeService.create(request);
        return ResponseEntity
                .created(URI.create("/api/v1/attributes/" + attribute.attributeId()))
                .body(attribute);
    }

    @GetMapping("/{attribute_id}")
    public AttributeDtos.AttributeResponse get(@PathVariable("attribute_id") UUID attributeId) {
        return attributeService.get(attributeId);
    }

    @PutMapping("/{attribute_id}")
    public AttributeDtos.AttributeResponse replace(
            @PathVariable("attribute_id") UUID attributeId,
            @Valid @RequestBody AttributeDtos.ReplaceAttributeRequest request) {
        return attributeService.replace(attributeId, request);
    }

    @DeleteMapping("/{attribute_id}")
    public ResponseEntity<Void> delete(@PathVariable("attribute_id") UUID attributeId) {
        attributeService.delete(attributeId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{attribute_id}/options")
    public ResponseEntity<AttributeDtos.AttributeOptionResponse> createOption(
            @PathVariable("attribute_id") UUID attributeId,
            @Valid @RequestBody AttributeDtos.CreateAttributeOptionRequest request) {
        AttributeDtos.AttributeOptionResponse option = attributeService.createOption(attributeId, request);
        return ResponseEntity
                .created(URI.create("/api/v1/attributes/" + attributeId + "/options/" + option.optionId()))
                .body(option);
    }

    @PutMapping("/{attribute_id}/options/{option_id}")
    public AttributeDtos.AttributeOptionResponse replaceOption(
            @PathVariable("attribute_id") UUID attributeId,
            @PathVariable("option_id") UUID optionId,
            @Valid @RequestBody AttributeDtos.ReplaceAttributeOptionRequest request) {
        return attributeService.replaceOption(attributeId, optionId, request);
    }

    @DeleteMapping("/{attribute_id}/options/{option_id}")
    public ResponseEntity<Void> deleteOption(@PathVariable("attribute_id") UUID attributeId,
                                             @PathVariable("option_id") UUID optionId) {
        attributeService.deleteOption(attributeId, optionId);
        return ResponseEntity.noContent().build();
    }
}
