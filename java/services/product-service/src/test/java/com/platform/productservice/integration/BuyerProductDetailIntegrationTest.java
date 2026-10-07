package com.platform.productservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.platform.productservice.common.DomainException;
import com.platform.productservice.common.ErrorCode;
import com.platform.productservice.domain.enums.AttributeValueType;
import com.platform.productservice.domain.enums.MediaType;
import com.platform.productservice.domain.enums.ProductStatus;
import com.platform.productservice.domain.enums.SkuStatus;
import com.platform.productservice.service.ProductQueryService;
import com.platform.productservice.web.dto.AttributeDtos;
import com.platform.productservice.web.dto.BuyerDtos;
import com.platform.productservice.web.dto.CategoryDtos;
import com.platform.productservice.web.dto.ProductDtos;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.platform.productservice.config.TrustedIdentityFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Buyer-read nội bộ cho Product Detail BFF (ADR-015, ADR-018, WF-PRODUCT-007).
 *
 * <p>Hai nhóm kiểm tra: những gì endpoint không trả (catalog chưa công bố, SKU
 * {@code INACTIVE} cùng media của chúng, mọi field tồn kho, {@code ETag}) và những gì
 * Product phải resolve sẵn cho BFF (category path, nhãn, trục biến thể, thứ tự, giới
 * hạn gallery).
 */
class BuyerProductDetailIntegrationTest extends AbstractCatalogIntegrationTest {

    /** Từ khóa của tồn kho; không field nào trong response được chứa chúng. */
    private static final List<String> STOCK_MARKERS =
            List.of("stock", "quantity", "availab", "reserv", "on_hand", "observed_at");

    @Autowired
    private ProductQueryService productQueryService;
    @Autowired
    private WebApplicationContext webApplicationContext;
    @Autowired
    private TrustedIdentityFilter trustedIdentityFilter;

    private UUID categoryId;
    private MockMvc mockMvc;

    /**
     * MockMvc dựng từ context dùng chung thay vì {@code @AutoConfigureMockMvc}: annotation
     * đó tạo một Spring context riêng cho class này, làm mất context cache của cả suite.
     */
    @BeforeEach
    void givenCategory() {
        categoryId = givenLeafCategory("phones");
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(trustedIdentityFilter)
                .build();
    }

    @Test
    @DisplayName("Product ACTIVE đọc được theo slug kèm giá công bố")
    void activeProductIsReadableBySlug() {
        ProductDtos.ProductResponse created = givenActiveProduct("pixel", 25_000_000L);

        BuyerDtos.BuyerProductDetail detail = productQueryService.getBuyerDetail("pixel");

        assertThat(detail.productId()).isEqualTo(created.productId());
        assertThat(detail.slug()).isEqualTo("pixel");
        assertThat(detail.catalogRevision()).isEqualTo(currentRevision(created.productId()));
        assertThat(detail.skus()).hasSize(1);
        assertThat(detail.skus().get(0).basePriceVnd()).isEqualTo(25_000_000L);
        assertThat(detail.skus().get(0).sellingPriceVnd()).isEqualTo(25_000_000L);
    }

    @Test
    @DisplayName("Product DRAFT không lộ ra buyer-read")
    void draftProductIsNotVisible() {
        givenProduct(categoryId, "unreleased", 1_000L);

        assertNotFound("unreleased");
    }

    @Test
    @DisplayName("Product HIDDEN không lộ ra buyer-read")
    void hiddenProductIsNotVisible() {
        ProductDtos.ProductResponse product = givenActiveProduct("recalled", 1_000L);
        productCommandService.changeStatus(
                product.productId(), etagOf(product.productId()), ProductStatus.HIDDEN);

        assertNotFound("recalled");
    }

    @Test
    @DisplayName("Slug không tồn tại và slug chưa công bố trả cùng một lỗi")
    void unknownSlugIsNotFound() {
        assertNotFound("no-such-slug");
    }

