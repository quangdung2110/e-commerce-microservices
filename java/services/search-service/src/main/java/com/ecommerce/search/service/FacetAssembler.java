package com.ecommerce.search.service;

import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.model.AttributeToken;
import com.ecommerce.search.dto.SearchDtos;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns the flat {@code filter_tokens} terms aggregation into the grouped facet structure
 * of the contract.
 *
 * <p>Counts come from the {@code cardinality} sub-aggregation on {@code product_id}, never
 * from {@code doc_count} ({@code SEARCH-INV-003}), which is why the response declares
 * {@code count_semantics = DISTINCT_PRODUCT} and {@code count_accuracy = APPROXIMATE}.
 *
 * <p>Labels are derived from the token itself. The design intends them to come from
 * {@code facet_definitions}, a Category descriptor Product does not publish yet
 * ({@code docs/search/README.md §9}); until it does, the attribute code and the folded
 * value are the only display text available and are title-cased here. When the descriptor
 * arrives, only {@link #label} needs to change.
 */
@Component
public class FacetAssembler {

    private final SearchProperties properties;

    public FacetAssembler(SearchProperties properties) {
        this.properties = properties;
    }

    /** Groups buckets by attribute code, ordered by count then value for stable output. */
    public SearchDtos.FacetSet assemble(Aggregate aggregate) {
        if (aggregate == null || !aggregate.isSterms()) {
            return SearchDtos.FacetSet.of(List.of());
        }
        Map<String, List<SearchDtos.FacetOption>> byCode = new LinkedHashMap<>();
        Map<String, Long> codeWeight = new LinkedHashMap<>();

        for (StringTermsBucket bucket : aggregate.sterms().buckets().array()) {
            String token = bucket.key().stringValue();
            AttributeToken.decode(token).ifPresent(decoded -> {
                long count = distinctProducts(bucket);
                if (count == 0) {
                    return;
                }
                byCode.computeIfAbsent(decoded.code(), unused -> new ArrayList<>())
                        .add(new SearchDtos.FacetOption(token, label(decoded.value()), count));
                codeWeight.merge(decoded.code(), count, Long::sum);
            });
        }

        List<SearchDtos.AttributeFacet> facets = byCode.entrySet().stream()
                .sorted(Comparator
                        .comparingLong((Map.Entry<String, List<SearchDtos.FacetOption>> e) ->
                                -codeWeight.getOrDefault(e.getKey(), 0L))
                        .thenComparing(Map.Entry::getKey))
                .limit(properties.query().facetMaxAttributes())
                .map(entry -> SearchDtos.AttributeFacet.multiSelect(
                        entry.getKey(),
                        label(entry.getKey()),
                        entry.getValue().stream()
                                .sorted(Comparator
                                        .comparingLong((SearchDtos.FacetOption o) -> -o.count())
                                        .thenComparing(SearchDtos.FacetOption::label))
                                .limit(properties.query().facetMaxOptions())
                                .toList()))
                .toList();

        return SearchDtos.FacetSet.of(facets);
    }

    private static long distinctProducts(StringTermsBucket bucket) {
        Aggregate sub = bucket.aggregations().get(ProductQueryBuilder.DISTINCT_PRODUCTS_AGG);
        return sub != null && sub.isCardinality() ? sub.cardinality().value() : 0L;
    }

    /** Title-cases a folded code or value so the dropdown is readable. */
    private static String label(String folded) {
        if (folded == null || folded.isBlank()) {
            return "-";
        }
        String spaced = folded.replace('_', ' ').replace('-', ' ').trim();
        StringBuilder out = new StringBuilder(spaced.length());
        boolean startOfWord = true;
        for (char c : spaced.toCharArray()) {
            out.append(startOfWord ? Character.toUpperCase(c) : c);
            startOfWord = Character.isWhitespace(c);
        }
        return out.toString().toLowerCase(Locale.ROOT).isEmpty() ? "-" : out.toString();
    }
}
