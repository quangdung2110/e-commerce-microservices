package com.ecommerce.checkout.service;

import com.ecommerce.checkout.api.dto.PublicDtos;
import com.ecommerce.checkout.client.CartClient;
import com.ecommerce.checkout.client.InventoryClient;
import com.ecommerce.checkout.client.ProductClient;
import com.ecommerce.checkout.client.PromotionClient;
import com.ecommerce.checkout.client.RequestContext;
import com.ecommerce.checkout.client.UserClient;
import com.ecommerce.checkout.config.CheckoutProperties;
import com.ecommerce.checkout.crypto.SecureIds;
import com.ecommerce.checkout.domain.CanonicalJson;
import com.ecommerce.checkout.domain.Hashes;
import com.ecommerce.checkout.domain.Snapshot;
import com.ecommerce.checkout.media.MediaUrls;
import com.ecommerce.checkout.observability.CheckoutMetrics;
import com.ecommerce.checkout.problem.ApiException;
import com.ecommerce.checkout.problem.Problem;
import com.ecommerce.checkout.problem.ProblemCode;
import com.ecommerce.checkout.resilience.CallResult;
import com.ecommerce.checkout.resilience.Dependency;
import com.ecommerce.checkout.resilience.DependencyGuard;
import com.ecommerce.checkout.store.SessionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * WF-01 Preview: builds the user's immutable quotation from the owners of the data
 * (architecture.md, section 3). Every dependency is required and fail-closed; a dependency failure
 * never becomes "no discount" or "enough stock".
 *
 * <pre>
 * peek   idempotent replay / in-flight guard                 (one Redis script)
 * Wave 1 Cart snapshot
 * Wave 2 User address snapshot  ||  Product GetBatchSkus
 * Wave 3 Inventory allocation preview  ||  Promotion calculation (only with a promotion_id)
 * Wave 4 one Lua script: guard, fence, session, supersede, pointer, replay
 * </pre>
 *
 * Preview has no side effect outside Redis, so it always follows the client's cancellation.
 */
@Service
public class PreviewService {

    private final DependencyGuard guard;
    private final CartClient carts;
    private final UserClient users;
    private final ProductClient products;
    private final InventoryClient inventory;
    private final PromotionClient promotions;
    private final SessionStore store;
    private final Codec codec;
    private final MediaUrls media;
    private final CheckoutProperties properties;
    private final Failures failures;
    private final CheckoutMetrics metrics;
    private final Clock clock;
    private final ObjectMapper mapper;

    public PreviewService(DependencyGuard guard, CartClient carts, UserClient users, ProductClient products,
                          InventoryClient inventory, PromotionClient promotions, SessionStore store, Codec codec,
                          MediaUrls media, CheckoutProperties properties, Failures failures, CheckoutMetrics metrics,
                          Clock clock, ObjectMapper mapper) {
        this.guard = guard;
        this.carts = carts;
        this.users = users;
        this.products = products;
        this.inventory = inventory;
        this.promotions = promotions;
        this.store = store;
        this.codec = codec;
        this.media = media;
        this.properties = properties;
        this.failures = failures;
        this.metrics = metrics;
        this.clock = clock;
        this.mapper = mapper;
    }

    /** All identifiers are lowercase canonical UUIDs; {@code previousToken} and {@code promotionId} may be null. */
    public record Command(String userId, String idempotencyKey, String previousToken, String addressId,
                          String promotionId) {
    }

    /** Result of Wave 2: the verified address and the quoted lines, sorted by UTF-8 {@code sku_id}. */
    private record Priced(UserClient.Address address, List<Snapshot.Item> items, long total) {
    }

    /** Result of Wave 3. */
    private record Allocated(InventoryClient.Allocatable plan, PromotionClient.Calculation calculation) {
    }

    /** @return the {@code CheckoutPreview} JSON; a replay returns the stored JSON unchanged */
    public Mono<String> preview(Command command, RequestContext context) {
        String fingerprint = fingerprint(command);
        return store.peekPreview(command.userId(), command.idempotencyKey(), fingerprint)
                .flatMap(peek -> switch (peek) {
                    case SessionStore.Peek.Replay replay -> {
                        metrics.previewCas("idempotent_replay");
                        yield Mono.just(codec.decryptPreviewResponse(replay.responseCipher()));
                    }
                    case SessionStore.Peek.Mismatch mismatch -> {
                        metrics.previewCas("idempotency_mismatch");
                        yield Mono.error(idempotencyReused());
                    }
                    case SessionStore.Peek.InFlight inFlight -> {
                        metrics.previewCas("in_flight_blocked");
                        yield Mono.error(inFlightProblem(inFlight.payloadCipher()));
                    }
                    case SessionStore.Peek.Proceed proceed -> build(command, context, fingerprint);
                });
    }

