package com.ecommerce.checkout.service;

import com.ecommerce.checkout.client.CartClient;
import com.ecommerce.checkout.client.ProductClient;
import com.ecommerce.checkout.client.PromotionClient;
import com.ecommerce.checkout.client.RequestContext;
import com.ecommerce.checkout.domain.Hashes;
import com.ecommerce.checkout.domain.Snapshot;
import com.ecommerce.checkout.problem.Problem;
import com.ecommerce.checkout.problem.ProblemCode;
import com.ecommerce.checkout.resilience.CallResult;
import com.ecommerce.checkout.resilience.Dependency;
import com.ecommerce.checkout.resilience.DependencyGuard;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Revalidation at Submit (WF-02 step 4): is the quotation still true? Cart and Product are read in
 * parallel, then Promotion with the current price. Inventory is not revalidated here: Order Reserve
 * checks exactly the plan.
 *
 * <p>Material changes are quantity, saleability, selling price, {@code category_ids} and the
 * Promotion result; names, images and the list price are not (CHK-BR-013). All changes are reported in
 * {@code changed_fields}; the code follows the priority CART_CHANGED, SKU_UNAVAILABLE, PRICE_CHANGED,
 * PROMOTION_CHANGED. The Promotion check evaluates the quotation's own quantities at current prices,
 * and is skipped when a SKU is unsaleable because no discount can be computed for it.
 */
@Component
public class Revalidator {

    /** Outcome of a revalidation. {@code Failed} is an infrastructure failure, never a change. */
    public sealed interface Result permits Unchanged, Changed, Failed {
    }

    public record Unchanged() implements Result {
    }

    public record Changed(Problem problem) implements Result {
    }

    public record Failed(CallResult<?> failure) implements Result {
    }

    private final DependencyGuard guard;
    private final CartClient carts;
    private final ProductClient products;
    private final PromotionClient promotions;

    public Revalidator(DependencyGuard guard, CartClient carts, ProductClient products, PromotionClient promotions) {
        this.guard = guard;
        this.carts = carts;
        this.products = products;
        this.promotions = promotions;
    }

    public Mono<Result> revalidate(Snapshot snapshot, RequestContext context) {
        List<String> skuIds = snapshot.items().stream().map(Snapshot.Item::skuId).toList();
        Mono<CallResult<CartClient.Snapshot>> cart = guard.call(Dependency.CART, context.deadline(),
                () -> carts.fetch(snapshot.userId(), context));
        Mono<CallResult<ProductClient.Batch>> product = guard.call(Dependency.PRODUCT, context.deadline(),
                () -> products.fetch(skuIds, context), batch -> !batch.unsaleable().isEmpty());
        return Mono.zip(cart, product).flatMap(both -> {
            CallResult<CartClient.Snapshot> cartResult = both.getT1();
            CallResult<ProductClient.Batch> productResult = both.getT2();
            if (!cartResult.isUsable()) {
                return Mono.just((Result) new Failed(cartResult));
            }
            if (!productResult.isUsable()) {
                return Mono.just((Result) new Failed(productResult));
            }
            return compare(snapshot, cartResult.value(), productResult.value(), context);
        });
    }

    private Mono<Result> compare(Snapshot snapshot, CartClient.Snapshot cart, ProductClient.Batch batch,
                                 RequestContext context) {
        boolean cartChanged = !Hashes.itemsHash(cart.items().stream()
                .map(line -> new Hashes.SkuQuantity(line.skuId(), line.quantity())).toList())
                .equals(snapshot.itemsHash());

        TreeSet<String> unavailable = new TreeSet<>(Hashes.SKU_ORDER);
        unavailable.addAll(batch.unsaleable());
        boolean priceChanged = false;
        for (Snapshot.Item item : snapshot.items()) {
            ProductClient.ProductSku sku = batch.active().get(item.skuId());
            if (sku == null) {
                unavailable.add(item.skuId());
                continue;
            }
            if (!sku.productId().equalsIgnoreCase(item.productId())) {
                unavailable.add(item.skuId());
                continue;
            }
            if (sku.price() != item.unitPrice() || !sameCategories(sku.categoryIds(), item.categoryIds())) {
                priceChanged = true;
            }
        }

        boolean finalCartChanged = cartChanged;
        boolean finalPriceChanged = priceChanged;
        if (!snapshot.hasPromotion() || !unavailable.isEmpty()) {
            return Mono.just(assemble(finalCartChanged, unavailable, finalPriceChanged, false, null));
        }
        return recalculate(snapshot, batch, context).map(outcome -> {
            if (outcome.failure() != null) {
                return (Result) new Failed(outcome.failure());
            }
            return assemble(finalCartChanged, unavailable, finalPriceChanged, outcome.changed(), outcome.reason());
        });
    }

