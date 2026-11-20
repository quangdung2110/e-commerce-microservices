package com.platform.productservice.service;

import com.platform.productservice.common.DomainException;
import com.platform.productservice.common.ErrorCode;
import com.platform.productservice.common.Uuids;
import com.platform.productservice.domain.entity.AttributeEntity;
import com.platform.productservice.domain.entity.AttributeOptionEntity;
import com.platform.productservice.domain.enums.AttributeValueType;
import com.platform.productservice.infrastructure.persistence.AttributeOptionRepository;
import com.platform.productservice.infrastructure.persistence.AttributeRepository;
import com.platform.productservice.infrastructure.persistence.CategoryAttributeRepository;
import com.platform.productservice.infrastructure.persistence.ProductRepository;
import com.platform.productservice.infrastructure.persistence.SkuRepository;
import com.platform.productservice.service.support.CatalogAssembler;
import com.platform.productservice.web.dto.AttributeDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Attribute cấu hình và option.
 *
 * <p>Attribute/option không có event riêng ở v1 (docs/product/interfaces.md). Thay đổi
 * chỉ đi vào Category event: mapping category–attribute, và đổi tên attribute đã được
 * map vì tên đó nằm trong {@code facet_definitions} (ADR-019). Option không nằm trong
 * descriptor nên tạo/sửa/xóa option không phát event.
 */
@Service
@RequiredArgsConstructor
public class AttributeService {

    private final AttributeRepository attributeRepository;
    private final AttributeOptionRepository optionRepository;
    private final CategoryAttributeRepository categoryAttributeRepository;
    private final ProductRepository productRepository;
    private final SkuRepository skuRepository;
    private final CategoryCommandService categoryCommandService;
    private final CatalogAssembler assembler;

    @Transactional(readOnly = true)
    public AttributeDtos.AttributeListResponse list() {
        List<AttributeEntity> attributes = attributeRepository.findAllByOrderByCodeAsc();
        return new AttributeDtos.AttributeListResponse(
                attributes.stream()
                        .map(attribute -> assembler.toAttribute(attribute, optionsOf(attribute.getAttributeId())))
                        .toList());
    }

    @Transactional(readOnly = true)
    public AttributeDtos.AttributeResponse get(UUID attributeId) {
        AttributeEntity attribute = require(attributeId);
        return assembler.toAttribute(attribute, optionsOf(attributeId));
    }

    @Transactional
    public AttributeDtos.AttributeResponse create(AttributeDtos.CreateAttributeRequest request) {
        Instant now = Instant.now();
        if (attributeRepository.existsByCode(request.code())) {
            throw DomainException.of(ErrorCode.ATTRIBUTE_CODE_ALREADY_EXISTS,
                    "Attribute code " + request.code() + " is already used");
        }
        AttributeEntity attribute = new AttributeEntity();
        attribute.setAttributeId(Uuids.next());
        attribute.setCode(request.code());
        attribute.setName(request.name());
        attribute.setValueType(request.valueType());
        attribute.setCreatedAt(now);
        attribute.setUpdatedAt(now);
        attributeRepository.save(attribute);
        return assembler.toAttribute(attribute, List.of());
    }

    /**
     * Không đổi được {@code code} hay {@code value_type} khi dữ liệu hiện hữu sẽ
     * thành không hợp lệ; server trả {@code ATTRIBUTE_IN_USE}.
     */
    @Transactional
    public AttributeDtos.AttributeResponse replace(UUID attributeId,
                                                   AttributeDtos.ReplaceAttributeRequest request) {
        Instant now = Instant.now();
        AttributeEntity attribute = require(attributeId);
        if (attributeRepository.existsByCodeAndAttributeIdNot(request.code(), attributeId)) {
            throw DomainException.of(ErrorCode.ATTRIBUTE_CODE_ALREADY_EXISTS,
                    "Attribute code " + request.code() + " is already used");
        }

        boolean codeChanged = !attribute.getCode().equals(request.code());
        boolean typeChanged = attribute.getValueType() != request.valueType();
        if ((codeChanged || typeChanged) && isInUse(attribute)) {
            throw DomainException.of(ErrorCode.ATTRIBUTE_IN_USE,
                    "Attribute " + attribute.getCode() + " is mapped to a category or used by catalog data");
        }
        if (typeChanged && request.valueType() != AttributeValueType.SELECT
                && optionRepository.existsByAttributeId(attributeId)) {
            throw DomainException.of(ErrorCode.ATTRIBUTE_IN_USE,
                    "Attribute " + attribute.getCode() + " still has SELECT options");
        }

        boolean nameChanged = !attribute.getName().equals(request.name());

        attribute.setCode(request.code());
        attribute.setName(request.name());
        attribute.setValueType(request.valueType());
        attribute.setUpdatedAt(now);
        if (nameChanged || codeChanged || typeChanged) {
            // Code/type chỉ đổi được khi attribute chưa được map; đổi tên thì mọi category
            // đang map nó phải phát lại snapshot vì facet_definitions mang tên này (ADR-019).
            categoryCommandService.republishForAttribute(attributeId);
        }
        return assembler.toAttribute(attribute, optionsOf(attributeId));
    }

