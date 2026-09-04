package com.platform.productservice.service.support;

import com.platform.productservice.common.DomainException;
import com.platform.productservice.common.ErrorCode;
import com.platform.productservice.domain.enums.AttributeValueType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validate attribute theo mapping category–attribute. Schema được dựng trực
 * tiếp nên test này không chạm database.
 */
class AttributeValidatorTest {

    private final AttributeValidator validator = new AttributeValidator(null, null, null);

    private final AttributeValidator.CategorySchema schema = new AttributeValidator.CategorySchema(Map.of(
            "brand", mapped("brand", AttributeValueType.TEXT, true, false, Set.of()),
            "weight", mapped("weight", AttributeValueType.NUMBER, false, false, Set.of()),
            "refurbished", mapped("refurbished", AttributeValueType.BOOLEAN, false, false, Set.of()),
            "color", mapped("color", AttributeValueType.SELECT, true, true, Set.of("black", "white"))));

    @Test
    @DisplayName("Giá trị hợp lệ được chấp nhận")
    void acceptsValidValues() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("brand", "Google");
        attributes.put("weight", 187.5);
        attributes.put("refurbished", false);

        assertThatCode(() -> validator.validateProductAttributes(schema, attributes))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validateVariantAttributes(schema, Map.of("color", "black")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Thiếu attribute bắt buộc trả INVALID_ATTRIBUTES kèm field")
    void rejectsMissingRequiredAttribute() {
        assertThatThrownBy(() -> validator.validateProductAttributes(schema, Map.of()))
                .isInstanceOf(DomainException.class)
                .satisfies(thrown -> {
                    DomainException exception = (DomainException) thrown;
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_ATTRIBUTES);
                    assertThat(exception.fieldErrors())
                            .extracting(DomainException.FieldError::field)
                            .contains("attributes.brand");
                });
    }

    @Test
    @DisplayName("Attribute không thuộc category bị từ chối")
    void rejectsUnmappedAttribute() {
        Map<String, Object> attributes = Map.of("brand", "Google", "unknown", "x");

        assertThatThrownBy(() -> validator.validateProductAttributes(schema, attributes))
                .isInstanceOf(DomainException.class)
                .satisfies(thrown -> assertThat(((DomainException) thrown).fieldErrors())
                        .extracting(DomainException.FieldError::field)
                        .contains("attributes.unknown"));
    }

    @Test
    @DisplayName("Variant attribute không được gửi ở cấp Product")
    void rejectsVariantAttributeOnProductLevel() {
        Map<String, Object> attributes = Map.of("brand", "Google", "color", "black");

        assertThatThrownBy(() -> validator.validateProductAttributes(schema, attributes))
                .isInstanceOf(DomainException.class)
                .satisfies(thrown -> assertThat(((DomainException) thrown).fieldErrors())
                        .extracting(DomainException.FieldError::message)
                        .contains("attribute is a variant attribute"));
    }

    @Test
    @DisplayName("Product attribute không được gửi ở cấp SKU")
    void rejectsProductAttributeOnVariantLevel() {
        Map<String, Object> variant = Map.of("color", "black", "brand", "Google");

        assertThatThrownBy(() -> validator.validateVariantAttributes(schema, variant))
                .isInstanceOf(DomainException.class)
                .satisfies(thrown -> assertThat(((DomainException) thrown).fieldErrors())
                        .extracting(DomainException.FieldError::message)
                        .contains("attribute is a product-level attribute"));
    }

    @Test
    @DisplayName("Sai kiểu dữ liệu bị từ chối theo value_type")
    void rejectsWrongValueType() {
        Map<String, Object> attributes = Map.of("brand", 42, "weight", "heavy");

        assertThatThrownBy(() -> validator.validateProductAttributes(schema, attributes))
                .isInstanceOf(DomainException.class)
                .satisfies(thrown -> assertThat(((DomainException) thrown).fieldErrors())
                        .extracting(DomainException.FieldError::message)
                        .contains("value must be a string", "value must be a number"));
    }

    @Test
    @DisplayName("SELECT chỉ nhận option code đã khai báo")
    void rejectsUnknownSelectOption() {
        assertThatThrownBy(() -> validator.validateVariantAttributes(schema, Map.of("color", "red")))
                .isInstanceOf(DomainException.class)
                .satisfies(thrown -> assertThat(((DomainException) thrown).fieldErrors())
                        .extracting(DomainException.FieldError::message)
                        .anyMatch(message -> message.contains("not a defined option")));
    }

    @Test
    @DisplayName("Category không có mapping thì mọi attribute đều là lạ")
    void emptySchemaRejectsAnyAttribute() {
        AttributeValidator.CategorySchema empty = new AttributeValidator.CategorySchema(Map.of());

        assertThatCode(() -> validator.validateProductAttributes(empty, Map.of()))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validateProductAttributes(empty, Map.of("brand", "Google")))
                .isInstanceOf(DomainException.class);
    }

    private AttributeValidator.MappedAttribute mapped(String code,
                                                      AttributeValueType type,
                                                      boolean required,
                                                      boolean variant,
                                                      Set<String> options) {
        return new AttributeValidator.MappedAttribute(UUID.randomUUID(), code, type, required, variant, options);
    }
}