    // ------------------------------------------------------------------ waves

    private Mono<String> build(Command command, RequestContext context, String fingerprint) {
        return guard.call(Dependency.CART, context.deadline(), () -> carts.fetch(command.userId(), context),
                        cart -> cart.items().isEmpty())
                .flatMap(result -> {
                    if (!result.isUsable()) {
                        return Mono.error(failures.unavailable(result));
                    }
                    CartClient.Snapshot cart = result.value();
                    if (cart.items().isEmpty()) {
                        return Mono.error(new ApiException(ProblemCode.CART_EMPTY, "The cart has no items"));
                    }
                    metrics.cartLines(cart.items().size());
                    return wave2(command, context, cart)
                            .flatMap(priced -> wave3(command, context, priced)
                                    .flatMap(allocated -> wave4(command, context, fingerprint, cart, priced,
                                            allocated)));
                });
    }

    private Mono<Priced> wave2(Command command, RequestContext context, CartClient.Snapshot cart) {
        List<String> skuIds = cart.items().stream().map(CartClient.Line::skuId).toList();
        Mono<CallResult<Optional<UserClient.Address>>> address = guard.call(Dependency.USER, context.deadline(),
                () -> users.fetch(command.userId(), command.addressId(), context), Optional::isEmpty);
        Mono<CallResult<ProductClient.Batch>> product = guard.call(Dependency.PRODUCT, context.deadline(),
                () -> products.fetch(skuIds, context), batch -> !batch.unsaleable().isEmpty());
        return Mono.zip(address, product).flatMap(both -> {
            CallResult<Optional<UserClient.Address>> addressResult = both.getT1();
            CallResult<ProductClient.Batch> productResult = both.getT2();
            if (!addressResult.isUsable()) {
                return Mono.error(failures.unavailable(addressResult));
            }
            if (!productResult.isUsable()) {
                return Mono.error(failures.unavailable(productResult));
            }
            ProductClient.Batch batch = productResult.value();
            TreeSet<String> unavailable = new TreeSet<>(Hashes.SKU_ORDER);
            unavailable.addAll(batch.unsaleable());
            for (CartClient.Line line : cart.items()) {
                ProductClient.ProductSku sku = batch.active().get(line.skuId());
                if (sku != null && !sku.productId().equalsIgnoreCase(line.productId())) {
                    unavailable.add(line.skuId());
                }
            }
            if (!unavailable.isEmpty()) {
                return Mono.error(new ApiException(ProblemCode.SKU_UNAVAILABLE,
                        "Some products are no longer available", Map.of("sku_ids", List.copyOf(unavailable))));
            }
            if (addressResult.value().isEmpty()) {
                return Mono.error(new ApiException(ProblemCode.ADDRESS_NOT_FOUND,
                        "The delivery address does not exist"));
            }
            List<Snapshot.Item> items = new ArrayList<>();
            long total = 0;
            for (CartClient.Line line : cart.items()) {
                ProductClient.ProductSku sku = batch.active().get(line.skuId());
                Snapshot.Item item = new Snapshot.Item(line.skuId(), sku.productId(), line.skuId(), line.quantity(),
                        sku.price(), sku.listPrice(), sku.categoryIds(), sku.productName(), sku.variantAttributes(),
                        sku.imageStorageKey(), sku.catalogRevision());
                items.add(item);
                total = Math.addExact(total, item.lineTotal());
            }
            items.sort(Comparator.comparing(Snapshot.Item::skuId, Hashes.SKU_ORDER));
            UserClient.Address a = addressResult.value().get();
            return Mono.just(new Priced(a, List.copyOf(items), total));
        });
    }

