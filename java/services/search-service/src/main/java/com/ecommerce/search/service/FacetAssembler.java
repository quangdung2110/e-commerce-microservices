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
 * <p>The group label comes from the Category {@code facet_definitions} Product publishes
 * (Product ADR-019) when the attribute has a descriptor, and is otherwise derived from the
 * token. Option labels are always derived: the descriptor carries no options, so the folded
 * value, title-cased by {@link #label}, is the only display text available for them.
 */
@Component
public class FacetAssembler {

    private final SearchProperties properties;

    public FacetAssembler(SearchProperties properties) {
        this.properties = properties;
    }

    /**
     * Groups buckets by attribute code, ordered by count then value for stable output.
     *
     * @param groupLabels display names from Product's {@code facet_definitions}, keyed by the
     *                    folded attribute code; a code without an entry keeps the derived label
     */
    public SearchDtos.FacetSet assemble(Aggregate aggregate, Map<String, String> groupLabels) {
        if (aggregate == null || !aggregate.isSterms()) {
            return SearchDtos.FacetSet.of(List.of());
        }
        List<Map.Entry<String, Long>> counts = new ArrayList<>();
        for (StringTermsBucket bucket : aggregate.sterms().buckets().array()) {
            counts.add(Map.entry(bucket.key().stringValue(), distinctProducts(bucket)));
        }
        return assembleCounts(counts, groupLabels);
    }

    /**
     * Same grouping from distinct-Product counts per token, in bucket order. The Product-level
     * projection (ADR-027) counts them exactly with {@code reverse_nested}, so it does not carry a
     * {@code cardinality} sub-aggregation.
     */
    public SearchDtos.FacetSet assembleCounts(List<Map.Entry<String, Long>> tokenCounts, Map<String, String> groupLabels) {
        Map<String, List<SearchDtos.FacetOption>> byCode = new LinkedHashMap<>();
        Map<String, Long> codeWeight = new LinkedHashMap<>();

        for (Map.Entry<String, Long> tokenCount : tokenCounts) {
            String token = tokenCount.getKey();
            AttributeToken.decode(token).ifPresent(decoded -> {
                long count = tokenCount.getValue();
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
                        groupLabels.getOrDefault(entry.getKey(), label(entry.getKey())),
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
        String label = out.toString();
        for (String unit : List.of("gb", "tb", "w", "k", "g")) {
            label = label.replaceAll("(?i)(\\d+)" + unit + "\\b", "$1" + unit.toUpperCase(Locale.ROOT));
        }
        label = label.replaceAll("(?i)(\\d+)mah\\b", "$1mAh");
        for (String term : List.of("gps", "lte", "rgb", "anc", "usb")) {
            label = label.replaceAll("(?i)\\b" + term + "\\b", term.toUpperCase(Locale.ROOT));
        }
        return label.replaceAll("(?i)\\bwi fi\\b", "Wi-Fi").replaceAll("\\bUSB C\\b", "USB-C");
    }
}
