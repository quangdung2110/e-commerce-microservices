package com.platform.promotionservice.service;

import com.platform.promotionservice.common.DomainException;
import com.platform.promotionservice.common.ErrorCode;
import com.platform.promotionservice.domain.rule.RuleFields;
import com.platform.promotionservice.web.dto.CheckoutDtos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Map CheckoutContext + trusted identity sang rule facts của rule_schema_version 1.
 * {@code user.id}/{@code user.tier} không lấy từ facts do client gửi; facts chỉ được
 * bổ sung field allowlisted mà trusted mapping chưa có.
 */
public final class RuleFactsBuilder {

    private RuleFactsBuilder() {
    }

    public record Context(long subtotalVnd, Map<String, Object> facts) {
    }

    public static final String CHECKOUT_CHANNEL = "CHECKOUT";

    /**
     * Context của calculate, voucher options và reserve là {@code TrustedCheckoutContext} (OpenAPI): caller là
     * service nên channel luôn {@code CHECKOUT}, không phải giá trị tuỳ ý như context customer discovery.
     */
    public static void requireCheckoutChannel(CheckoutDtos.CheckoutContext context) {
        if (!CHECKOUT_CHANNEL.equals(context.checkoutChannel())) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, "context.checkout_channel must be CHECKOUT",
                    List.of(new DomainException.FieldError("context.checkout_channel", "must be CHECKOUT")));
        }
    }

    public static Context build(CheckoutDtos.CheckoutContext context, UUID userId, String userTier) {
        long subtotal = context.subtotal().requireVnd("context.subtotal", 0);
        String paymentMethod = context.paymentMethod() == null ? CheckoutDtos.PAYMENT_METHOD_STRIPE : context.paymentMethod();
        if (!CheckoutDtos.PAYMENT_METHOD_STRIPE.equals(paymentMethod)) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, "context.payment_method must be STRIPE",
                    List.of(new DomainException.FieldError("context.payment_method", "only STRIPE is supported")));
        }
        List<String> categoryIds = new ArrayList<>();
        for (CheckoutDtos.CheckoutItem item : context.items()) {
            item.unitPrice().requireVnd("context.items[].unit_price", 0);
            categoryIds.addAll(item.categoryIds());
        }

        Map<String, Object> facts = new HashMap<>();
        facts.put(RuleFields.CART_TOTAL_AMOUNT, subtotal);
        facts.put(RuleFields.CART_PAYMENT_METHOD, paymentMethod);
        facts.put(RuleFields.CART_CHECKOUT_CHANNEL, context.checkoutChannel());
        facts.put(RuleFields.CART_ITEMS_CATEGORY_ID, List.copyOf(categoryIds));
        if (userId != null) {
            facts.put(RuleFields.USER_ID, userId.toString());
        }
        if (userTier != null && !userTier.isBlank()) {
            facts.put(RuleFields.USER_TIER, userTier);
        }
        if (context.facts() != null) {
            context.facts().forEach((key, value) -> {
                if (RuleFields.CLIENT_FACT_OVERRIDABLE.contains(key) && value != null && !facts.containsKey(key)) {
                    facts.put(key, value);
                }
            });
        }
        return new Context(subtotal, Map.copyOf(facts));
    }
}
