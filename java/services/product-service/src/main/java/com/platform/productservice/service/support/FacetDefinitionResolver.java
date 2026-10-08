package com.platform.productservice.service.support;

import com.platform.productservice.domain.FacetDefinition;
import com.platform.productservice.domain.entity.AttributeEntity;
import com.platform.productservice.domain.entity.CategoryAttributeEntity;
import com.platform.productservice.infrastructure.persistence.AttributeRepository;
import com.platform.productservice.infrastructure.persistence.CategoryAttributeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Dựng {@code facet_definitions} của Category snapshot và Category export (ADR-019).
 *
 * <p>Descriptor là mọi attribute được map trực tiếp vào category, sắp theo {@code code}.
 * Product không có cờ "filterable": Search lập token cho cả thuộc tính Product-level lẫn
 * thuộc tính biến thể, nên mapping nào cũng có thể trở thành facet. Mapping của category
 * cha không được thừa kế ở đây, vì Product chỉ thuộc category lá và label/type thuộc về
 * attribute (cùng một {@code code} có cùng descriptor ở mọi category).
 */
@Component
@RequiredArgsConstructor
public class FacetDefinitionResolver {

    private final CategoryAttributeRepository categoryAttributeRepository;
    private final AttributeRepository attributeRepository;

    public List<FacetDefinition> of(UUID categoryId) {
        return of(List.of(categoryId)).getOrDefault(categoryId, List.of());
    }

    /**
     * Batch cho export: một truy vấn mapping và một truy vấn attribute cho cả trang.
     * Category không có mapping vẫn có entry rỗng để caller không phải xử lý null.
     */
    public Map<UUID, List<FacetDefinition>> of(Collection<UUID> categoryIds) {
        Map<UUID, List<FacetDefinition>> result = new HashMap<>();
        categoryIds.forEach(categoryId -> result.put(categoryId, List.of()));
        if (categoryIds.isEmpty()) {
            return result;
        }

        List<CategoryAttributeEntity> mappings = categoryAttributeRepository.findByIdCategoryIdIn(categoryIds);
        if (mappings.isEmpty()) {
            return result;
        }
        Map<UUID, AttributeEntity> attributes = attributeRepository
                .findByAttributeIdIn(mappings.stream().map(mapping -> mapping.getId().getAttributeId()).toList())
                .stream()
                .collect(Collectors.toMap(AttributeEntity::getAttributeId, Function.identity()));

        Map<UUID, List<FacetDefinition>> grouped = mappings.stream()
                .filter(mapping -> attributes.containsKey(mapping.getId().getAttributeId()))
                .collect(Collectors.groupingBy(
                        mapping -> mapping.getId().getCategoryId(),
                        Collectors.mapping(mapping -> toDefinition(attributes.get(mapping.getId().getAttributeId())),
                                Collectors.toList())));
        grouped.forEach((categoryId, definitions) -> result.put(categoryId,
                definitions.stream().sorted(Comparator.comparing(FacetDefinition::code)).toList()));
        return result;
    }

    private static FacetDefinition toDefinition(AttributeEntity attribute) {
        return new FacetDefinition(attribute.getCode(), attribute.getName(), attribute.getValueType());
    }
}
