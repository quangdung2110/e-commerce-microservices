package com.ecommerce.search.worker.job;

import com.ecommerce.search.config.SearchProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Suggestion weighting and dedupe tie-breaks, {@code docs/search/data.md §1.2}. */
class SuggestionCandidateTest {

    private final SearchProperties.Suggestions config = new SearchProperties.Suggestions(
            "0 0 3 * * *", true, 1L, 0.80,
            Duration.ofMinutes(5), Duration.ofMinutes(5), Duration.ofMinutes(1),
            5000, 1000, 1.10, 9_000_000_000_000_000_000L, "vi", "GLOBAL", List.of());

    @Test
    void categoryWeightFollowsTheLogarithmOfProductCount() {
        long expected = (long) Math.floor(5000 * Math.log1p(120));

        assertThat(SuggestionCandidate.category("c1", "Điện thoại", 120, config).weight())
                .isEqualTo(expected);
    }

    @Test
    void productWithoutReviewsStillGetsACandidateAtWeightZero() {
        SuggestionCandidate candidate = SuggestionCandidate.product("p1", "iPhone 15", null, null, false, config);

        assertThat(candidate.weight()).isZero();
        assertThat(candidate.textNormalized()).isEqualTo("iphone 15");
    }

    @Test
    void promotionAddsAtMostTenPercent() {
        SuggestionCandidate plain = SuggestionCandidate.product("p1", "iPhone", 4.5, 100L, false, config);
        SuggestionCandidate promoted = SuggestionCandidate.product("p1", "iPhone", 4.5, 100L, true, config);

        assertThat(promoted.weight()).isGreaterThan(plain.weight());
        assertThat(promoted.weight()).isLessThanOrEqualTo((long) Math.ceil(plain.weight() * 1.10));
    }

    @Test
    void ratingAndReviewCountBothRaiseTheWeight() {
        long low = SuggestionCandidate.product("p1", "A", 3.0, 10L, false, config).weight();
        long betterRating = SuggestionCandidate.product("p1", "A", 4.8, 10L, false, config).weight();
        long moreReviews = SuggestionCandidate.product("p1", "A", 3.0, 1000L, false, config).weight();

        assertThat(betterRating).isGreaterThan(low);
        assertThat(moreReviews).isGreaterThan(low);
    }

    @Test
    void dedupeKeepsTheHighestWeightAndNeverSumsDuplicates() {
        // SEARCH-BR-015: two products with the same name must not add up.
        SuggestionCandidate weaker = SuggestionCandidate.product("p1", "iPhone", 3.0, 10L, false, config);
        SuggestionCandidate stronger = SuggestionCandidate.product("p2", "iPhone", 4.8, 500L, false, config);

        SuggestionCandidate winner = weaker.betterOf(stronger);

        assertThat(winner.weight()).isEqualTo(stronger.weight());
        assertThat(winner.weight()).isLessThan(weaker.weight() + stronger.weight());
    }

    @Test
    void aTiedGroupIsResolvedDeterministicallyByTextThenSourceId() {
        SuggestionCandidate second = SuggestionCandidate.product("p2", "iPhone", null, null, false, config);
        SuggestionCandidate first = SuggestionCandidate.product("p1", "iPhone", null, null, false, config);

        assertThat(second.betterOf(first).sourceId()).isEqualTo("p1");
        assertThat(first.betterOf(second).sourceId()).isEqualTo("p1");
    }

    @Test
    void accentAndCaseVariantsOfOneTypeShareADedupeKey() {
        SuggestionCandidate accented = SuggestionCandidate.category("c1", "Điện Thoại", 5, config);
        SuggestionCandidate plain = SuggestionCandidate.category("c2", "dien thoai", 5, config);

        assertThat(accented.dedupeKey("GLOBAL", "vi")).isEqualTo(plain.dedupeKey("GLOBAL", "vi"));
    }

    @Test
    void productAndCategoryWithTheSameTextStayTwoEntries() {
        SuggestionCandidate category = SuggestionCandidate.category("c1", "iPhone", 5, config);
        SuggestionCandidate product = SuggestionCandidate.product("p1", "iPhone", 4.0, 10L, false, config);

        assertThat(category.dedupeKey("GLOBAL", "vi")).isNotEqualTo(product.dedupeKey("GLOBAL", "vi"));
    }
}
