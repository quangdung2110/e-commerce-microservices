package com.platform.productservice.domain;

import com.platform.productservice.common.DomainException;
import com.platform.productservice.common.ErrorCode;

import java.util.regex.Pattern;

/**
 * Định dạng slug Product (PRODUCT-BR-013): kebab-case ASCII chữ thường, dài 1–180.
 *
 * <p>Product không tự chuẩn hóa slug admin gửi. Product Detail BFF dùng cùng pattern
 * để trả {@code 404} mà không gọi Product, nên slug nào Product chấp nhận cũng phải
 * đi qua được pattern đó. CHECK {@code chk_products_slug_format} trong database là
 * lớp phòng thủ cuối cho mọi đường ghi.
 */
public final class ProductSlug {

    /** Trùng pattern {@code Slug} trong contracts/http/product/v1/openapi.yaml. */
    public static final String PATTERN = "^[a-z0-9]+(?:-[a-z0-9]+)*$";

    public static final int MAX_LENGTH = 180;

    private static final Pattern COMPILED = Pattern.compile(PATTERN);

    private ProductSlug() {
    }

    public static boolean isValid(String slug) {
        return slug != null && slug.length() <= MAX_LENGTH && COMPILED.matcher(slug).matches();
    }

    /**
     * Validate ở service layer để quy tắc giữ nguyên với mọi caller, không chỉ HTTP;
     * {@code @Pattern} trên DTO chỉ chặn sớm ở controller.
     */
    public static void require(String slug) {
        if (!isValid(slug)) {
            throw DomainException.of(ErrorCode.INVALID_REQUEST,
                    "Product slug must match " + PATTERN + " and be at most "
                            + MAX_LENGTH + " characters");
        }
    }
}
