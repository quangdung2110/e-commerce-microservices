package com.ecommerce.review.service;

import com.ecommerce.review.config.ReviewProperties;
import com.ecommerce.review.domain.EligibilityStatus;
import com.ecommerce.review.messaging.OrderCompletedPayload;
import com.ecommerce.review.metrics.ReviewMetrics;
import com.ecommerce.review.privacy.SubjectKey;
import com.ecommerce.review.repository.EligibilityRepository;
import com.ecommerce.review.repository.EventReceiptRepository;
import com.ecommerce.review.repository.TransactionClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WF-REVIEW-001: turning one {@code ORDER_COMPLETED} into review entitlements.
 *
 * <p>Everything the entitlement needs is in the event. Review never calls Order back —
 * not for the variant name, not for anything — because a synchronous cross-service call
 * in a write path is exactly what the architecture forbids.
 */
@Service
public class EntitlementService {

    private static final Logger log = LoggerFactory.getLogger(EntitlementService.class);

    private final EligibilityRepository eligibilities;
    private final EventReceiptRepository receipts;
    private final PrivacyGuardService privacyGuard;
    private final TransactionClock clock;
    private final ReviewProperties properties;
    private final ReviewMetrics metrics;

    public EntitlementService(EligibilityRepository eligibilities,
                              EventReceiptRepository receipts,
                              PrivacyGuardService privacyGuard,
                              TransactionClock clock,
                              ReviewProperties properties,
                              ReviewMetrics metrics) {
        this.eligibilities = eligibilities;
        this.receipts = receipts;
        this.privacyGuard = privacyGuard;
        this.clock = clock;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * Records the receipt and, unless the buyer is guarded, the entitlements.
     *
     * <p>The subject lock is taken first, before the receipt and before any row carrying
     * {@code owner_user_id} is written, so this serialises with the privacy consumer. A
     * guarded buyer commits the receipt and nothing else: the event is consumed, no
     * entitlement appears, and no event leaves Review.
     */
    @Transactional
    public void apply(UUID eventId, OrderCompletedPayload payload, String topic, int partition, long offset) {
        SubjectKey subjectKey = privacyGuard.subjectKey(payload.userId());
        privacyGuard.lock(subjectKey);

        if (!receipts.claim(EventReceiptRepository.ORDER_SYNC, eventId, topic, partition, offset)) {
            metrics.consumerDuplicate(EventReceiptRepository.ORDER_SYNC);
            return;
        }
        if (privacyGuard.isGuarded(subjectKey, "order-completed")) {
            return;
        }

        Instant now = clock.now();
        Duration window = Duration.ofDays(properties.windowDays());

        for (OrderCompletedPayload.Item item : deduplicate(payload)) {
            // The window is measured from the business timestamp, so an event that
            // arrives late still records the moment the window actually closed and
            // simply lands as EXPIRED (REVIEW-BR-002).
            Instant expiresAt = payload.completedAt().plus(window);
            EligibilityStatus status = expiresAt.isAfter(now)
                    ? EligibilityStatus.PENDING
                    : EligibilityStatus.EXPIRED;

            eligibilities.insertIfAbsent(payload.orderId(), item.productId(), item.skuId(),
                    item.variantName(), item.productName(), item.productImageUrl(),
                    payload.userId(), status, payload.completedAt(), expiresAt);
        }
    }

    /**
     * Keeps the first occurrence of each {@code (product_id, sku_id)}.
     *
     * <p>Producers must not repeat a pair, but JSON Schema cannot express uniqueness on
     * a key subset. Deduplicating here in array order means a producer that does repeat
     * one cannot make the snapshotted variant name depend on statement ordering
     * (WF-REVIEW-001 step 3).
     */
    private static Iterable<OrderCompletedPayload.Item> deduplicate(OrderCompletedPayload payload) {
        Map<List<String>, OrderCompletedPayload.Item> firstOccurrence = new LinkedHashMap<>();
        for (OrderCompletedPayload.Item item : payload.items()) {
            firstOccurrence.putIfAbsent(List.of(item.productId(), item.skuId()), item);
        }
        if (firstOccurrence.size() != payload.items().size()) {
            log.warn("ORDER_COMPLETED for order {} repeated a (product_id, sku_id) pair; kept the first",
                    payload.orderId());
        }
        return firstOccurrence.values();
    }
}
