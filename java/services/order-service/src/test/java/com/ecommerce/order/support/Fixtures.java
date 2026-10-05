package com.ecommerce.order.support;

import com.ecommerce.order.domain.CheckoutRules;
import com.ecommerce.order.web.dto.CheckoutDtos.AddressSnapshot;
import com.ecommerce.order.web.dto.CheckoutDtos.AllocationPlan;
import com.ecommerce.order.web.dto.CheckoutDtos.AllocationPlanLine;
import com.ecommerce.order.web.dto.CheckoutDtos.CheckoutItem;
import com.ecommerce.order.web.dto.CheckoutDtos.CheckoutOrderSubmissionRequest;
import com.ecommerce.order.web.dto.CheckoutDtos.PricingSnapshot;
import com.ecommerce.order.web.dto.CheckoutDtos.PromotionSnapshot;
import com.ecommerce.order.web.dto.CheckoutDtos.VndMoney;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Synthetic Checkout snapshots (fake UUIDs/phones/addresses; never production PII). */
public final class Fixtures {

    public static final String USER = "11111111-1111-4111-8111-111111111111";

    public record LineSpec(String sku, int quantity, long unitPrice, String warehouse, String name, String variant) {
    }

    private Fixtures() {
    }

    /** A×2 @300k, B×1 @1000k (other warehouse), C×3 @100k. */
    public static List<LineSpec> defaultLines() {
        return List.of(new LineSpec("SKU-A", 2, 300_000, "WH-HN", "Áo thun cổ tròn", "Đỏ / M"),
                new LineSpec("SKU-B", 1, 1_000_000, "WH-HCM", "Giày chạy bộ", null),
                new LineSpec("SKU-C", 3, 100_000, "WH-HN", "Bình nước thể thao", "500ml"));
    }

    public static CheckoutOrderSubmissionRequest submission(List<LineSpec> lines, long discount, String promotionId) {
        return submission(UUID.randomUUID().toString(), USER, lines, discount, promotionId);
    }

    public static CheckoutOrderSubmissionRequest submission(String token, String user, List<LineSpec> lines,
                                                            long discount, String promotionId) {
        List<CheckoutItem> items = new ArrayList<>();
        List<AllocationPlanLine> plan = new ArrayList<>();
        long total = 0;
        for (LineSpec line : lines) {
            items.add(new CheckoutItem(line.sku(), UUID.nameUUIDFromBytes(line.sku().getBytes()).toString(), line.sku(),
                    line.quantity(), vnd(line.unitPrice()), List.of("cat-root", "cat-" + line.sku().toLowerCase()),
                    line.name(), "https://cdn.example.test/" + line.sku() + ".jpg", Map.of("color", "red"),
                    line.variant()));
            plan.add(new AllocationPlanLine(line.sku(), line.sku(), line.warehouse(), line.quantity()));
            total += line.unitPrice() * line.quantity();
        }
        List<PromotionSnapshot> promotions = promotionId == null ? List.of()
                : List.of(new PromotionSnapshot(promotionId, vnd(discount)));
        long revision = promotionId == null ? 0 : 7;
        CheckoutOrderSubmissionRequest draft = new CheckoutOrderSubmissionRequest(token, user, "sha256:" + "0".repeat(64),
                "sha256:" + "0".repeat(64), revision, false, address(), items,
                new PricingSnapshot(vnd(total), vnd(discount), vnd(total - discount)), promotions,
                new AllocationPlan("plan-" + token, plan));
        return new CheckoutOrderSubmissionRequest(token, user, CheckoutRules.pricingHash(draft),
                CheckoutRules.itemsHash(draft), revision, false, address(), items, draft.pricing(), promotions,
                draft.allocationPlan());
    }

    public static AddressSnapshot address() {
        return new AddressSnapshot("Nguyễn Văn Test", "0900000000", "1 Đường Thử Nghiệm", "79", "760", "26734",
                "Hồ Chí Minh", "Quận 1", "Phường Bến Nghé");
    }

    public static CheckoutOrderSubmissionRequest withPricingHash(CheckoutOrderSubmissionRequest r, String hash) {
        return new CheckoutOrderSubmissionRequest(r.checkoutToken(), r.userId(), hash, r.itemsHash(),
                r.promotionCampaignRevision(), r.userAckDegradedPricing(), r.deliveryAddressSnapshot(), r.items(),
                r.pricing(), r.promotions(), r.allocationPlan());
    }

    public static CheckoutOrderSubmissionRequest withRevision(CheckoutOrderSubmissionRequest r, long revision) {
        return new CheckoutOrderSubmissionRequest(r.checkoutToken(), r.userId(), r.pricingHash(), r.itemsHash(), revision,
                r.userAckDegradedPricing(), r.deliveryAddressSnapshot(), r.items(), r.pricing(), r.promotions(),
                r.allocationPlan());
    }

    public static CheckoutOrderSubmissionRequest withFinal(CheckoutOrderSubmissionRequest r, long delta) {
        PricingSnapshot p = r.pricing();
        return new CheckoutOrderSubmissionRequest(r.checkoutToken(), r.userId(), r.pricingHash(), r.itemsHash(),
                r.promotionCampaignRevision(), r.userAckDegradedPricing(), r.deliveryAddressSnapshot(), r.items(),
                new PricingSnapshot(p.totalAmount(), p.totalDiscount(), vnd(p.finalAmount().amountMinor() + delta)),
                r.promotions(), r.allocationPlan());
    }

    public static VndMoney vnd(long amount) {
        return new VndMoney(amount, "VND");
    }
}
