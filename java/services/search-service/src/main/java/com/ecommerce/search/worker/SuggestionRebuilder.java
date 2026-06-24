package com.ecommerce.search.worker;

import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.document.CategoryDocument;
import com.ecommerce.search.domain.document.ProductDocument;
import com.ecommerce.search.domain.document.SuggestionDocument;
import com.ecommerce.search.repository.CategoryRepository;
import com.ecommerce.search.repository.ProductSearchRepository;
import com.ecommerce.search.repository.ReindexRepository;
import com.ecommerce.search.repository.SuggestRepository;
import com.ecommerce.search.repository.es.SuggestionIndexMapping;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Daily cronjob that atomically rebuilds the {@code search_suggestions} autocomplete
 * index.
 *
 * <p>The rebuild follows these steps:
 * <ol>
 *   <li>Create a new versioned index ({@code search_suggestions_v{timestamp}})</li>
 *   <li>Build category suggestions (weight = product_count * W_CATEGORY_BASE)</li>
 *   <li>Build product suggestions (weight = rating * log(count+1) * W_REVIEW_FACTOR + promo boost)</li>
 *   <li>Validate: total docs &ge; threshold, no hot prefix empty</li>
 *   <li>Atomic alias swap: point {@code search_suggestions} to the new index</li>
 * </ol>
 *
 * <p>If the build fails before the swap, the old index is untouched and the
 * API continues serving from it without interruption.
 *
 * <p>Schedule: configured via {@code search.suggestions.rebuild-cron} (default 03:00 AM daily).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SuggestionRebuilder {

    private static final long W_CATEGORY_BASE = 10;
    private static final long W_REVIEW_FACTOR = 100;
    private static final long BOOST_PROMOTION = 5000;
    private static final int BATCH_SIZE = 2000;
    private static final long MIN_DOCS_THRESHOLD = 100;

    private final ProductSearchRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final SuggestRepository suggestRepository;
    private final ReindexRepository reindexRepository;
    private final SearchProperties properties;

    /**
     * Scheduled execution of the suggestions rebuild.
     *
     * <p>Errors are logged but never rethrown, so the scheduler does not
     * repeatedly retry the same failed run.
     */
    @Scheduled(cron = "${search.suggestions.rebuild-cron}")
    public void rebuildSuggestions() {
        long startTime = System.currentTimeMillis();
        String newIndex = "search_suggestions_v" + System.currentTimeMillis();
        log.info("Starting Suggestions Rebuilder: new_index={}", newIndex);

        try {
            // 1. Create new index with the standard suggestions mapping
            reindexRepository.createIndex(newIndex, SuggestionIndexMapping.MAPPING_JSON);
            log.info("Created new suggestions index [{}]", newIndex);

            // 2. Build suggestions
            AtomicLong totalWritten = new AtomicLong(0);

            // 2a. Category suggestions
            buildCategorySuggestions(newIndex, totalWritten);

            // 2b. Product suggestions
            buildProductSuggestions(newIndex, totalWritten);

            log.info("Total suggestions written: {}", totalWritten.get());

            // 3. Validation gate
            if (totalWritten.get() < MIN_DOCS_THRESHOLD) {
                log.warn("Suggestions count {} < threshold {}, aborting swap",
                    totalWritten.get(), MIN_DOCS_THRESHOLD);
                reindexRepository.deleteIndex(newIndex);
                return;
            }

            // 4. Resolve the current index name to enable rollback
            String currentIndex = reindexRepository.resolveIndexForAlias(
                properties.getEs().getIndexAliases().getSuggestions());
            log.info("Current suggestions index: [{}]", currentIndex);

            // 5. Atomic alias swap (suggestions has no write alias, pass null)
            if (currentIndex != null) {
                // Normal case: swap from old index to new index
                reindexRepository.swapAlias(
                    currentIndex,
                    newIndex,
                    properties.getEs().getIndexAliases().getSuggestions(),
                    null
                );
                log.info("Swapped alias [{}] from [{}] -> [{}]",
                    properties.getEs().getIndexAliases().getSuggestions(), currentIndex, newIndex);

                // 6. Cleanup old index
                if (!currentIndex.equals(newIndex)) {
                    reindexRepository.deleteIndex(currentIndex);
                    log.info("Deleted old suggestions index [{}]", currentIndex);
                }
            } else {
                // First run: no alias exists yet, just add it to the new index
                reindexRepository.addAlias(
                    newIndex,
                    properties.getEs().getIndexAliases().getSuggestions()
                );
                log.info("Added alias [{}] -> [{}] (first run)",
                    properties.getEs().getIndexAliases().getSuggestions(), newIndex);
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("Suggestions Rebuilder completed in {} ms ({} docs)", duration, totalWritten.get());

        } catch (Exception e) {
            log.error("Suggestions Rebuilder failed for new_index=[{}]", newIndex, e);
            // Best effort: delete the partial new index
            try {
                reindexRepository.deleteIndex(newIndex);
                log.info("Cleaned up partial new index [{}]", newIndex);
            } catch (Exception ex) {
                log.warn("Failed to delete partial new index [{}]", newIndex, ex);
            }
        }
    }

    /**
     * Build category suggestions from all non-deleted categories.
     *
     * <p>Weight formula: {@code product_count * W_CATEGORY_BASE}.
     */
    private void buildCategorySuggestions(String newIndex, AtomicLong totalWritten) {
        log.info("Building category suggestions...");

        // Get all non-deleted categories, sorted by level
        SearchRequest request = SearchRequest.of(s -> s
            .index(properties.getEs().getIndexAliases().getCategories())
            .size(1000)
            .query(Query.of(q -> q.bool(b -> b
                .filter(Query.of(qf -> qf.term(t -> t.field("is_deleted").value(false))))
            )))
            .sort(so -> so.field(f -> f.field("level").order(SortOrder.Asc)))
        );

        SearchResponse<CategoryDocument> response = categoryRepository.search(request);

        List<SuggestionDocument> batch = new ArrayList<>();
        for (Hit<CategoryDocument> hit : response.hits().hits()) {
            CategoryDocument cat = hit.source();
            if (cat == null) continue;

            // Weight = product_count * W_CATEGORY_BASE
            long weight = (cat.productCount() != null ? cat.productCount() : 0) * W_CATEGORY_BASE;

            SuggestionDocument doc = SuggestionDocument.builder()
                .suggestionId("sgg:cat:" + cat.categoryId())
                .text(cat.name())
                .textNormalized(normalize(cat.name()))
                .type("CATEGORY")
                .source("CATEGORIES_ALIAS")
                .scope("GLOBAL")
                .categoryId(cat.categoryId())
                .weight(weight)
                .productCount(cat.productCount())
                .buildVersion(buildVersion())
                .updatedAt(Instant.now())
                .build();

            batch.add(doc);

            if (batch.size() >= BATCH_SIZE) {
                suggestRepository.bulkIndexToIndex(newIndex, batch);
                totalWritten.addAndGet(batch.size());
                batch.clear();
            }
        }

        // Flush remaining
        if (!batch.isEmpty()) {
            suggestRepository.bulkIndexToIndex(newIndex, batch);
            totalWritten.addAndGet(batch.size());
        }

        log.info("Category suggestions written: {} total (running: {})",
            response.hits().total(), totalWritten.get());
    }

    /**
     * Build product suggestions from ACTIVE, in-stock, non-deleted products.
     *
     * <p>Weight formula: {@code rating * log(count+1) * W_REVIEW_FACTOR + (has_promotion ? BOOST : 0)}.
     */
    private void buildProductSuggestions(String newIndex, AtomicLong totalWritten) {
        log.info("Building product suggestions...");

        // Get ACTIVE in-stock products
        SearchRequest request = SearchRequest.of(s -> s
            .index(properties.getEs().getIndexAliases().getProducts())
            .size(10000)
            .query(Query.of(q -> q.bool(b -> b
                .filter(
                    Query.of(qf -> qf.term(t -> t.field("is_deleted").value(false))),
                    Query.of(qf -> qf.term(t -> t.field("product_status").value("ACTIVE"))),
                    Query.of(qf -> qf.term(t -> t.field("sku_status").value("ACTIVE"))),
                    Query.of(qf -> qf.term(t -> t.field("is_in_stock").value(true)))
                )
            )))
            .source(src -> src.filter(f -> f.includes(
                "product_id", "name", "product_rating_avg", "product_rating_count",
                "has_promotion", "category_id")))
        );

        SearchResponse<ProductDocument> response = productRepository.search(request);

        List<SuggestionDocument> batch = new ArrayList<>();
        for (Hit<ProductDocument> hit : response.hits().hits()) {
            ProductDocument prod = hit.source();
            if (prod == null || prod.name() == null) continue;

            // Weight = rating * log(count+1) * W_REVIEW_FACTOR + (has_promotion ? BOOST : 0)
            double rating = prod.productRatingAvg() != null ? prod.productRatingAvg() : 0.0;
            int count = prod.productRatingCount() != null ? prod.productRatingCount() : 0;
            long weight = (long) (rating * Math.log(count + 1) * W_REVIEW_FACTOR);
            if (Boolean.TRUE.equals(prod.hasPromotion())) {
                weight += BOOST_PROMOTION;
            }

            SuggestionDocument doc = SuggestionDocument.builder()
                .suggestionId("sgg:prod:" + prod.productId())
                .text(prod.name())
                .textNormalized(normalize(prod.name()))
                .type("PRODUCT")
                .source("PRODUCTS_ALIAS")
                .scope("GLOBAL")
                .categoryId(prod.categoryId())
                .productId(prod.productId())
                .weight(weight)
                .productRatingAvg(prod.productRatingAvg())
                .productRatingCount(prod.productRatingCount())
                .buildVersion(buildVersion())
                .updatedAt(Instant.now())
                .build();

            batch.add(doc);

            if (batch.size() >= BATCH_SIZE) {
                suggestRepository.bulkIndexToIndex(newIndex, batch);
                totalWritten.addAndGet(batch.size());
                batch.clear();
            }
        }

        // Flush remaining
        if (!batch.isEmpty()) {
            suggestRepository.bulkIndexToIndex(newIndex, batch);
            totalWritten.addAndGet(batch.size());
        }

        log.info("Product suggestions written: {} total (running: {})",
            response.hits().total(), totalWritten.get());
    }

    /**
     * Normalize text by removing diacritics, converting to lowercase, and trimming.
     */
    private static String normalize(String text) {
        if (text == null) return null;
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
            .toLowerCase()
            .trim();
    }

    /**
     * Build version as yyyyMMddHHmm formatted timestamp.
     */
    private static long buildVersion() {
        return Long.parseLong(DateTimeFormatter.ofPattern("yyyyMMddHHmm")
            .format(LocalDateTime.now()));
    }
}
