package com.ecommerce.search.service;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.dto.SearchDtos;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FacetAssemblerTest {

    private final FacetAssembler assembler = new FacetAssembler(properties());

    @Test
    void usesTheDescriptorLabelForTheGroupAndDerivesOptionLabels() {
        SearchDtos.FacetSet facets = assembler.assemble(
                aggregate(bucket("brand:s:apple", 3), bucket("color:s:black", 2)),
                Map.of("brand", "Thương hiệu"));

        SearchDtos.AttributeFacet brand = facet(facets, "brand");
        assertThat(brand.label()).isEqualTo("Thương hiệu");
        assertThat(brand.options()).extracting(SearchDtos.FacetOption::label).containsExactly("Apple");
        assertThat(brand.options()).extracting(SearchDtos.FacetOption::value).containsExactly("brand:s:apple");
        assertThat(brand.type()).isEqualTo("MULTI_SELECT");
    }

    @Test
    void anAttributeWithoutADescriptorKeepsTheDerivedLabel() {
        SearchDtos.FacetSet facets = assembler.assemble(
                aggregate(bucket("screen_size:n:6", 1)), Map.of("brand", "Thương hiệu"));

        assertThat(facet(facets, "screen_size").label()).isEqualTo("Screen Size");
    }

    @Test
    void aMissingAggregateIsAnEmptyFacetSetWhateverTheLabels() {
        assertThat(assembler.assemble(null, Map.of("brand", "Thương hiệu")).filters()).isEmpty();
    }

    private static SearchDtos.AttributeFacet facet(SearchDtos.FacetSet facets, String code) {
        return facets.filters().stream().filter(item -> item.code().equals(code)).findFirst().orElseThrow();
    }

    private static SearchProperties properties() {
        SearchProperties properties = mock(SearchProperties.class, Answers.RETURNS_DEEP_STUBS);
        when(properties.query().facetMaxAttributes()).thenReturn(100);
        when(properties.query().facetMaxOptions()).thenReturn(50);
        return properties;
    }

    private static Aggregate aggregate(StringTermsBucket... buckets) {
        return Aggregate.of(a -> a.sterms(s -> s
                .sumOtherDocCount(0L)
                .buckets(b -> b.array(List.of(buckets)))));
    }

    private static StringTermsBucket bucket(String token, long distinctProducts) {
        Aggregate cardinality = Aggregate.of(a -> a.cardinality(c -> c.value(distinctProducts)));
        return StringTermsBucket.of(b -> b
                .key(FieldValue.of(token))
                .docCount(distinctProducts)
                .aggregations(ProductQueryBuilder.DISTINCT_PRODUCTS_AGG, cardinality));
    }
}
