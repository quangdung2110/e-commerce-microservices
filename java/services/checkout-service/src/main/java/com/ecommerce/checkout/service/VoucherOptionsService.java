package com.ecommerce.checkout.service;

import com.ecommerce.checkout.api.dto.PublicDtos;
import com.ecommerce.checkout.client.PromotionClient;
import com.ecommerce.checkout.client.RequestContext;
import com.ecommerce.checkout.config.CheckoutProperties;
import com.ecommerce.checkout.domain.Snapshot;
import com.ecommerce.checkout.observability.CheckoutMetrics;
import com.ecommerce.checkout.observability.Spans;
import com.ecommerce.checkout.problem.ApiException;
import com.ecommerce.checkout.problem.Problem;
import com.ecommerce.checkout.problem.ProblemCode;
import com.ecommerce.checkout.resilience.Dependency;
import com.ecommerce.checkout.resilience.DependencyGuard;
import com.ecommerce.checkout.store.SessionStore;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * WF-08 voucher tray (ADR-017): a read-only composition over the quotation of one token. The trusted
 * Promotion context is built from the token's own snapshot (Product price and categories, Cart
 * quantities, subtotal), so the tray agrees with the quotation without calling Cart or Product again.
 * The result is advisory: applying a voucher always means a new Preview with a {@code promotion_id}.
 * A Promotion failure is {@code 503 VOUCHER_OPTIONS_UNAVAILABLE} and never touches Preview or Submit.
 */
@Service
public class VoucherOptionsService {

    private final SessionStore store;
    private final Codec codec;
    private final DependencyGuard guard;
    private final PromotionClient promotions;
    private final Failures failures;
    private final CheckoutProperties properties;
    private final CheckoutMetrics metrics;
    private final Spans spans;
    private final Clock clock;

    public VoucherOptionsService(SessionStore store, Codec codec, DependencyGuard guard, PromotionClient promotions,
                                 Failures failures, CheckoutProperties properties, CheckoutMetrics metrics,
                                 Spans spans, Clock clock) {
        this.store = store;
        this.codec = codec;
        this.guard = guard;
        this.promotions = promotions;
        this.failures = failures;
        this.properties = properties;
        this.metrics = metrics;
        this.spans = spans;
        this.clock = clock;
    }

    public Mono<PublicDtos.VoucherOptions> list(String userId, String token, RequestContext context) {
        if (!properties.voucherOptionsEnabled()) {
            // Disabled until Promotion ships the operation: do not call it (operations.md, Cau hinh).
            return Mono.error(failures.unavailable(ProblemCode.VOUCHER_OPTIONS_UNAVAILABLE));
        }
        return spans.in("checkout.voucher_options", store.findControl(token).flatMap(found -> {
            if (found.isEmpty() || !found.get().userId().equals(userId)) {
                return alreadySubmittedOrMissing(userId, token);
            }
            SessionStore.Control control = found.get();
            switch (control.state()) {
                case SUPERSEDED:
                    return Mono.<PublicDtos.VoucherOptions>error(new ApiException(ProblemCode.CHECKOUT_SUPERSEDED,
                            "A newer quotation replaced this one; start a new preview"));
                case PREVIEWED:
                    if (clock.millis() >= control.expiresAtMs()) {
                        return Mono.<PublicDtos.VoucherOptions>error(new ApiException(ProblemCode.CHECKOUT_EXPIRED,
                                "The quotation expired; start a new preview"));
                    }
                    return options(control, userId, token, context);
                default:
                    return Mono.<PublicDtos.VoucherOptions>error(alreadySubmitted(token));
            }
        }));
    }

    /** A record that is gone may still have a terminal result: that token was submitted, not unknown. */
    private Mono<PublicDtos.VoucherOptions> alreadySubmittedOrMissing(String userId, String token) {
        return store.findResult(token).flatMap(result -> {
            if (result.isPresent() && result.get().userId().equals(userId)) {
                return Mono.<PublicDtos.VoucherOptions>error(alreadySubmitted(token));
            }
            return Mono.<PublicDtos.VoucherOptions>error(new ApiException(ProblemCode.CHECKOUT_NOT_FOUND,
                    "No checkout with this token exists for the current user"));
        });
    }

