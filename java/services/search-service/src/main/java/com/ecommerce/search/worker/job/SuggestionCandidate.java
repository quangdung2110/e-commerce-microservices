package com.ecommerce.search.worker.job;

import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.model.SuggestionText;

/**
 * One candidate phrase before deduplication.
 *
 * <p>A Category contributes one candidate; a Product contributes one candidate for the
 * whole product, not one per SKU or per variant attribute ({@code docs/search/data.md
 * §1.1}).
 */
public record SuggestionCandidate(
        String type,
        String text,
        String textNormalized,
        String sourceId,
        long productCount,
        double ratingAvg,
        long ratingCount,
        boolean hasPromotion,
        long weight) {

    /** Separator that cannot occur in a normalised phrase. */
    private static final String KEY_SEPARATOR = String.valueOf((char) 1);

    public static final String TYPE_CATEGORY = "CATEGORY";
    public static final String TYPE_PRODUCT = "PRODUCT";

    /** {@code floor(category_weight_scale × ln(1 + product_count))}. */
    public static SuggestionCandidate category(String categoryId, String name, long productCount,
                                               SearchProperties.Suggestions config) {
        long weight = (long) Math.floor(config.categoryWeightScale() * Math.log1p(productCount));
        return new SuggestionCandidate(TYPE_CATEGORY, name, SuggestionText.normalize(name), categoryId,
                productCount, 0, 0, false, Math.min(config.maxWeight(), Math.max(0, weight)));
    }

    /**
     * {@code min(max_weight, floor(product_weight_scale × rating_avg × ln(1 + rating_count)
     * × (has_promotion ? promotion_multiplier : 1)))}.
     *
     * <p>Null rating values count as zero, so a product with no reviews still gets a
     * suggestion — it simply ranks behind anything with a positive score, ordered by text.
     * The logarithm keeps a very large review count from dominating, and a promotion adds
     * at most ten percent.
     */
    public static SuggestionCandidate product(String productId, String name, Double ratingAvg, Long ratingCount,
                                              boolean hasPromotion, SearchProperties.Suggestions config) {
        double avg = ratingAvg == null ? 0.0 : ratingAvg;
        long count = ratingCount == null ? 0L : ratingCount;
        double base = Math.floor(config.productWeightScale() * avg * Math.log1p(count));
        double multiplied = base * (hasPromotion ? config.promotionMultiplier() : 1.0);
        long weight = Math.min(config.maxWeight(), (long) Math.max(0, Math.floor(multiplied)));
        return new SuggestionCandidate(TYPE_PRODUCT, name, SuggestionText.normalize(name), productId,
                0, avg, count, hasPromotion, weight);
    }

    /**
     * Deterministic winner inside a dedupe group: highest weight, then the smaller
     * {@code text}, then the smaller source identifier. Weights are never summed — having
     * several products with the same name must not buy the phrase a ranking advantage
     * ({@code SEARCH-BR-015}).
     */
    public SuggestionCandidate betterOf(SuggestionCandidate other) {
        if (other == null) {
            return this;
        }
        if (weight != other.weight) {
            return weight > other.weight ? this : other;
        }
        int byText = text.compareTo(other.text);
        if (byText != 0) {
            return byText < 0 ? this : other;
        }
        return sourceId.compareTo(other.sourceId) <= 0 ? this : other;
    }

    /** The dedupe key of {@code SEARCH-BR-014}: type, scope, language and normalised text. */
    public String dedupeKey(String scope, String language) {
        return String.join(KEY_SEPARATOR, type, scope, language, textNormalized);
    }
}
