package com.platform.productservice.worker.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.productservice.domain.entity.MarkdownApplied;
import com.platform.productservice.domain.entity.OutboxEvent;
import com.platform.productservice.domain.entity.Product;
import com.platform.productservice.domain.entity.Sku;
import com.platform.productservice.domain.event.DomainEvent;
import com.platform.productservice.domain.event.SkuUpdatedEvent;
import com.platform.productservice.domain.vo.ProductId;
import com.platform.productservice.domain.vo.SkuId;
import com.platform.productservice.port.outbound.cache.DistributedLockRepository;
import com.platform.productservice.port.outbound.cache.MarkdownCacheRepository;
import com.platform.productservice.port.outbound.database.MarkdownRepository;
import com.platform.productservice.port.outbound.database.OutboxRepository;
import com.platform.productservice.port.outbound.database.ProductRepository;
import com.platform.productservice.port.outbound.database.SkuRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Processes expired markdown rules by resetting promotional prices to NULL
 * and publishing SKU_UPDATED events.
 *
 * <p>Processing flow:</p>
 * <ol>
 *   <li>Query all expired markdowns from DB</li>
 *   <li>Group by product ID, sorted alphabetically (deadlock prevention)</li>
 *   <li>Per product: acquire distributed lock, delegate to transactional service</li>
 *   <li>Transactional service: lock DB rows, reset prices, delete markdown records</li>
 *   <li>Publish SKU_UPDATED events with changeSource=MARKDOWN_ENDED</li>
 *   <li>Delete markdown cache entries from Redis</li>
 * </ol>
 */
@Component
public class MarkdownExpiryProcessor {

    private static final Logger log = LoggerFactory.getLogger(MarkdownExpiryProcessor.class);

    private final MarkdownRepository markdownRepository;
    private final DistributedLockRepository distributedLockRepository;
    private final MarkdownExpiryTransactionalService transactionalService;

    private final int productLockTtlSeconds;

    public MarkdownExpiryProcessor(
            MarkdownRepository markdownRepository,
            DistributedLockRepository distributedLockRepository,
            @Lazy MarkdownExpiryTransactionalService transactionalService,
            @Value("${app.markdown-expiry.product-lock-ttl-seconds:30}") int productLockTtlSeconds) {
        this.markdownRepository = markdownRepository;
        this.distributedLockRepository = distributedLockRepository;
        this.transactionalService = transactionalService;
        this.productLockTtlSeconds = productLockTtlSeconds;
    }

    /**
     * Processes all expired markdowns.
     *
     * @return total number of expired markdowns processed
     */
    public int processExpiredMarkdowns() {
        Instant now = Instant.now();
        List<MarkdownApplied> allExpired = markdownRepository.findExpiredMarkdowns(now);

        if (allExpired.isEmpty()) {
            log.debug("MarkdownExpiryProcessor: no expired markdowns found");
            return 0;
        }

        log.info("MarkdownExpiryProcessor: found {} expired markdown records to process", allExpired.size());

        Map<String, List<MarkdownApplied>> markdownsByProduct = allExpired.stream()
                .collect(Collectors.groupingBy(
                        m -> m.getProductId().getValue(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<String> sortedProductIds = new ArrayList<>(markdownsByProduct.keySet());
        sortedProductIds.sort(String::compareTo);

        int totalProcessed = 0;
        int productsAffected = 0;

        for (String productId : sortedProductIds) {
            List<MarkdownApplied> productMarkdowns = markdownsByProduct.get(productId);
            try {
                boolean processed = processProductMarkdowns(productId, productMarkdowns);
                if (processed) {
                    totalProcessed += productMarkdowns.size();
                    productsAffected++;
                }
            } catch (Exception e) {
                log.error("MarkdownExpiryProcessor: failed to process markdowns for product {}: {}",
                        productId, e.getMessage(), e);
            }
        }

        log.info("MarkdownExpiryProcessor: completed. totalMarkdownsProcessed={}, productsAffected={}",
                totalProcessed, productsAffected);

        return totalProcessed;
    }

    private boolean processProductMarkdowns(String productId, List<MarkdownApplied> markdowns) {
        String lockKey = "product:lock:markdown-expiry:" + productId;
        Duration lockTtl = Duration.ofSeconds(productLockTtlSeconds);

        if (!distributedLockRepository.acquireLock(lockKey, lockTtl)) {
            log.warn("MarkdownExpiryProcessor: could not acquire lock for product {}, skipping", productId);
            return false;
        }

        try {
            return transactionalService.processProductInTransaction(productId, markdowns);
        } catch (Exception e) {
            log.error("MarkdownExpiryProcessor: error processing product {}: {}", productId, e.getMessage(), e);
            return false;
        } finally {
            distributedLockRepository.releaseLock(lockKey);
        }
    }
}
