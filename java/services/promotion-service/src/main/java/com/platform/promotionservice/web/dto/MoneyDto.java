package com.platform.promotionservice.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.platform.promotionservice.common.DomainException;
import com.platform.promotionservice.common.ErrorCode;

import java.util.List;

/**
 * Money trên wire: {@code {amount_minor, currency}}; v1 chỉ chấp nhận VND.
 * Storage/Redis dùng integer VND, adapter map hai chiều (data.md §1).
 */
public record MoneyDto(@JsonProperty("amount_minor") long amountMinor,
                       @JsonProperty("currency") String currency) {

    public static final String VND = "VND";

    public static MoneyDto vnd(long amountMinor) {
        return new MoneyDto(amountMinor, VND);
    }

    /** Trả về amount VND sau khi kiểm currency và dấu; dùng cho mọi request money. */
    public long requireVnd(String field, long minimum) {
        if (!VND.equals(currency)) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, field + " must use currency VND",
                    List.of(new DomainException.FieldError(field + ".currency", "only VND is supported")));
        }
        if (amountMinor < minimum) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, field + " must be >= " + minimum,
                    List.of(new DomainException.FieldError(field + ".amount_minor", "must be >= " + minimum)));
        }
        return amountMinor;
    }

    public static Long nullableVnd(MoneyDto money, String field, long minimum) {
        return money == null ? null : money.requireVnd(field, minimum);
    }

    public static MoneyDto ofNullable(Long amountVnd) {
        return amountVnd == null ? null : vnd(amountVnd);
    }
}
