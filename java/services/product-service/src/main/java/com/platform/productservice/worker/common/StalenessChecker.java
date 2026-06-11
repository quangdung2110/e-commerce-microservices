package com.platform.productservice.worker.common;

import com.platform.productservice.domain.enums.ChangedField;
import com.platform.productservice.infrastructure.redis.cache.model.SkuCacheEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/**
 * Helper class for checking event staleness against cached data.
 * Implements multi-layered staleness guards for different field types.
 */
@Component
public class StalenessChecker {

    private static final Logger log = LoggerFactory.getLogger(StalenessChecker.class);

    /**
     * Checks whether the incoming event should be applied based on its timestamp
     * versus the cached entry's timestamps.
     *
     * @param eventTimestamp the timestamp from the incoming event (epoch millis)
     * @param cachedEntry    the cached SKU entry to compare against
     * @return true if the event is fresh and should be applied, false if stale
     */
    public boolean isEventFresh(long eventTimestamp, SkuCacheEntry cachedEntry) {
        if (cachedEntry == null) {
            return true;
        }

        if (eventTimestamp <= cachedEntry.getLastInventorySyncAt()) {
            log.debug("StalenessChecker: event timestamp {} <= lastInventorySyncAt {} — STALE",
                    eventTimestamp, cachedEntry.getLastInventorySyncAt());
            return false;
        }

        if (cachedEntry.getPriceUpdatedAt() != null && !cachedEntry.getPriceUpdatedAt().isBlank()) {
            try {
                Instant priceUpdatedAt = Instant.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME
                        .parse(cachedEntry.getPriceUpdatedAt()));
                if (eventTimestamp <= priceUpdatedAt.toEpochMilli()) {
                    log.debug("StalenessChecker: event timestamp {} <= priceUpdatedAt {} — STALE",
                            eventTimestamp, priceUpdatedAt.toEpochMilli());
                    return false;
                }
            } catch (Exception e) {
                log.warn("StalenessChecker: failed to parse priceUpdatedAt '{}': {}",
                        cachedEntry.getPriceUpdatedAt(), e.getMessage());
            }
        }

        return true;
    }

    /**
     * Checks staleness for SKU_CREATED events.
     *
     * @param eventCreatedAt the created_at from the event (epoch millis)
     * @param cachedEntry    the cached entry, or null if not cached
     * @return true if the event is fresh, false if stale
     */
    public boolean isSkuCreatedFresh(long eventCreatedAt, SkuCacheEntry cachedEntry) {
        if (cachedEntry == null) {
            return true;
        }
        return eventCreatedAt > cachedEntry.getLastInventorySyncAt();
    }

    /**
     * Checks staleness for SKU_UPDATED events based on which fields changed.
     *
     * @param eventTimestamp the updatedAt from the event (epoch millis)
     * @param cachedEntry    the cached entry
     * @param changedFields  the list of fields that changed in this event
     * @return true if the event is fresh for at least one changed field, false if all are stale
     */
    public boolean isSkuUpdatedFresh(long eventTimestamp, SkuCacheEntry cachedEntry, List<String> changedFields) {
        if (cachedEntry == null) {
            return true;
        }

        if (changedFields == null || changedFields.isEmpty()) {
            return isEventFresh(eventTimestamp, cachedEntry);
        }

        Set<String> priceFields = Set.of(
                ChangedField.original_price.name(),
                ChangedField.promotional_price.name(),
                ChangedField.best_price.name(),
                ChangedField.has_promotion.name()
        );

        Set<String> inventoryFields = Set.of(
                ChangedField.sku_status.name(),
                ChangedField.display_quantity.name()
        );

        boolean hasPriceFieldChange = changedFields.stream().anyMatch(priceFields::contains);
        boolean hasInventoryFieldChange = changedFields.stream().anyMatch(inventoryFields::contains);
        boolean hasOtherFieldChange = changedFields.stream()
                .noneMatch(f -> priceFields.contains(f) || inventoryFields.contains(f));

        if (hasPriceFieldChange) {
            if (cachedEntry.getPriceUpdatedAt() != null && !cachedEntry.getPriceUpdatedAt().isBlank()) {
                try {
                    Instant priceUpdatedAt = Instant.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME
                            .parse(cachedEntry.getPriceUpdatedAt()));
                    if (eventTimestamp <= priceUpdatedAt.toEpochMilli()) {
                        log.debug("StalenessChecker: SKU_UPDATED price field change is STALE, eventTs={}, priceUpdatedAt={}",
                                eventTimestamp, priceUpdatedAt.toEpochMilli());
                        return false;
                    }
                } catch (Exception e) {
                    log.warn("StalenessChecker: failed to parse priceUpdatedAt: {}", cachedEntry.getPriceUpdatedAt());
                }
            }
        }

        if (hasInventoryFieldChange) {
            if (eventTimestamp <= cachedEntry.getLastInventorySyncAt()) {
                log.debug("StalenessChecker: SKU_UPDATED inventory field change is STALE, eventTs={}, lastInventorySyncAt={}",
                        eventTimestamp, cachedEntry.getLastInventorySyncAt());
                return false;
            }
        }

        if (hasOtherFieldChange) {
            if (cachedEntry.getPriceUpdatedAt() != null && !cachedEntry.getPriceUpdatedAt().isBlank()) {
                try {
                    Instant priceUpdatedAt = Instant.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME
                            .parse(cachedEntry.getPriceUpdatedAt()));
                    if (eventTimestamp <= priceUpdatedAt.toEpochMilli()) {
                        log.debug("StalenessChecker: SKU_UPDATED other field change is STALE, eventTs={}, priceUpdatedAt={}",
                                eventTimestamp, priceUpdatedAt.toEpochMilli());
                        return false;
                    }
                } catch (Exception e) {
                    log.warn("StalenessChecker: failed to parse priceUpdatedAt for other field check: {}",
                            cachedEntry.getPriceUpdatedAt());
                }
            }
        }

        return true;
    }

    /**
     * Checks staleness for product-level events (e.g., CATEGORY_DELETED).
     *
     * @param eventTimestamp the event timestamp (epoch millis)
     * @param productUpdatedAt the product's updated_at timestamp (epoch millis)
     * @return true if the event is fresh, false if stale
     */
    public boolean isProductEventFresh(long eventTimestamp, long productUpdatedAt) {
        if (productUpdatedAt == 0) {
            return true;
        }
        return eventTimestamp > productUpdatedAt;
    }
}