    /** Promotion for the quotation's quantities at the current Product price and categories. */
    private Mono<PromotionOutcome> recalculate(Snapshot snapshot, ProductClient.Batch batch, RequestContext context) {
        List<PromotionClient.ContextItem> items = new ArrayList<>();
        long subtotal = 0;
        for (Snapshot.Item item : snapshot.items()) {
            ProductClient.ProductSku sku = batch.active().get(item.skuId());
            items.add(new PromotionClient.ContextItem(sku.productId(), item.skuId(), item.quantity(), sku.price(),
                    sku.categoryIds()));
            subtotal = Math.addExact(subtotal, Math.multiplyExact(sku.price(), (long) item.quantity()));
        }
        long total = subtotal;
        return guard.call(Dependency.PROMOTION_CALCULATION, context.deadline(),
                        () -> promotions.calculate(snapshot.userId(), snapshot.promotion().promotionId(), total, items,
                                context),
                        calculation -> !calculation.eligible())
                .map(result -> {
                    if (!result.isUsable()) {
                        return new PromotionOutcome(result, false, null);
                    }
                    PromotionClient.Calculation calculation = result.value();
                    boolean changed = !calculation.eligible()
                            || calculation.discount() != snapshot.promotion().discount()
                            || calculation.campaignRevision() == null
                            || calculation.campaignRevision() != snapshot.promotion().campaignRevision();
                    return new PromotionOutcome(null, changed, PromotionReasons.map(calculation.reason()));
                });
    }

    private record PromotionOutcome(CallResult<?> failure, boolean changed, String reason) {
    }

    private Result assemble(boolean cart, Set<String> unavailable, boolean price, boolean promotion,
                            String promotionReason) {
        List<String> fields = new ArrayList<>();
        if (cart) {
            fields.add("CART");
        }
        if (!unavailable.isEmpty()) {
            fields.add("AVAILABILITY");
        }
        if (price) {
            fields.add("PRICE");
        }
        if (promotion) {
            fields.add("PROMOTION");
        }
        if (fields.isEmpty()) {
            return new Unchanged();
        }
        Map<String, Object> base = Map.of("changed_fields", List.copyOf(fields));
        Problem problem;
        if (cart) {
            problem = Problem.of(ProblemCode.CART_CHANGED, "The cart changed since this quotation was created", base);
        } else if (!unavailable.isEmpty()) {
            problem = Problem.of(ProblemCode.SKU_UNAVAILABLE, "Some products are no longer available", Map.of(
                    "changed_fields", List.copyOf(fields), "sku_ids", List.copyOf(unavailable))).withStatus(409);
        } else if (price) {
            problem = Problem.of(ProblemCode.PRICE_CHANGED, "A price or category changed since this quotation was created",
                    base);
        } else {
            problem = Problem.of(ProblemCode.PROMOTION_CHANGED, "The voucher result changed since this quotation was created",
                    promotionReason == null ? base : Map.of("changed_fields", List.copyOf(fields),
                            "promotion_reason", promotionReason));
        }
        return new Changed(problem);
    }

    private static boolean sameCategories(List<String> current, List<String> quoted) {
        Set<String> a = new HashSet<>(current);
        return a.size() == quoted.size() && a.containsAll(quoted);
    }
}