    @Transactional
    public void delete(UUID attributeId) {
        AttributeEntity attribute = require(attributeId);
        if (isInUse(attribute) || optionRepository.existsByAttributeId(attributeId)) {
            throw DomainException.of(ErrorCode.ATTRIBUTE_IN_USE,
                    "Attribute " + attribute.getCode() + " is still referenced");
        }
        attributeRepository.delete(attribute);
    }

    @Transactional
    public AttributeDtos.AttributeOptionResponse createOption(
            UUID attributeId, AttributeDtos.CreateAttributeOptionRequest request) {
        Instant now = Instant.now();
        AttributeEntity attribute = requireSelect(attributeId);
        if (optionRepository.existsByAttributeIdAndCode(attributeId, request.code())) {
            throw DomainException.of(ErrorCode.ATTRIBUTE_OPTION_CODE_ALREADY_EXISTS,
                    "Option code " + request.code() + " already exists for attribute " + attribute.getCode());
        }
        AttributeOptionEntity option = new AttributeOptionEntity();
        option.setOptionId(Uuids.next());
        option.setAttributeId(attributeId);
        option.setCode(request.code());
        option.setLabel(request.label());
        option.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        option.setCreatedAt(now);
        option.setUpdatedAt(now);
        optionRepository.save(option);
        return assembler.toAttributeOption(option);
    }

    @Transactional
    public AttributeDtos.AttributeOptionResponse replaceOption(
            UUID attributeId, UUID optionId, AttributeDtos.ReplaceAttributeOptionRequest request) {
        Instant now = Instant.now();
        AttributeEntity attribute = requireSelect(attributeId);
        AttributeOptionEntity option = requireOption(attributeId, optionId);
        if (optionRepository.existsByAttributeIdAndCodeAndOptionIdNot(attributeId, request.code(), optionId)) {
            throw DomainException.of(ErrorCode.ATTRIBUTE_OPTION_CODE_ALREADY_EXISTS,
                    "Option code " + request.code() + " already exists for attribute " + attribute.getCode());
        }
        if (!option.getCode().equals(request.code()) && isOptionInUse(attribute, option)) {
            throw DomainException.of(ErrorCode.ATTRIBUTE_IN_USE,
                    "Option " + option.getCode() + " is still referenced by catalog data");
        }
        option.setCode(request.code());
        option.setLabel(request.label());
        option.setSortOrder(request.sortOrder());
        option.setUpdatedAt(now);
        return assembler.toAttributeOption(option);
    }

    @Transactional
    public void deleteOption(UUID attributeId, UUID optionId) {
        AttributeEntity attribute = require(attributeId);
        AttributeOptionEntity option = requireOption(attributeId, optionId);
        if (isOptionInUse(attribute, option)) {
            throw DomainException.of(ErrorCode.ATTRIBUTE_IN_USE,
                    "Option " + option.getCode() + " is still referenced by catalog data");
        }
        optionRepository.delete(option);
    }

    private boolean isInUse(AttributeEntity attribute) {
        return categoryAttributeRepository.existsByIdAttributeId(attribute.getAttributeId())
                || productRepository.countUsingAttribute(attribute.getCode()) > 0
                || skuRepository.countUsingAttribute(attribute.getCode()) > 0;
    }

    private boolean isOptionInUse(AttributeEntity attribute, AttributeOptionEntity option) {
        return productRepository.countUsingAttributeValue(attribute.getCode(), option.getCode()) > 0
                || skuRepository.countUsingAttributeValue(attribute.getCode(), option.getCode()) > 0;
    }

    private List<AttributeOptionEntity> optionsOf(UUID attributeId) {
        return optionRepository.findByAttributeIdOrderBySortOrderAscCodeAsc(attributeId);
    }

    private AttributeEntity require(UUID attributeId) {
        return attributeRepository.findById(attributeId)
                .orElseThrow(() -> DomainException.of(ErrorCode.ATTRIBUTE_NOT_FOUND,
                        "Attribute " + attributeId + " does not exist"));
    }

    private AttributeEntity requireSelect(UUID attributeId) {
        AttributeEntity attribute = require(attributeId);
        if (attribute.getValueType() != AttributeValueType.SELECT) {
            throw DomainException.of(ErrorCode.INVALID_REQUEST,
                    "Attribute " + attribute.getCode() + " is not a SELECT attribute");
        }
        return attribute;
    }

    private AttributeOptionEntity requireOption(UUID attributeId, UUID optionId) {
        return optionRepository.findById(optionId)
                .filter(option -> option.getAttributeId().equals(attributeId))
                .orElseThrow(() -> DomainException.of(ErrorCode.ATTRIBUTE_OPTION_NOT_FOUND,
                        "Option " + optionId + " does not belong to attribute " + attributeId));
    }
}