    private Mono<Allocated> wave3(Command command, RequestContext context, Priced priced) {
        List<InventoryClient.Line> lines = priced.items().stream()
                .map(item -> new InventoryClient.Line(item.lineReference(), item.skuId(), item.quantity()))
                .toList();
        Mono<CallResult<InventoryClient.Outcome>> allocation = guard.call(Dependency.INVENTORY, context.deadline(),
                () -> inventory.preview(lines, context), outcome -> outcome instanceof InventoryClient.RejectAll);
        Mono<Optional<CallResult<PromotionClient.Calculation>>> promotion = command.promotionId() == null
                ? Mono.just(Optional.empty())
                : guard.call(Dependency.PROMOTION_CALCULATION, context.deadline(),
                        () -> promotions.calculate(command.userId(), command.promotionId(), priced.total(),
                                contextItems(priced.items()), context),
                        calculation -> !calculation.eligible())
                .map(Optional::of);
        return Mono.zip(allocation, promotion).flatMap(both -> {
            CallResult<InventoryClient.Outcome> allocationResult = both.getT1();
            Optional<CallResult<PromotionClient.Calculation>> promotionResult = both.getT2();
            if (!allocationResult.isUsable()) {
                return Mono.error(failures.unavailable(allocationResult));
            }
            if (promotionResult.isPresent() && !promotionResult.get().isUsable()) {
                return Mono.error(failures.unavailable(promotionResult.get()));
            }
            if (allocationResult.value() instanceof InventoryClient.RejectAll rejected) {
                List<Map<String, Object>> lines2 = rejected.rejectedLines().stream()
                        .map(line -> {
                            Map<String, Object> rejectedLine = new java.util.LinkedHashMap<>();
                            rejectedLine.put("sku_id", line.skuId());
                            rejectedLine.put("quantity", line.quantity());
                            return rejectedLine;
                        })
                        .toList();
                return Mono.error(new ApiException(ProblemCode.INSUFFICIENT_STOCK,
                        "Not enough stock for some products", Map.of("rejected_lines", lines2)));
            }
            PromotionClient.Calculation calculation = promotionResult.map(CallResult::value).orElse(null);
            if (calculation != null) {
                metrics.budgetGuard(calculation.budgetGuard());
                if (!calculation.eligible()) {
                    return Mono.error(new ApiException(ProblemCode.PROMOTION_INVALID,
                            "The voucher cannot be applied to this order",
                            Map.of("promotion_reason", PromotionReasons.map(calculation.reason()))));
                }
            }
            return Mono.just(new Allocated((InventoryClient.Allocatable) allocationResult.value(), calculation));
        });
    }

    private Mono<String> wave4(Command command, RequestContext context, String fingerprint, CartClient.Snapshot cart,
                               Priced priced, Allocated allocated) {
        long discount = allocated.calculation() == null ? 0 : allocated.calculation().discount();
        long revision = allocated.calculation() == null ? 0 : allocated.calculation().campaignRevision();
        long finalAmount = priced.total() - discount;
        if (finalAmount < 1) {
            return Mono.error(new ApiException(ProblemCode.FINAL_AMOUNT_NOT_PAYABLE,
                    "The amount to pay must be at least 1 VND"));
        }
        long now = clock.millis();
        long expiresAt = now + properties.lifecycle().quoteTtl().toMillis();
        String token = SecureIds.uuid();

        String itemsHash = Hashes.itemsHash(priced.items().stream()
                .map(item -> new Hashes.SkuQuantity(item.skuId(), item.quantity())).toList());
        String pricingHash = Hashes.pricingHash(priced.items().stream()
                .map(item -> new Hashes.SkuPrice(item.skuId(), item.unitPrice())).toList(), revision, discount);
        Snapshot.Promotion promotion = allocated.calculation() == null
                ? Snapshot.Promotion.none()
                : new Snapshot.Promotion(command.promotionId(), revision, discount);
        UserClient.Address a = priced.address();
        Snapshot snapshot = new Snapshot(Snapshot.SCHEMA_VERSION, command.userId(),
                new Snapshot.Address(a.addressId(), a.recipientName(), a.recipientPhone(), a.addressLine(),
                        a.provinceCode(), a.districtCode(), a.wardCode()),
                priced.items(),
                new Snapshot.Allocation(allocated.plan().allocationPlanId(), allocated.plan().lines().stream()
                        .map(line -> new Snapshot.AllocationLine(line.lineReference(), line.skuId(),
                                line.warehouseId(), line.quantity())).toList()),
                promotion, priced.total(), discount, finalAmount, itemsHash, pricingHash, now, expiresAt);

        String response = codec.write(toPublic(token, snapshot));
        return store.createPreview(command.userId(), command.idempotencyKey(), fingerprint, command.previousToken(),
                        token, codec.encryptSnapshot(snapshot), codec.encryptPreviewResponse(response), now, expiresAt,
                        codec.keyVersion())
                .flatMap(created -> switch (created) {
                    case SessionStore.Created.Done done -> {
                        metrics.previewCas(done.supersededPrevious() ? "superseded_previous" : "created");
                        yield Mono.just(response);
                    }
                    case SessionStore.Created.Replay replay -> {
                        metrics.previewCas("idempotent_replay");
                        yield Mono.just(codec.decryptPreviewResponse(replay.responseCipher()));
                    }
                    case SessionStore.Created.Mismatch mismatch -> {
                        metrics.previewCas("idempotency_mismatch");
                        yield Mono.error(idempotencyReused());
                    }
                    case SessionStore.Created.InFlight inFlight -> {
                        metrics.previewCas("in_flight_blocked");
                        yield Mono.error(inFlightProblem(inFlight.payloadCipher()));
                    }
                    case SessionStore.Created.ActiveChanged changed -> {
                        metrics.previewCas("active_changed");
                        yield Mono.error(new ApiException(ProblemCode.ACTIVE_CHECKOUT_CHANGED,
                                "The previous checkout is no longer the current one; start a new preview"));
                    }
                });
    }

