package com.ecommerce.checkout;

import com.ecommerce.checkout.support.ContractSchemas;
import com.ecommerce.checkout.support.Fixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every WireMock fixture must be valid against the contract of the provider it imitates; otherwise the
 * tests of Checkout only prove that Checkout understands a Cart, Inventory, Promotion or Order that does
 * not exist (development.md, "Contract tests").
 */
class ProviderFixturesContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Cart and User fixtures match their internal checkout contracts")
    void cartAndUser() {
        assertThat(ContractSchemas.validate(ContractSchemas.CART, "CheckoutCartSnapshot", tree(Fixtures.cart())))
                .isEmpty();
        assertThat(ContractSchemas.validate(ContractSchemas.USER, "CheckoutAddressSnapshot", tree(Fixtures.address())))
                .isEmpty();
    }

    @Test
    @DisplayName("Inventory fixtures match AllocationPreviewResponse for ALLOCATABLE and REJECT_ALL")
    void inventory() {
        assertThat(ContractSchemas.validate(ContractSchemas.INVENTORY, "AllocationPreviewResponse",
                tree(Fixtures.allocatable()))).isEmpty();
        assertThat(ContractSchemas.validate(ContractSchemas.INVENTORY, "AllocationPreviewResponse",
                tree(Fixtures.rejectAll("sku-1", 2)))).isEmpty();
    }

    @Test
    @DisplayName("Promotion fixtures match the calculation result and the voucher option list")
    void promotion() {
        assertThat(ContractSchemas.validate(ContractSchemas.PROMOTION, "DiscountCalculationResult",
                tree(Fixtures.eligibleCalculation()))).isEmpty();
        assertThat(ContractSchemas.validate(ContractSchemas.PROMOTION, "DiscountCalculationResult",
                tree(Fixtures.calculation(false, "VOUCHER_EXPIRED", Fixtures.TOTAL, 0, null)))).isEmpty();
        assertThat(ContractSchemas.validate(ContractSchemas.PROMOTION, "VoucherOptionList", tree(
                Fixtures.voucherOptions(List.of(
                        Fixtures.voucherOption("promo-1", "WALLET", "PUBLIC_SAVE", true, "ELIGIBLE", 25_000, 3,
                                Fixtures.percentage(1000, null)),
                        Fixtures.voucherOption("promo-2", "PUBLIC", "LIMITED_CLAIM", true, "ELIGIBLE", 20_000, 5,
                                Fixtures.fixed(20_000))))))).isEmpty();
    }

    @Test
    @DisplayName("Order fixtures match CheckoutOrderReady, CheckoutOrderSubmissionStatus and the recalculation problem")
    void order() {
        String token = "9b2c1d1e-0000-4000-8000-000000000001";
        assertThat(ContractSchemas.validate(ContractSchemas.ORDER, "CheckoutOrderReady",
                tree(Fixtures.orderReady(token, true, "secret")))).isEmpty();
        assertThat(ContractSchemas.validate(ContractSchemas.ORDER, "CheckoutOrderReady",
                tree(Fixtures.orderReady(token, false, null)))).isEmpty();
        assertThat(ContractSchemas.validate(ContractSchemas.ORDER, "CheckoutOrderSubmissionStatus",
                tree(Fixtures.orderStatus(token, "PAYMENT_READY", Fixtures.payment(true, "secret"), null)))).isEmpty();
        assertThat(ContractSchemas.validate(ContractSchemas.ORDER, "CheckoutOrderSubmissionStatus",
                tree(Fixtures.orderStatus(token, "FAILED", null, Fixtures.problem(409, "PAYMENT_INITIATION_REJECTED")))))
                .isEmpty();
        assertThat(ContractSchemas.validate(ContractSchemas.ORDER, "PromotionRecalculationProblem",
                tree(Fixtures.recalculationProblem()))).isEmpty();
    }

    private JsonNode tree(Object value) {
        return mapper.valueToTree(value);
    }
}
