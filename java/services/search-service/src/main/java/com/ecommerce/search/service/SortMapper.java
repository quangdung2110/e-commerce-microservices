package com.ecommerce.search.service;

import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Maps the {@code sort} query parameter to an Elasticsearch {@link SortOptions} list.
 *
 * <p>Every sort option appends a tie-breaker {@code sku_id asc} at the end to
 * guarantee stable cursor-based pagination via {@code search_after}. Without a
 * tie-breaker, documents with identical sort values would be ordered
 * non-deterministically, causing pagination to skip or duplicate results.
 *
 * <p><b>Sort values supported:</b>
 * <ul>
 *   <li>{@code relevance} (default) — {@code _score desc, sku_id asc}</li>
 *   <li>{@code price_asc} — {@code best_price asc, sku_id asc}</li>
 *   <li>{@code price_desc} — {@code best_price desc, sku_id asc}</li>
 *   <li>{@code newest} — {@code updated_at desc, sku_id asc}</li>
 *   <li>{@code rating} — {@code product_rating_avg desc, product_rating_count desc, sku_id asc}</li>
 * </ul>
 */
@Component
public class SortMapper {

    public static final String SORT_RELEVANCE = "relevance";
    public static final String SORT_PRICE_ASC = "price_asc";
    public static final String SORT_PRICE_DESC = "price_desc";
    public static final String SORT_NEWEST = "newest";
    public static final String SORT_RATING = "rating";

    /**
     * Parse the sort parameter and return the corresponding Elasticsearch sort options.
     *
     * @param sortParam the raw sort query parameter ({@code null} → default to relevance)
     * @return a non-empty list of sort options ending with the tie-breaker
     */
    public List<SortOptions> map(String sortParam) {
        String sort = sortParam == null ? SORT_RELEVANCE : sortParam;
        return switch (sort) {
            case SORT_PRICE_ASC -> List.of(
                SortOptions.of(s -> s.field(f -> f.field("best_price").order(SortOrder.Asc))),
                tieBreakSkuId()
            );
            case SORT_PRICE_DESC -> List.of(
                SortOptions.of(s -> s.field(f -> f.field("best_price").order(SortOrder.Desc))),
                tieBreakSkuId()
            );
            case SORT_NEWEST -> List.of(
                SortOptions.of(s -> s.field(f -> f.field("updated_at").order(SortOrder.Desc))),
                tieBreakSkuId()
            );
            case SORT_RATING -> List.of(
                SortOptions.of(s -> s.field(f -> f.field("product_rating_avg").order(SortOrder.Desc))),
                SortOptions.of(s -> s.field(f -> f.field("product_rating_count").order(SortOrder.Desc))),
                tieBreakSkuId()
            );
            default -> List.of(
                SortOptions.of(s -> s.score(sc -> sc.order(SortOrder.Desc))),
                tieBreakSkuId()
            );
        };
    }

    /**
     * Returns a tie-breaker sort option on {@code sku_id asc}.
     * This must be the <em>last</em> element in every sort list to ensure
     * stable cursor-based pagination.
     */
    private SortOptions tieBreakSkuId() {
        return SortOptions.of(s -> s.field(f -> f.field("sku_id").order(SortOrder.Asc)));
    }
}