    // ------------------------------------------------------------------ mapping

    /** The public view of a snapshot: no warehouse, allocation plan, category, revision or hash. */
    PublicDtos.CheckoutPreview toPublic(String token, Snapshot snapshot) {
        List<PublicDtos.PreviewItem> items = snapshot.items().stream()
                .map(item -> new PublicDtos.PreviewItem(item.lineReference(), item.productId(), item.skuId(),
                        item.productName(), item.variantAttributes(), media.displayUrl(item.imageStorageKey()),
                        item.quantity(), PublicDtos.Money.vnd(item.listUnitPrice()),
                        PublicDtos.Money.vnd(item.unitPrice()), PublicDtos.Money.vnd(item.lineTotal())))
                .toList();
        Snapshot.Address a = snapshot.address();
        return new PublicDtos.CheckoutPreview(token, Instant.ofEpochMilli(snapshot.expiresAtMs()),
                new PublicDtos.AddressSnapshot(a.addressId(), a.recipientName(), a.recipientPhone(), a.addressLine(),
                        a.provinceCode(), a.districtCode(), a.wardCode()),
                items,
                new PublicDtos.PromotionPreview(snapshot.promotion().promotionId(),
                        snapshot.promotion().campaignRevision(),
                        PublicDtos.Money.vnd(snapshot.promotion().discount())),
                new PublicDtos.Pricing(PublicDtos.Money.vnd(snapshot.totalAmount()),
                        PublicDtos.Money.vnd(snapshot.totalDiscount()), PublicDtos.Money.vnd(snapshot.finalAmount())));
    }

    static List<PromotionClient.ContextItem> contextItems(List<Snapshot.Item> items) {
        return items.stream()
                .map(item -> new PromotionClient.ContextItem(item.productId(), item.skuId(), item.quantity(),
                        item.unitPrice(), item.categoryIds()))
                .toList();
    }

    /**
     * Same {@code Idempotency-Key} with a different body is {@code 409}. The fingerprint covers exactly
     * the fields the client may send; an omitted value and {@code null} are the same request.
     */
    String fingerprint(Command command) {
        ObjectNode node = mapper.createObjectNode();
        node.put("delivery_address_id", command.addressId());
        node.put("previous_checkout_token", command.previousToken());
        node.put("promotion_id", command.promotionId());
        return CanonicalJson.digest(node);
    }

    private ApiException idempotencyReused() {
        return new ApiException(ProblemCode.IDEMPOTENCY_KEY_REUSED,
                "The Idempotency-Key was already used with a different request");
    }

    /** {@code 409 SUBMISSION_IN_PROGRESS} with the {@code status_url} of the token being submitted. */
    private ApiException inFlightProblem(String payloadCipher) {
        if (payloadCipher == null || payloadCipher.isEmpty()) {
            // The pointer says a submission is running but its payload is gone: it cannot be located.
            return failures.unavailable(ProblemCode.CHECKOUT_UNAVAILABLE);
        }
        String statusUrl = "/api/v1/checkout/submissions/" + codec.tokenOf(payloadCipher);
        return new ApiException(Problem.of(ProblemCode.SUBMISSION_IN_PROGRESS,
                "A submission is still in progress; follow status_url instead of creating a new quotation",
                Map.of("status_url", statusUrl)));
    }
}