    @Test
    @DisplayName("SKU INACTIVE bị lọc khỏi buyer-read cùng media của nó")
    void inactiveSkusAndTheirMediaAreFiltered() {
        ProductDtos.ProductResponse product = givenActiveProduct("laptop", 30_000_000L);
        UUID productId = product.productId();
        UUID retiredSkuId = skuCommandService.create(productId, etagOf(productId),
                new ProductDtos.CreateSkuRequest("LAPTOP-2", 31_000_000L, SkuStatus.ACTIVE, Map.of(),
                        List.of(new ProductDtos.CreateMediaInput(
                                MediaType.IMAGE, "skus/laptop-2/front.jpg", null, 0)))).body().skuId();
        skuCommandService.replace(productId, retiredSkuId, etagOf(productId),
                new ProductDtos.ReplaceSkuRequest("LAPTOP-2", 31_000_000L, SkuStatus.INACTIVE, Map.of()));

        BuyerDtos.BuyerProductDetail detail = productQueryService.getBuyerDetail("laptop");

        assertThat(detail.skus()).extracting(BuyerDtos.BuyerSku::skuId).doesNotContain(retiredSkuId);
        assertThat(detail.media()).extracting(BuyerDtos.BuyerMedia::skuId).doesNotContain(retiredSkuId);
    }

    @Test
    @DisplayName("Product ACTIVE không còn SKU saleable trả not found")
    void productWithoutSaleableSkuIsNotFound() {
        ProductDtos.ProductResponse product = givenActiveProduct("sold-out-forever", 500L);
        UUID productId = product.productId();
        ProductDtos.SkuResponse onlySku = product.skus().get(0);
        skuCommandService.replace(productId, onlySku.skuId(), etagOf(productId),
                new ProductDtos.ReplaceSkuRequest(onlySku.skuCode(), 500L, SkuStatus.INACTIVE, Map.of()));

        // Product vẫn ACTIVE trong catalog quản trị; chỉ buyer-read từ chối nó.
        assertThat(productRepository.findById(productId).orElseThrow().getStatus())
                .isEqualTo(ProductStatus.ACTIVE);
        assertNotFound("sold-out-forever");
    }

    @Test
    @DisplayName("Category mang breadcrumb từ gốc tới category lá của Product")
    void categoryCarriesPathFromRootToLeaf() {
        UUID electronics = givenLeafCategory("electronics");
        UUID phones = givenChildCategory(electronics, "smartphones");
        UUID flagships = givenChildCategory(phones, "flagships");
        ProductDtos.ProductResponse product = givenProduct(flagships, "galaxy", 20_000_000L);
        activate(product.productId());

        BuyerDtos.BuyerCategory category = productQueryService.getBuyerDetail("galaxy").category();

        assertThat(category.categoryId()).isEqualTo(flagships);
        assertThat(category.path()).extracting(CategoryDtos.CategoryPathSegment::slug)
                .containsExactly("electronics", "smartphones", "flagships");
    }

    @Test
    @DisplayName("Thuộc tính mang tên, nhãn option chỉ với SELECT và sắp theo collation tiếng Việt")
    void attributesAreResolvedAndOrderedByVietnameseName() {
        UUID brand = givenSelectAttribute("brand", "Màu vỏ", Map.of("graphite", "Xám than"));
        UUID capacity = givenTypedAttribute("capacity", "Dung lượng pin", AttributeValueType.NUMBER);
        UUID resolution = givenTypedAttribute("resolution", "Độ phân giải", AttributeValueType.TEXT);
        UUID waterproof = givenTypedAttribute("waterproof", "Chống nước", AttributeValueType.BOOLEAN);
        for (UUID attribute : List.of(brand, capacity, resolution, waterproof)) {
            givenMapping(categoryId, attribute, false, false);
        }
        UUID productId = productCommandService.create(new ProductDtos.CreateProductRequest(
                categoryId, "Phone", "phone", null,
                Map.of("brand", "graphite", "capacity", 5000, "resolution", "1080x2400", "waterproof", true),
                List.of(), List.of(sku("PHONE-1", Map.of())))).productId();
        activate(productId);

        List<BuyerDtos.BuyerAttributeValue> attributes =
                productQueryService.getBuyerDetail("phone").attributes();

        // Code point đặt "Độ" sau "Màu"; collation tiếng Việt đặt Đ ngay sau D.
        assertThat(attributes).extracting(BuyerDtos.BuyerAttributeValue::name)
                .containsExactly("Chống nước", "Dung lượng pin", "Độ phân giải", "Màu vỏ");
        assertThat(attributes).filteredOn(attribute -> attribute.code().equals("brand"))
                .singleElement()
                .satisfies(attribute -> {
                    assertThat(attribute.valueType()).isEqualTo(AttributeValueType.SELECT);
                    assertThat(attribute.value()).isEqualTo("graphite");
                    assertThat(attribute.valueLabel()).isEqualTo("Xám than");
                });
        assertThat(attributes).filteredOn(attribute -> !attribute.code().equals("brand"))
                .allSatisfy(attribute -> assertThat(attribute.valueLabel()).isNull());
    }

