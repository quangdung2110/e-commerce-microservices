package com.ecommerce.search.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Suggestion normalisation and identity, {@code docs/search/data.md §1.2}. */
class SuggestionTextTest {

    @Test
    void foldsVietnameseDiacriticsSoAccentedAndPlainInputAgree() {
        assertThat(SuggestionText.normalize("Điện Thoại")).isEqualTo("dien thoai");
        assertThat(SuggestionText.normalize("dien thoai")).isEqualTo("dien thoai");
    }

    @Test
    void collapsesWhitespaceAndCaseButKeepsPunctuation() {
        assertThat(SuggestionText.normalize("  iPhone   15  Pro-Max! ")).isEqualTo("iphone 15 pro-max!");
    }

    @Test
    void normalisesToEmptyWhenNothingSurvives() {
        assertThat(SuggestionText.normalize("   ")).isEmpty();
        assertThat(SuggestionText.normalize(null)).isEmpty();
    }

    @Test
    void keepsTheSameTextUnderDifferentTypesAsTwoSuggestions() {
        // SEARCH-BR-014: the dropdown must be able to show both kinds.
        String asProduct = SuggestionText.suggestionId("PRODUCT", "GLOBAL", "vi", "dien thoai");
        String asCategory = SuggestionText.suggestionId("CATEGORY", "GLOBAL", "vi", "dien thoai");

        assertThat(asProduct).isNotEqualTo(asCategory);
    }

    @Test
    void isDeterministicAcrossBuilds() {
        assertThat(SuggestionText.suggestionId("PRODUCT", "GLOBAL", "vi", "iphone 15"))
                .isEqualTo(SuggestionText.suggestionId("PRODUCT", "GLOBAL", "vi", "iphone 15"))
                .hasSize(64);
    }
}
