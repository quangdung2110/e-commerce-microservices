package com.ecommerce.search.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Wire types of {@code contracts/http/search/v2/openapi.yaml}.
 *
 * <p>Every schema there declares {@code additionalProperties: false} and lists nullable
 * fields explicitly, so these records carry exactly the contract's properties — nothing is
 * omitted when null, because several nullable fields are also required.
 */
public final class SearchDtos {

    private SearchDtos() {
    }

    /** Exact distinct matching products; every item is one product card. */
    public record SearchPage(
            List<SearchProductItem> items,
            long totalProducts,
            String nextCursor,
            FacetSet facets) {
    }

    /** One Product represented by its cheapest matching SKU (price, then sku_id). */
    public record SearchProductItem(
            String skuId,
            String productId,
            String productSlug,
            String name,
            String thumbnailUrl,
            long basePriceVnd,
            long sellingPriceVnd,
            int discountPercentage,
            Double productRatingAvg,
            long productRatingCount,
            boolean isInStock) {
    }

    public record SuggestionList(List<Suggestion> items) {
    }

    /**
     * {@code Suggestion}: a phrase that completes a query. It is not a navigation target,
     * which is why no identifier is exposed and {@code type} is presentation metadata only.
     */
    public record Suggestion(String text, String type) {
    }

    /** {@code CategoryTree}; counts are DISTINCT products. */
    public record CategoryTree(String countSemantics, List<CategoryNode> categories) {
        public static CategoryTree of(List<CategoryNode> categories) {
            return new CategoryTree("DISTINCT_PRODUCT", categories);
        }
    }

    /**
     * {@code CategoryNode}. {@code productCountUpdatedAt} is null until the recompute job
     * has covered this node, which is why the contract marks it nullable.
     */
    public record CategoryNode(
            String categoryId,
            String name,
            String slug,
            long productCount,
            Instant productCountUpdatedAt,
            List<CategoryNode> children) {
    }

    /** {@code FacetSet}; the two constants tell clients how to read the counts. */
    public record FacetSet(String countSemantics, String countAccuracy, List<AttributeFacet> filters) {
        public static FacetSet of(List<AttributeFacet> filters) {
            return new FacetSet("DISTINCT_PRODUCT", "APPROXIMATE", filters);
        }
    }

    public record AttributeFacet(String code, String label, String type, List<FacetOption> options) {
        public static AttributeFacet multiSelect(String code, String label, List<FacetOption> options) {
            return new AttributeFacet(code, label, "MULTI_SELECT", options);
        }
    }

    /** {@code value} is the opaque token to echo back through the {@code filter} parameter. */
    public record FacetOption(String value, String label, long count) {
    }

    public record CreateReindexRunRequest(String reason) {
    }

    public record ReindexRun(
            String runId,
            String status,
            String phase,
            ReindexProgress progress,
            String statusUrl,
            Instant startedAt,
            Instant finishedAt,
            @JsonInclude(JsonInclude.Include.ALWAYS) ReindexFailure failure) {
    }

    public record ReindexProgress(
            long categoriesExported,
            long productsExported,
            long stockSnapshotsImported,
            long ratingAggregatesImported,
            Long catchUpLag) {
    }

    public record ReindexFailure(String code, String message) {
    }
}