    @Test
    @DisplayName("Trục biến thể chỉ gồm option SKU ACTIVE đang dùng, đúng thứ tự và nhãn")
    void variantAxesCarryInUseOptionsInOrder() {
        UUID color = givenSelectAttribute("color", "Màu sắc",
                Map.of("green", "Xanh lá", "blue", "Xanh dương", "red", "Đỏ"));
        UUID storage = givenTypedAttribute("storage", "Bộ nhớ", AttributeValueType.NUMBER);
        givenMapping(categoryId, color, true, true);
        givenMapping(categoryId, storage, true, true);
        UUID productId = productCommandService.create(new ProductDtos.CreateProductRequest(
                categoryId, "Tee", "tee", null, Map.of(), List.of(),
                List.of(sku("TEE-RED-256", Map.of("color", "red", "storage", 256)),
                        sku("TEE-BLUE-128", Map.of("color", "blue", "storage", 128)),
                        sku("TEE-RED-128", Map.of("color", "red", "storage", 128))))).productId();
        UUID greenSkuId = skuCommandService.create(productId, etagOf(productId),
                new ProductDtos.CreateSkuRequest("TEE-GREEN-512", 1_000L, SkuStatus.ACTIVE,
                        Map.of("color", "green", "storage", 512), List.of())).body().skuId();
        skuCommandService.replace(productId, greenSkuId, etagOf(productId),
                new ProductDtos.ReplaceSkuRequest("TEE-GREEN-512", 1_000L, SkuStatus.INACTIVE,
                        Map.of("color", "green", "storage", 512)));
        activate(productId);

        BuyerDtos.BuyerProductDetail detail = productQueryService.getBuyerDetail("tee");

        assertThat(detail.variantAxes()).extracting(BuyerDtos.BuyerVariantAxis::name)
                .containsExactly("Bộ nhớ", "Màu sắc");
        BuyerDtos.BuyerVariantAxis storageAxis = detail.variantAxes().get(0);
        assertThat(storageAxis.options()).extracting(option -> ((Number) option.value()).intValue())
                .containsExactly(128, 256);
        assertThat(storageAxis.options()).allSatisfy(option -> assertThat(option.label()).isNull());
        // Option của SKU INACTIVE (green) không xuất hiện; SELECT theo sort_order của option.
        BuyerDtos.BuyerVariantAxis colorAxis = detail.variantAxes().get(1);
        assertThat(colorAxis.options()).extracting(BuyerDtos.BuyerVariantOption::value)
                .containsExactly("blue", "red");
        assertThat(colorAxis.options()).extracting(BuyerDtos.BuyerVariantOption::label)
                .containsExactly("Xanh dương", "Đỏ");
        assertThat(detail.skus()).allSatisfy(sku ->
                assertThat(sku.variantAttributes()).containsOnlyKeys("color", "storage"));
    }

    @Test
    @DisplayName("Gallery cắt 20 media cấp Product và 10 media mỗi SKU, cấp Product đứng trước")
    void galleryIsCappedAndOrdered() {
        List<ProductDtos.CreateMediaInput> productMedia = IntStream.range(0, 25)
                .mapToObj(i -> new ProductDtos.CreateMediaInput(
                        MediaType.IMAGE, "products/cam/" + i + ".jpg", null, 24 - i))
                .toList();
        List<ProductDtos.CreateMediaInput> skuMedia = IntStream.range(0, 12)
                .mapToObj(i -> new ProductDtos.CreateMediaInput(
                        MediaType.IMAGE, "skus/cam-1/" + i + ".jpg", null, i))
                .toList();
        UUID productId = productCommandService.create(new ProductDtos.CreateProductRequest(
                categoryId, "Cam", "cam", null, Map.of(), productMedia,
                List.of(new ProductDtos.CreateSkuRequest("CAM-1", 1_000L, SkuStatus.ACTIVE, Map.of(), skuMedia))))
                .productId();
        activate(productId);

        List<BuyerDtos.BuyerMedia> media = productQueryService.getBuyerDetail("cam").media();

        List<BuyerDtos.BuyerMedia> shared = media.subList(0, 20);
        assertThat(shared).allSatisfy(item -> assertThat(item.skuId()).isNull());
        assertThat(shared).extracting(BuyerDtos.BuyerMedia::sortOrder)
                .containsExactlyElementsOf(IntStream.range(0, 20).boxed().toList());
        List<BuyerDtos.BuyerMedia> own = media.subList(20, media.size());
        assertThat(own).hasSize(10).allSatisfy(item -> assertThat(item.skuId()).isNotNull());
        assertThat(own).extracting(BuyerDtos.BuyerMedia::sortOrder)
                .containsExactlyElementsOf(IntStream.range(0, 10).boxed().toList());
    }

