package com.platform.productservice.service.support;

import com.platform.productservice.domain.entity.AttributeEntity;
import com.platform.productservice.domain.entity.AttributeOptionEntity;
import com.platform.productservice.domain.entity.CategoryAttributeEntity;
import com.platform.productservice.domain.entity.CategoryEntity;
import com.platform.productservice.domain.entity.ProductEntity;
import com.platform.productservice.domain.entity.ProductMediaEntity;
import com.platform.productservice.domain.entity.SkuEntity;
import com.platform.productservice.domain.enums.AttributeValueType;
import com.platform.productservice.infrastructure.persistence.AttributeOptionRepository;
import com.platform.productservice.infrastructure.persistence.AttributeRepository;
import com.platform.productservice.infrastructure.persistence.CategoryAttributeRepository;
import com.platform.productservice.web.dto.BuyerDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.Collator;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Dựng projection buyer cho Product Detail BFF (ADR-018). Product resolve mọi thứ BFF
 * cần để hiển thị — category path, tên/nhãn thuộc tính, trục biến thể và thứ tự — để
 * BFF chỉ map chứ không phải gọi thêm API category/attribute hay tự áp quy tắc thứ tự.
 *
 * <p>Category và định nghĩa attribute không thuộc Product aggregate: chúng được đọc ở
 * trạng thái hiện hành lúc request, không gắn với {@code catalog_revision}.
 */
@Component
@RequiredArgsConstructor
public class BuyerDetailAssembler {

    /** Giới hạn gallery của buyer-read (ADR-018, {@code BuyerProductDetail.media}). */
    public static final int PRODUCT_MEDIA_LIMIT = 20;
    public static final int SKU_MEDIA_LIMIT = 10;

    private final CategoryTreeService categoryTree;
    private final CategoryAttributeRepository categoryAttributeRepository;
    private final AttributeRepository attributeRepository;
    private final AttributeOptionRepository attributeOptionRepository;
    private final CatalogAssembler catalogAssembler;

    /** Attribute được map vào category của Product, kèm option theo thứ tự hiển thị. */
    private record Mapped(AttributeEntity attribute,
                          boolean variant,
                          List<AttributeOptionEntity> options) {

        String code() {
            return attribute.getCode();
        }

        /** Label của option có code {@code value}; option đã bị xóa thì rơi về chính code. */
        String labelOf(Object value) {
            String code = String.valueOf(value);
            return options.stream()
                    .filter(option -> option.getCode().equals(code))
                    .map(AttributeOptionEntity::getLabel)
                    .findFirst()
                    .orElse(code);
        }

        int optionRank(Object value) {
            String code = String.valueOf(value);
            for (int i = 0; i < options.size(); i++) {
                if (options.get(i).getCode().equals(code)) {
                    return i;
                }
            }
            return Integer.MAX_VALUE;
        }
    }

    /**
     * @param saleableSkus SKU {@code ACTIVE} của Product theo thứ tự {@code sku_code}
     * @param gallery      kết quả {@code findBuyerGallery}, đã cắt và đúng thứ tự contract
     * @param thumbnailCandidates kết quả {@code findThumbnailCandidates}
     */
    public BuyerDtos.BuyerProductDetail toBuyerDetail(ProductEntity product,
                                                      List<SkuEntity> saleableSkus,
                                                      List<ProductMediaEntity> gallery,
                                                      List<ProductMediaEntity> thumbnailCandidates) {
        List<Mapped> mapped = mappedAttributes(product.getCategoryId());
        List<Mapped> specs = mapped.stream().filter(attribute -> !attribute.variant()).toList();
        List<Mapped> axes = mapped.stream().filter(Mapped::variant).toList();

        return new BuyerDtos.BuyerProductDetail(
                product.getProductId(),
                product.getName(),
                product.getSlug(),
                product.getDescription(),
                product.getCatalogRevision(),
                product.getUpdatedAt(),
                category(product.getCategoryId()),
                attributes(specs, product.getAttributes()),
                variantAxes(axes, saleableSkus),
                gallery.stream().map(this::toMedia).toList(),
                saleableSkus.stream().map(sku -> toSku(sku, axes, thumbnailCandidates)).toList());
    }

    private BuyerDtos.BuyerCategory category(UUID categoryId) {
        CategoryEntity category = categoryTree.require(categoryId);
        return new BuyerDtos.BuyerCategory(
                category.getCategoryId(),
                category.getName(),
                category.getSlug(),
                catalogAssembler.toPath(categoryTree.path(category)));
    }

    /** Mapping của category, sắp theo tên (collation tiếng Việt) rồi {@code code}. */
    private List<Mapped> mappedAttributes(UUID categoryId) {
        List<CategoryAttributeEntity> mappings = categoryAttributeRepository.findByIdCategoryId(categoryId);
        if (mappings.isEmpty()) {
            return List.of();
        }
        List<UUID> attributeIds = mappings.stream().map(mapping -> mapping.getId().getAttributeId()).toList();
        Map<UUID, AttributeEntity> attributes = attributeRepository.findByAttributeIdIn(attributeIds).stream()
                .collect(Collectors.toMap(AttributeEntity::getAttributeId, attribute -> attribute));
        Map<UUID, List<AttributeOptionEntity>> options = attributeOptionRepository
                .findByAttributeIdInOrderBySortOrderAscCodeAsc(attributeIds).stream()
                .collect(Collectors.groupingBy(AttributeOptionEntity::getAttributeId));

        Collator collator = vietnameseCollator();
        return mappings.stream()
                .filter(mapping -> attributes.containsKey(mapping.getId().getAttributeId()))
                .map(mapping -> {
                    UUID attributeId = mapping.getId().getAttributeId();
                    return new Mapped(attributes.get(attributeId), mapping.isVariant(),
                            options.getOrDefault(attributeId, List.of()));
                })
                .sorted(Comparator.<Mapped, String>comparing(item -> item.attribute().getName(), collator)
                        .thenComparing(Mapped::code))
                .toList();
    }

