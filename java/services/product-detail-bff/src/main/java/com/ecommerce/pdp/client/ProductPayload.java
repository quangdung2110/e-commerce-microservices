package com.ecommerce.pdp.client;

import com.ecommerce.pdp.resilience.ContractViolationException;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code BuyerProductDetail} của Product (contracts/http/product/v1/openapi.yaml, ADR-018)
 * theo tolerant reader: field không biết bị bỏ qua, còn thiếu field bắt buộc, sai kiểu,
 * sai enum, vượt giới hạn hay vi phạm ràng buộc tham chiếu là vi phạm contract (ADR-005).
 */
public record ProductPayload(String productId,
                             String name,
                             String slug,
                             String description,
                             Long catalogRevision,
                             Category category,
                             List<Attribute> attributes,
                             List<VariantAxis> variantAxes,
                             List<Media> media,
                             List<Sku> skus) {

    static final Set<String> VALUE_TYPES = Set.of("TEXT", "NUMBER", "BOOLEAN", "SELECT");
    static final Set<String> MEDIA_TYPES = Set.of("IMAGE", "VIDEO");

    public record CategoryRef(String categoryId, String name, String slug) {
    }

    public record Category(String categoryId, String name, String slug, List<CategoryRef> path) {
    }

    public record Attribute(String code, String name, String valueType, JsonNode value, String valueLabel) {
    }

    public record VariantOption(JsonNode value, String label) {
    }

    public record VariantAxis(String code, String name, String valueType, List<VariantOption> options) {
    }

    public record Media(String mediaId, String skuId, String mediaType, String storageKey,
                        String altText, Integer sortOrder) {
    }

    public record Sku(String skuId, String skuCode, Long basePriceVnd, Long sellingPriceVnd,
                      Map<String, JsonNode> variantAttributes, String thumbnailStorageKey) {
    }

    /** @return chính payload khi hợp lệ, để dùng trong chuỗi Reactor */
    public ProductPayload validated() {
        text(productId, "product_id", 64);
        text(name, "name", 255);
        text(slug, "slug", 180);
        require(catalogRevision != null && catalogRevision >= 1, "catalog_revision must be >= 1");
        validateCategory();
        validateAttributes();
        validateAxes();
        Set<String> skuIds = validateSkus();
        validateMedia(skuIds);
        validateVariantReferences();
        return this;
    }

    private void validateCategory() {
        require(category != null, "category is required");
        text(category.categoryId(), "category.category_id", 64);
        text(category.name(), "category.name", 160);
        text(category.slug(), "category.slug", 160);
        require(category.path() != null && !category.path().isEmpty() && category.path().size() <= 64,
                "category.path must hold 1..64 segments");
        for (CategoryRef segment : category.path()) {
            require(segment != null, "category.path segment is null");
            text(segment.categoryId(), "category.path.category_id", 64);
            text(segment.name(), "category.path.name", 160);
            text(segment.slug(), "category.path.slug", 160);
        }
    }

    private void validateAttributes() {
        require(attributes != null && attributes.size() <= 100, "attributes must be a list of at most 100");
        for (Attribute attribute : attributes) {
            require(attribute != null, "attribute is null");
            text(attribute.code(), "attributes.code", 64);
            text(attribute.name(), "attributes.name", 120);
            require(VALUE_TYPES.contains(attribute.valueType()), "attributes.value_type is not a known type");
            scalar(attribute.value(), "attributes.value");
            optionalText(attribute.valueLabel(), "attributes.value_label", 120);
        }
    }

    private void validateAxes() {
        require(variantAxes != null && variantAxes.size() <= 100, "variant_axes must be a list of at most 100");
        Set<String> codes = new HashSet<>();
        for (VariantAxis axis : variantAxes) {
            require(axis != null, "variant axis is null");
            text(axis.code(), "variant_axes.code", 64);
            require(codes.add(axis.code()), "duplicate variant axis " + axis.code());
            text(axis.name(), "variant_axes.name", 120);
            require(VALUE_TYPES.contains(axis.valueType()), "variant_axes.value_type is not a known type");
            require(axis.options() != null && !axis.options().isEmpty() && axis.options().size() <= 500,
                    "variant_axes.options must hold 1..500 options");
            for (VariantOption option : axis.options()) {
                require(option != null, "variant option is null");
                scalar(option.value(), "variant_axes.options.value");
                optionalText(option.label(), "variant_axes.options.label", 120);
            }
        }
    }

    private Set<String> validateSkus() {
        require(skus != null && !skus.isEmpty() && skus.size() <= 500, "skus must hold 1..500 SKUs");
        Set<String> ids = new HashSet<>();
        for (Sku sku : skus) {
            require(sku != null, "sku is null");
            text(sku.skuId(), "skus.sku_id", 64);
            require(ids.add(sku.skuId()), "duplicate sku_id " + sku.skuId());
            text(sku.skuCode(), "skus.sku_code", 64);
            require(sku.basePriceVnd() != null && sku.basePriceVnd() >= 1, "skus.base_price_vnd must be >= 1");
            require(sku.sellingPriceVnd() != null && sku.sellingPriceVnd() >= 0,
                    "skus.selling_price_vnd must be >= 0");
            require(sku.variantAttributes() != null && sku.variantAttributes().size() <= 100,
                    "skus.variant_attributes must be an object of at most 100 keys");
            sku.variantAttributes().values().forEach(value -> scalar(value, "skus.variant_attributes value"));
            if (sku.thumbnailStorageKey() != null) {
                storageKey(sku.thumbnailStorageKey(), "skus.thumbnail_storage_key");
            }
        }
        return ids;
    }

    private void validateMedia(Set<String> skuIds) {
        require(media != null && media.size() <= 5020, "media must be a list of at most 5020");
        Set<String> ids = new HashSet<>();
        for (Media item : media) {
            require(item != null, "media item is null");
            text(item.mediaId(), "media.media_id", 64);
            require(ids.add(item.mediaId()), "duplicate media_id " + item.mediaId());
            require(MEDIA_TYPES.contains(item.mediaType()), "media.media_type is not IMAGE or VIDEO");
            storageKey(item.storageKey(), "media.storage_key");
            optionalText(item.altText(), "media.alt_text", 255);
            require(item.sortOrder() != null && item.sortOrder() >= 0, "media.sort_order must be >= 0");
            require(item.skuId() == null || skuIds.contains(item.skuId()),
                    "media " + item.mediaId() + " points at a SKU that is not in skus[]");
        }
    }

    /** Mọi giá trị biến thể của SKU phải có đúng một option trong trục cùng code. */
    private void validateVariantReferences() {
        for (Sku sku : skus) {
            sku.variantAttributes().forEach((code, value) -> {
                VariantAxis axis = variantAxes.stream()
                        .filter(candidate -> candidate.code().equals(code))
                        .findFirst()
                        .orElseThrow(() -> new ContractViolationException(
                                "sku " + sku.skuId() + " uses variant attribute " + code + " with no axis"));
                String key = ScalarValues.key(value);
                require(axis.options().stream().anyMatch(option -> ScalarValues.key(option.value()).equals(key)),
                        "sku " + sku.skuId() + " value of " + code + " has no option in variant_axes");
            });
        }
    }

    private static void text(String value, String field, int maxLength) {
        require(value != null && !value.isBlank() && value.length() <= maxLength,
                field + " must be a non-empty string of at most " + maxLength);
    }

    private static void optionalText(String value, String field, int maxLength) {
        require(value == null || value.length() <= maxLength, field + " must be at most " + maxLength);
    }

    /**
     * Storage key dựng thành URL CDN bằng cách nối base + key. Segment rỗng, {@code .} hoặc
     * {@code ..} được trình duyệt chuẩn hóa nên URL thoát khỏi thư mục base của CDN: Product
     * chỉ kiểm key không rỗng, vì vậy BFF coi chúng là sai contract (ADR-005). Dấu {@code /}
     * đầu bị {@code MediaUrls} bỏ nên được chấp nhận.
     */
    private static void storageKey(String value, String field) {
        text(value, field, 1024);
        String key = value.replaceFirst("^/+", "");
        for (String segment : key.split("/", -1)) {
            require(!segment.isEmpty() && !segment.equals(".") && !segment.equals(".."),
                    field + " must not contain empty, \".\" or \"..\" path segments");
        }
    }

    private static void scalar(JsonNode value, String field) {
        require(ScalarValues.isScalar(value), field + " must be a string, number or boolean");
        require(!value.isTextual() || value.textValue().length() <= 500, field + " must be at most 500 characters");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new ContractViolationException("Product: " + message);
        }
    }
}
