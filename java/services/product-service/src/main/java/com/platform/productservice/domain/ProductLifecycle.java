package com.platform.productservice.domain;

import com.platform.productservice.common.DomainException;
import com.platform.productservice.common.ErrorCode;
import com.platform.productservice.domain.enums.ProductStatus;

/**
 * State machine của Product (docs/product/domain.md — State machine).
 *
 * <pre>
 * — -> DRAFT      : admin tạo
 * DRAFT  -> ACTIVE : activate, cần tối thiểu một SKU ACTIVE
 * ACTIVE -> HIDDEN : hide
 * HIDDEN -> ACTIVE : show
 * </pre>
 *
 * <p>Không có transition tự động theo tồn kho (PRODUCT-BR-009) và {@code DRAFT}
 * không bao giờ là target của transition.
 */
public final class ProductLifecycle {

    private ProductLifecycle() {
    }

    /**
     * @throws DomainException {@code INVALID_STATE_TRANSITION} khi command không
     *         hợp lệ cho status hiện tại, kể cả khi target trùng status hiện tại
     */
    public static void assertTransitionAllowed(ProductStatus current, ProductStatus target) {
        if (target == ProductStatus.DRAFT) {
            throw DomainException.of(ErrorCode.INVALID_STATE_TRANSITION,
                    "DRAFT is only an initial state, not a transition target");
        }
        if (current == target) {
            throw DomainException.of(ErrorCode.INVALID_STATE_TRANSITION,
                    "Product is already " + current);
        }
        boolean allowed = switch (target) {
            case ACTIVE -> current == ProductStatus.DRAFT || current == ProductStatus.HIDDEN;
            case HIDDEN -> current == ProductStatus.ACTIVE;
            case DRAFT -> false;
        };
        if (!allowed) {
            throw DomainException.of(ErrorCode.INVALID_STATE_TRANSITION,
                    "Cannot move product from " + current + " to " + target);
        }
    }
}