    private Mono<PublicDtos.VoucherOptions> options(SessionStore.Control control, String userId, String token,
                                                    RequestContext context) {
        return store.readSnapshotCipher(control.ref())
                .switchIfEmpty(Mono.error(new ApiException(ProblemCode.CHECKOUT_NOT_FOUND,
                        "No checkout with this token exists for the current user")))
                .flatMap(cipher -> {
                    Snapshot snapshot = codec.decryptSnapshot(cipher);
                    return guard.call(Dependency.PROMOTION_OPTIONS, context.deadline(),
                                    () -> promotions.options(userId, snapshot.totalAmount(),
                                            PreviewService.contextItems(snapshot.items()), context))
                            .flatMap(result -> {
                                if (!result.isUsable()) {
                                    return Mono.<PublicDtos.VoucherOptions>error(
                                            failures.unavailable(ProblemCode.VOUCHER_OPTIONS_UNAVAILABLE, result));
                                }
                                return Mono.just(toView(token, snapshot, result.value()));
                            });
                });
    }

    private PublicDtos.VoucherOptions toView(String token, Snapshot snapshot, PromotionClient.Options options) {
        List<PublicDtos.VoucherOption> items = new ArrayList<>();
        for (PromotionClient.Option option : options.items()) {
            map(option, snapshot).ifPresent(items::add);
        }
        return new PublicDtos.VoucherOptions(token, snapshot.promotion().promotionId(), List.copyOf(items),
                options.evaluatedAt());
    }

    /**
     * Interfaces.md, "VoucherOptions": {@code UNKNOWN} sources and unmapped source/mode combinations are
     * dropped and counted; an unknown reason becomes {@code UNKNOWN}. Order is Promotion's ranking.
     */
    private Optional<PublicDtos.VoucherOption> map(PromotionClient.Option option, Snapshot snapshot) {
        PromotionClient.Evaluation evaluation = option.evaluation();
        PromotionClient.Display display = option.display();
        String source = evaluation.source();
        if (!"WALLET".equals(source) && !"PUBLIC".equals(source)) {
            metrics.voucherOptionDropped("source");
            return Optional.empty();
        }
        String action = requiredAction(source, evaluation.distributionMode());
        if (action == null) {
            metrics.voucherOptionDropped("distribution_mode");
            return Optional.empty();
        }
        PublicDtos.DiscountRule rule = discountRule(display.discount());
        if (rule == null) {
            metrics.voucherOptionDropped("discount_type");
            return Optional.empty();
        }
        boolean applied = evaluation.promotionId().equals(snapshot.promotion().promotionId());
        return Optional.of(new PublicDtos.VoucherOption(evaluation.promotionId(), source, action,
                evaluation.campaignRevision(), display.code(), display.name(), display.description(), rule,
                PublicDtos.Money.vnd(display.minOrderAmount()), display.validUntil(), evaluation.eligible(),
                PromotionReasons.map(evaluation.reason()), PublicDtos.Money.vnd(evaluation.discount()),
                PublicDtos.Money.vnd(evaluation.finalAmount()), applied));
    }

    /** WALLET needs nothing; PUBLIC needs SAVE or CLAIM according to its distribution mode. */
    private static String requiredAction(String source, String distributionMode) {
        if ("WALLET".equals(source)) {
            return "NONE";
        }
        return switch (distributionMode) {
            case "PUBLIC_SAVE" -> "SAVE";
            case "LIMITED_CLAIM" -> "CLAIM";
            default -> null;
        };
    }

    private static PublicDtos.DiscountRule discountRule(JsonNode discount) {
        String type = discount.path("type").asText("");
        if ("PERCENTAGE".equals(type) && discount.path("value_basis_points").isIntegralNumber()) {
            JsonNode max = discount.path("max_discount");
            return new PublicDtos.PercentageDiscountRule(type, discount.path("value_basis_points").asInt(),
                    max.path("amount_minor").isIntegralNumber()
                            ? PublicDtos.Money.vnd(max.path("amount_minor").asLong()) : null);
        }
        if ("FIXED_VND".equals(type) && discount.path("value").path("amount_minor").isIntegralNumber()) {
            return new PublicDtos.FixedDiscountRule(type,
                    PublicDtos.Money.vnd(discount.path("value").path("amount_minor").asLong()));
        }
        return null;
    }

    private static ApiException alreadySubmitted(String token) {
        return new ApiException(Problem.of(ProblemCode.CHECKOUT_ALREADY_SUBMITTED,
                "This checkout was already submitted; voucher options exist only for a quotation not yet submitted",
                Map.of("status_url", "/api/v1/checkout/submissions/" + token)));
    }
}
