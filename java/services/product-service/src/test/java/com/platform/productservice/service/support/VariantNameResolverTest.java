package com.platform.productservice.service.support;

import com.platform.productservice.domain.entity.AttributeEntity;
import com.platform.productservice.domain.entity.AttributeOptionEntity;
import com.platform.productservice.domain.entity.SkuEntity;
import com.platform.productservice.domain.enums.AttributeValueType;
import com.platform.productservice.infrastructure.persistence.AttributeOptionRepository;
import com.platform.productservice.infrastructure.persistence.AttributeRepository;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class VariantNameResolverTest {
    @Test
    void batchesMetadataAndResolvesTypedDisplayWithoutChangingRawValues() {
        var attributes = mock(AttributeRepository.class);
        var options = mock(AttributeOptionRepository.class);
        var color = attribute("color", "Màu sắc", AttributeValueType.SELECT);
        var warranty = attribute("warranty", "Bảo hành", AttributeValueType.BOOLEAN);
        var size = attribute("size", "Dung lượng", AttributeValueType.NUMBER);
        var option = new AttributeOptionEntity();
        option.setAttributeId(color.getAttributeId()); option.setCode("black"); option.setLabel("Đen");
        when(attributes.findByCodeIn(any())).thenReturn(List.of(color, warranty, size));
        when(options.findByAttributeIdInOrderBySortOrderAscCodeAsc(any())).thenReturn(List.of(option));
        var a = sku(Map.of("warranty", true, "size", 128, "color", "black", "unmapped", "hidden"));
        var b = sku(Map.of("color", "legacy", "warranty", false));

        var names = new VariantNameResolver(attributes, options).resolve(List.of(a, b));

        assertThat(names.get(a.getSkuId())).isEqualTo("Màu sắc: Đen · Dung lượng: 128 · Bảo hành: Có");
        assertThat(names.get(b.getSkuId())).isEqualTo("Màu sắc: legacy · Bảo hành: Không");
        assertThat(a.getVariantAttributes()).containsEntry("color", "black");
        verify(attributes).findByCodeIn(Set.of("color", "warranty", "size", "unmapped"));
        verify(options).findByAttributeIdInOrderBySortOrderAscCodeAsc(List.of(color.getAttributeId()));
        verifyNoMoreInteractions(attributes, options);
    }

    @Test
    void plainSkusNeedNoMetadataQueries() {
        var attributes = mock(AttributeRepository.class);
        var options = mock(AttributeOptionRepository.class);
        assertThat(new VariantNameResolver(attributes, options).resolve(List.of(sku(Map.of())))).isEmpty();
        verifyNoInteractions(attributes, options);
    }

    private AttributeEntity attribute(String code, String name, AttributeValueType type) {
        var attribute = new AttributeEntity(); attribute.setAttributeId(UUID.randomUUID());
        attribute.setCode(code); attribute.setName(name); attribute.setValueType(type); return attribute;
    }
    private SkuEntity sku(Map<String, Object> values) {
        var sku = new SkuEntity(); sku.setSkuId(UUID.randomUUID()); sku.setVariantAttributes(values); return sku;
    }
}