    @Test
    @DisplayName("Thumbnail resolve trên toàn bộ media, không phụ thuộc gallery đã cắt")
    void thumbnailIgnoresGalleryCap() {
        List<ProductDtos.CreateMediaInput> skuMedia = new ArrayList<>(IntStream.range(0, 10)
                .mapToObj(i -> new ProductDtos.CreateMediaInput(
                        MediaType.VIDEO, "skus/drone-1/clip-" + i + ".mp4", null, i))
                .toList());
        skuMedia.add(new ProductDtos.CreateMediaInput(MediaType.IMAGE, "skus/drone-1/front.jpg", null, 10));
        UUID productId = productCommandService.create(new ProductDtos.CreateProductRequest(
                categoryId, "Drone", "drone", null, Map.of(),
                List.of(new ProductDtos.CreateMediaInput(MediaType.IMAGE, "products/drone/hero.jpg", null, 0)),
                List.of(new ProductDtos.CreateSkuRequest("DRONE-1", 1_000L, SkuStatus.ACTIVE, Map.of(), skuMedia))))
                .productId();
        activate(productId);

        BuyerDtos.BuyerProductDetail detail = productQueryService.getBuyerDetail("drone");

        // Ảnh của SKU nằm ngoài 10 media đầu nhưng vẫn thắng ảnh cấp Product (ADR-013).
        assertThat(detail.media()).extracting(BuyerDtos.BuyerMedia::storageKey)
                .doesNotContain("skus/drone-1/front.jpg");
        assertThat(detail.skus().get(0).thumbnailStorageKey()).isEqualTo("skus/drone-1/front.jpg");
    }

    @Test
    @DisplayName("Response buyer-read không chứa bất kỳ field tồn kho nào")
    void responseCarriesNoStockField() {
        ProductDtos.ProductResponse product = givenActiveProduct("tablet", 9_000_000L);
        UUID productId = product.productId();
        mediaCommandService.create(productId, etagOf(productId), new ProductDtos.CreateMediaRequest(
                null, MediaType.IMAGE, "products/tablet/hero.jpg", "hero", 0));

        JsonNode json = objectMapper.valueToTree(productQueryService.getBuyerDetail("tablet"));

        assertThat(fieldNames(json)).isNotEmpty().allSatisfy(name ->
                assertThat(STOCK_MARKERS).noneMatch(name::contains));
    }

