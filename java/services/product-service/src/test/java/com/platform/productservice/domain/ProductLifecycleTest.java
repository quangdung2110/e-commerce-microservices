package com.platform.productservice.domain;

import com.platform.productservice.common.DomainException;
import com.platform.productservice.common.ErrorCode;
import com.platform.productservice.domain.enums.ProductStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** State machine admin-only của Product (docs/product/domain.md). */
class ProductLifecycleTest {

    @ParameterizedTest
    @CsvSource({"DRAFT,ACTIVE", "ACTIVE,HIDDEN", "HIDDEN,ACTIVE"})
    @DisplayName("Transition hợp lệ được chấp nhận")
    void allowsValidTransitions(ProductStatus current, ProductStatus target) {
        assertThatCode(() -> ProductLifecycle.assertTransitionAllowed(current, target))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({"DRAFT,HIDDEN", "DRAFT,DRAFT", "ACTIVE,DRAFT", "HIDDEN,DRAFT", "HIDDEN,HIDDEN", "ACTIVE,ACTIVE"})
    @DisplayName("Transition không hợp lệ trả INVALID_STATE_TRANSITION")
    void rejectsInvalidTransitions(ProductStatus current, ProductStatus target) {
        assertThatThrownBy(() -> ProductLifecycle.assertTransitionAllowed(current, target))
                .isInstanceOf(DomainException.class)
                .extracting(exception -> ((DomainException) exception).errorCode())
                .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
    }

    @Test
    @DisplayName("Gửi lại đúng status hiện tại là lỗi, không phải no-op")
    void sameStatusIsAConflictNotANoop() {
        assertThatThrownBy(() -> ProductLifecycle.assertTransitionAllowed(ProductStatus.ACTIVE, ProductStatus.ACTIVE))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("already ACTIVE");
    }
}