    /**
     * Chỉ mapping không biến thể có giá trị. Key trong {@code products.attributes} không
     * còn mapping (category đổi mapping sau khi ghi) bị bỏ thay vì hiển thị không nhãn.
     */
    private List<BuyerDtos.BuyerAttributeValue> attributes(List<Mapped> specs, Map<String, Object> values) {
        return specs.stream()
                .filter(attribute -> values.get(attribute.code()) != null)
                .map(attribute -> {
                    Object value = values.get(attribute.code());
                    AttributeValueType type = attribute.attribute().getValueType();
                    return new BuyerDtos.BuyerAttributeValue(
                            attribute.code(),
                            attribute.attribute().getName(),
                            type,
                            value,
                            type == AttributeValueType.SELECT ? attribute.labelOf(value) : null);
                })
                .toList();
    }

    /** Trục biến thể chỉ gồm giá trị ít nhất một SKU {@code ACTIVE} đang dùng. */
    private List<BuyerDtos.BuyerVariantAxis> variantAxes(List<Mapped> axes, List<SkuEntity> skus) {
        return axes.stream()
                .map(axis -> {
                    Map<String, Object> distinct = new LinkedHashMap<>();
                    for (SkuEntity sku : skus) {
                        Object value = sku.getVariantAttributes().get(axis.code());
                        if (value != null) {
                            distinct.putIfAbsent(identity(value), value);
                        }
                    }
                    AttributeValueType type = axis.attribute().getValueType();
                    List<BuyerDtos.BuyerVariantOption> options = distinct.values().stream()
                            .sorted(optionOrder(axis))
                            .map(value -> new BuyerDtos.BuyerVariantOption(
                                    value, type == AttributeValueType.SELECT ? axis.labelOf(value) : null))
                            .toList();
                    return new BuyerDtos.BuyerVariantAxis(
                            axis.code(), axis.attribute().getName(), type, options);
                })
                .filter(axis -> !axis.options().isEmpty())
                .toList();
    }

    /**
     * {@code SELECT} theo {@code sort_order} rồi {@code code} của option; kiểu khác theo
     * giá trị tăng dần.
     */
    private Comparator<Object> optionOrder(Mapped axis) {
        Comparator<Object> byText = Comparator.comparing(value -> String.valueOf(value));
        return switch (axis.attribute().getValueType()) {
            case SELECT -> Comparator.<Object>comparingInt(axis::optionRank).thenComparing(byText);
            case NUMBER -> Comparator.<Object, BigDecimal>comparing(BuyerDetailAssembler::asDecimal);
            case BOOLEAN -> Comparator.<Object, Boolean>comparing(
                    value -> Boolean.parseBoolean(String.valueOf(value)));
            case TEXT -> Comparator.<Object, String>comparing(
                    value -> String.valueOf(value), vietnameseCollator()).thenComparing(byText);
        };
    }

    /**
     * Khóa so trùng: JSONB có thể trả {@code 1} và {@code 1.0} thành hai kiểu Java khác
     * nhau cho cùng một số.
     */
    private static String identity(Object value) {
        if (value instanceof Number) {
            return "n:" + asDecimal(value).stripTrailingZeros().toPlainString();
        }
        return value.getClass().getSimpleName() + ":" + value;
    }

    private static BigDecimal asDecimal(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    /**
     * {@code variant_attributes} chỉ giữ key của trục đang được map, để mọi giá trị SKU
     * luôn có option tương ứng trong {@code variant_axes} như contract hứa.
     */
    private BuyerDtos.BuyerSku toSku(SkuEntity sku,
                                     List<Mapped> axes,
                                     List<ProductMediaEntity> thumbnailCandidates) {
        Map<String, Object> variantAttributes = new LinkedHashMap<>();
        for (Mapped axis : axes) {
            Object value = sku.getVariantAttributes().get(axis.code());
            if (value != null) {
                variantAttributes.put(axis.code(), value);
            }
        }
        return new BuyerDtos.BuyerSku(
                sku.getSkuId(),
                sku.getSkuCode(),
                sku.getBasePriceVnd(),
                sku.getSellingPriceVnd(),
                variantAttributes,
                catalogAssembler.effectiveThumbnail(sku.getSkuId(), thumbnailCandidates));
    }

    private BuyerDtos.BuyerMedia toMedia(ProductMediaEntity media) {
        return new BuyerDtos.BuyerMedia(
                media.getMediaId(),
                media.getSkuId(),
                media.getMediaType(),
                media.getStorageKey(),
                media.getAltText(),
                media.getSortOrder());
    }

    /** Collator không thread-safe; mỗi lần sắp dùng một instance riêng. */
    private static Collator vietnameseCollator() {
        return Collator.getInstance(Locale.forLanguageTag("vi"));
    }
}