    @Test
    @DisplayName("HTTP buyer-read khớp BuyerProductDetail trong OpenAPI và không có ETag")
    void httpResponseMatchesContractWithoutEtag() throws Exception {
        Path openapi = Paths.get("").toAbsolutePath()
                .resolve("../../../contracts/http/product/v1/openapi.yaml").normalize();
        Assumptions.assumeTrue(Files.isRegularFile(openapi),
                "contracts/ chỉ có mặt khi test chạy trong repository");
        UUID color = givenSelectAttribute("color", "Màu sắc", Map.of("black", "Đen"));
        UUID material = givenTypedAttribute("material", "Chất liệu", AttributeValueType.TEXT);
        givenMapping(categoryId, color, true, true);
        givenMapping(categoryId, material, false, false);
        UUID productId = productCommandService.create(new ProductDtos.CreateProductRequest(
                categoryId, "Watch", "watch", null, Map.of("material", "titanium"),
                List.of(new ProductDtos.CreateMediaInput(MediaType.IMAGE, "products/watch/hero.jpg", "hero", 0)),
                List.of(new ProductDtos.CreateSkuRequest("WATCH-BLACK", 5_000_000L, SkuStatus.ACTIVE,
                        Map.of("color", "black"),
                        List.of(new ProductDtos.CreateMediaInput(
                                MediaType.VIDEO, "skus/watch-black/spin.mp4", null, 0))))))
                .productId();
        activate(productId);

        MvcResult result = mockMvc.perform(get("/internal/v1/product-details/watch")
                        .header("X-Service-Identity", "product-detail-bff"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getHeader("ETag")).isNull();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonSchema schema = buyerDetailSchema(openapi);
        Set<ValidationMessage> errors = schema.validate(body);
        assertThat(errors).isEmpty();
        // Đối chứng: schema thật sự được resolve, nên body thiếu field bắt buộc phải bị bắt.
        ObjectNode withoutCategory = body.deepCopy();
        withoutCategory.remove("category");
        assertThat(schema.validate(withoutCategory)).isNotEmpty();
        assertThat(body.path("description").isNull()).isTrue();
        assertThat(body.path("media").get(0).path("sku_id").isNull()).isTrue();
    }

    // ---------------------------------------------------------------- fixtures

    /** Product ACTIVE một SKU ACTIVE — trạng thái duy nhất buyer-read chấp nhận. */
    private ProductDtos.ProductResponse givenActiveProduct(String slug, long basePriceVnd) {
        ProductDtos.ProductResponse product = givenProduct(categoryId, slug, basePriceVnd);
        return activate(product.productId());
    }

    private ProductDtos.ProductResponse activate(UUID productId) {
        return productCommandService.changeStatus(productId, etagOf(productId), ProductStatus.ACTIVE);
    }

    private ProductDtos.CreateSkuRequest sku(String code, Map<String, Object> variantAttributes) {
        return new ProductDtos.CreateSkuRequest(code, 1_000L, SkuStatus.ACTIVE, variantAttributes, List.of());
    }

    private UUID givenTypedAttribute(String code, String name, AttributeValueType type) {
        return attributeService.create(new AttributeDtos.CreateAttributeRequest(code, name, type)).attributeId();
    }

    /**
     * Attribute {@code SELECT}; {@code sort_order} theo thứ tự khai báo trong
     * {@code labelsByCode} ngược lại, để thứ tự option không trùng thứ tự chữ cái.
     */
    private UUID givenSelectAttribute(String code, String name, Map<String, String> labelsByCode) {
        UUID attributeId = givenTypedAttribute(code, name, AttributeValueType.SELECT);
        List<String> codes = labelsByCode.keySet().stream().sorted().toList();
        for (int i = 0; i < codes.size(); i++) {
            String optionCode = codes.get(i);
            attributeService.createOption(attributeId, new AttributeDtos.CreateAttributeOptionRequest(
                    optionCode, labelsByCode.get(optionCode), sortOrderOf(optionCode, i)));
        }
        return attributeId;
    }

    /** Thứ tự hiển thị cố ý khác thứ tự chữ cái: blue, red, green. */
    private int sortOrderOf(String optionCode, int alphabeticalIndex) {
        return switch (optionCode) {
            case "blue" -> 0;
            case "red" -> 1;
            case "green" -> 2;
            default -> alphabeticalIndex;
        };
    }

    private void assertNotFound(String slug) {
        assertThatThrownBy(() -> productQueryService.getBuyerDetail(slug))
                .isInstanceOf(DomainException.class)
                .extracting(error -> ((DomainException) error).errorCode())
                .isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
    }

    /**
     * Schema {@code BuyerProductDetail} lấy thẳng từ OpenAPI: gốc là cả tài liệu để
     * {@code $ref: '#/components/schemas/...'} resolve được.
     */
    private JsonSchema buyerDetailSchema(Path openapi) throws Exception {
        ObjectNode root = (ObjectNode) new ObjectMapper(new YAMLFactory()).readTree(openapi.toFile());
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.put("$ref", "#/components/schemas/BuyerProductDetail");
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root);
    }

    /** Mọi tên field trong cây JSON, đã lowercase, kể cả field lồng trong sku/media. */
    private List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        collectFieldNames(node, names);
        return names;
    }

    private void collectFieldNames(JsonNode node, List<String> names) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                names.add(entry.getKey().toLowerCase(Locale.ROOT));
                collectFieldNames(entry.getValue(), names);
            });
            return;
        }
        if (node.isArray()) {
            node.forEach(element -> collectFieldNames(element, names));
        }
    }
}
