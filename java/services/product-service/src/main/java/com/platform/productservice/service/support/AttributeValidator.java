package com.platform.productservice.service.support;

import com.platform.productservice.common.DomainException;
import com.platform.productservice.common.ErrorCode;
import com.platform.productservice.domain.entity.AttributeEntity;
import com.platform.productservice.domain.entity.AttributeOptionEntity;
import com.platform.productservice.domain.entity.CategoryAttributeEntity;
import com.platform.productservice.domain.enums.AttributeValueType;
import com.platform.productservice.infrastructure.persistence.AttributeOptionRepository;
import com.platform.productservice.infrastructure.persistence.AttributeRepository;
import com.platform.productservice.infrastructure.persistence.CategoryAttributeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Kiểm tra giá trị attribute của Product và variant attribute của SKU theo
 * mapping category–attribute.
 *
 * <p>Mapping {@code is_variant=false} thuộc {@code attributes} của Product,
 * {@code is_variant=true} thuộc {@code variant_attributes} của SKU
 * (contracts/http/product/v1/openapi.yaml — AttributeValues).
 */
@Component
@RequiredArgsConstructor
public class AttributeValidator {

    private final CategoryAttributeRepository categoryAttributeRepository;
    private final AttributeRepository attributeRepository;
    private final AttributeOptionRepository attributeOptionRepository;

    /** Mapping của một category, đã nạp sẵn option để validate nhiều SKU. */
    public record CategorySchema(Map<String, MappedAttribute> byCode) {

        public List<MappedAttribute> required(boolean variant) {
            return byCode.values().stream()
                    .filter(attribute -> attribute.variant() == variant && attribute.required())
                    .toList();
        }
    }

    public record MappedAttribute(UUID attributeId,
                                  String code,
                                  AttributeValueType valueType,
                                  boolean required,
                                  boolean variant,
                                  Set<String> optionCodes) {
    }

    public CategorySchema schemaOf(UUID categoryId) {
        List<CategoryAttributeEntity> mappings = categoryAttributeRepository.findByIdCategoryId(categoryId);
        if (mappings.isEmpty()) {
            return new CategorySchema(Map.of());
        }
        List<UUID> attributeIds = mappings.stream()
                .map(mapping -> mapping.getId().getAttributeId())
                .toList();
        Map<UUID, AttributeEntity> attributes = attributeRepository.findByAttributeIdIn(attributeIds).stream()
                .collect(Collectors.toMap(AttributeEntity::getAttributeId, attribute -> attribute));
        Map<UUID, Set<String>> options = new HashMap<>();
        for (AttributeOptionEntity option : attributeOptionRepository
                .findByAttributeIdInOrderBySortOrderAscCodeAsc(attributeIds)) {
            options.computeIfAbsent(option.getAttributeId(), key -> new LinkedHashSet<>()).add(option.getCode());
        }

        Map<String, MappedAttribute> byCode = new HashMap<>();
        for (CategoryAttributeEntity mapping : mappings) {
            AttributeEntity attribute = attributes.get(mapping.getId().getAttributeId());
            if (attribute == null) {
                continue;
            }
            byCode.put(attribute.getCode(), new MappedAttribute(
                    attribute.getAttributeId(),
                    attribute.getCode(),
                    attribute.getValueType(),
                    mapping.isRequired(),
                    mapping.isVariant(),
                    options.getOrDefault(attribute.getAttributeId(), Set.of())));
        }
        return new CategorySchema(Map.copyOf(byCode));
    }

    public void validateProductAttributes(CategorySchema schema, Map<String, Object> attributes) {
        validate(schema, attributes, false, "attributes");
    }

    public void validateVariantAttributes(CategorySchema schema, Map<String, Object> attributes) {
        validate(schema, attributes, true, "variant_attributes");
    }

    private void validate(CategorySchema schema,
                          Map<String, Object> values,
                          boolean variant,
                          String field) {
        Map<String, Object> given = values == null ? Map.of() : values;
        List<DomainException.FieldError> errors = new ArrayList<>();

        for (Map.Entry<String, Object> entry : given.entrySet()) {
            MappedAttribute attribute = schema.byCode().get(entry.getKey());
            if (attribute == null) {
                errors.add(new DomainException.FieldError(field + "." + entry.getKey(),
                        "attribute is not mapped to the product category"));
                continue;
            }
            if (attribute.variant() != variant) {
                errors.add(new DomainException.FieldError(field + "." + entry.getKey(),
                        variant ? "attribute is a product-level attribute"
                                : "attribute is a variant attribute"));
                continue;
            }
            String typeError = checkValue(attribute, entry.getValue());
            if (typeError != null) {
                errors.add(new DomainException.FieldError(field + "." + entry.getKey(), typeError));
            }
        }

        for (MappedAttribute attribute : schema.required(variant)) {
            if (!given.containsKey(attribute.code()) || given.get(attribute.code()) == null) {
                errors.add(new DomainException.FieldError(field + "." + attribute.code(),
                        "attribute is required for this category"));
            }
        }

        if (!errors.isEmpty()) {
            throw new DomainException(ErrorCode.INVALID_ATTRIBUTES,
                    "Attribute values do not match the category configuration", errors);
        }
    }

    private String checkValue(MappedAttribute attribute, Object value) {
        if (value == null) {
            return "value must not be null";
        }
        return switch (attribute.valueType()) {
            case TEXT -> value instanceof String ? null : "value must be a string";
            case NUMBER -> value instanceof Number ? null : "value must be a number";
            case BOOLEAN -> value instanceof Boolean ? null : "value must be a boolean";
            case SELECT -> {
                if (!(value instanceof String text)) {
                    yield "value must be an option code";
                }
                yield attribute.optionCodes().contains(text) ? null
                        : "value is not a defined option of attribute " + attribute.code();
            }
        };
    }
}
