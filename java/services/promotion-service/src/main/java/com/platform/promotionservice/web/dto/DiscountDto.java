package com.platform.promotionservice.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.platform.promotionservice.common.DomainException;
import com.platform.promotionservice.common.ErrorCode;
import com.platform.promotionservice.domain.enums.DiscountType;

import java.util.List;

/** Discount discriminated theo {@code type} (openapi Discount). */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = DiscountDto.Percentage.class, name = "PERCENTAGE"),
        @JsonSubTypes.Type(value = DiscountDto.Fixed.class, name = "FIXED_VND")
})
public sealed interface DiscountDto permits DiscountDto.Percentage, DiscountDto.Fixed {

    String type();

    /** Storage shape: (discount_type, discount_value, max_discount_vnd). */
    record Storage(DiscountType type, long value, Long maxDiscountVnd) {
    }

    Storage toStorage(String field);

    static DiscountDto fromStorage(DiscountType type, long value, Long maxDiscountVnd) {
        return switch (type) {
            case PERCENTAGE -> new Percentage("PERCENTAGE", (int) value, MoneyDto.ofNullable(maxDiscountVnd));
            case FIXED_VND -> new Fixed("FIXED_VND", MoneyDto.vnd(value));
        };
    }

    record Percentage(@JsonProperty("type") String type,
                      @JsonProperty("value_basis_points") Integer valueBasisPoints,
                      @JsonProperty("max_discount") MoneyDto maxDiscount) implements DiscountDto {

        @Override
        public Storage toStorage(String field) {
            if (valueBasisPoints == null || valueBasisPoints < 1 || valueBasisPoints > 10_000) {
                throw new DomainException(ErrorCode.INVALID_REQUEST, field + ".value_basis_points must be within [1,10000]",
                        List.of(new DomainException.FieldError(field + ".value_basis_points", "must be within [1,10000]")));
            }
            return new Storage(DiscountType.PERCENTAGE, valueBasisPoints,
                    MoneyDto.nullableVnd(maxDiscount, field + ".max_discount", 1));
        }
    }

    record Fixed(@JsonProperty("type") String type,
                 @JsonProperty("value") MoneyDto value) implements DiscountDto {

        @Override
        public Storage toStorage(String field) {
            if (value == null) {
                throw new DomainException(ErrorCode.INVALID_REQUEST, field + ".value is required",
                        List.of(new DomainException.FieldError(field + ".value", "is required")));
            }
            return new Storage(DiscountType.FIXED_VND, value.requireVnd(field + ".value", 1), null);
        }
    }
}
