package com.platform.productservice.service.support;

import com.platform.productservice.domain.entity.AttributeEntity;
import com.platform.productservice.domain.entity.AttributeOptionEntity;
import com.platform.productservice.domain.entity.SkuEntity;
import com.platform.productservice.domain.enums.AttributeValueType;
import com.platform.productservice.infrastructure.persistence.AttributeOptionRepository;
import com.platform.productservice.infrastructure.persistence.AttributeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Resolves a batch with at most two metadata queries, never one query per SKU. */
@Component
@RequiredArgsConstructor
public class VariantNameResolver {
    private final AttributeRepository attributes;
    private final AttributeOptionRepository options;

    public Map<UUID, String> resolve(Collection<SkuEntity> skus) {
        Set<String> codes = skus.stream().flatMap(sku -> sku.getVariantAttributes().keySet().stream())
                .collect(Collectors.toSet());
        if (codes.isEmpty()) return Map.of();
        Map<String, AttributeEntity> definitions = attributes.findByCodeIn(codes).stream()
                .collect(Collectors.toMap(AttributeEntity::getCode, Function.identity()));
        List<UUID> selectIds = definitions.values().stream()
                .filter(attribute -> attribute.getValueType() == AttributeValueType.SELECT)
                .map(AttributeEntity::getAttributeId).toList();
        Map<UUID, Map<String, String>> labels = selectIds.isEmpty() ? Map.of()
                : options.findByAttributeIdInOrderBySortOrderAscCodeAsc(selectIds).stream()
                .collect(Collectors.groupingBy(AttributeOptionEntity::getAttributeId,
                        Collectors.toMap(AttributeOptionEntity::getCode, AttributeOptionEntity::getLabel)));
        return skus.stream().collect(Collectors.toMap(SkuEntity::getSkuId, sku ->
                sku.getVariantAttributes().entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .filter(entry -> entry.getValue() != null && definitions.containsKey(entry.getKey()))
                        .map(entry -> {
                            AttributeEntity attribute = definitions.get(entry.getKey());
                            Object value = entry.getValue();
                            String display = value instanceof Boolean flag ? (flag ? "Có" : "Không") : String.valueOf(value);
                            if (attribute.getValueType() == AttributeValueType.SELECT) {
                                display = labels.getOrDefault(attribute.getAttributeId(), Map.of())
                                        .getOrDefault(String.valueOf(value), display);
                            }
                            return attribute.getName() + ": " + display;
                        }).collect(Collectors.joining(" · "))));
    }
}
