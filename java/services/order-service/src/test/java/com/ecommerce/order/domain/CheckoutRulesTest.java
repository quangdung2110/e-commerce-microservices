package com.ecommerce.order.domain;

import com.ecommerce.order.support.Fixtures;
import com.ecommerce.order.web.dto.CheckoutDtos.CheckoutOrderSubmissionRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckoutRulesTest {

    @Test
    void validSnapshotHasNoErrors() {
        CheckoutOrderSubmissionRequest request = Fixtures.submission(Fixtures.defaultLines(), 10_000, "promo-1");
        assertThat(CheckoutRules.validate(request)).isEmpty();
    }

    @Test
    void hashesAreRecomputedFromCanonicalJson() {
        CheckoutOrderSubmissionRequest request = Fixtures.submission(Fixtures.defaultLines(), 0, null);
        assertThat(request.pricingHash()).startsWith("sha256:").hasSize(71);
        CheckoutOrderSubmissionRequest tampered = Fixtures.withPricingHash(request,
                "sha256:" + "0".repeat(64));
        assertThat(CheckoutRules.validate(tampered)).anyMatch(e -> e.field().equals("pricing_hash"));
    }

    @Test
    void itemsHashSortsBySkuBytes() {
        // Same items in another order produce the same canonical hash.
        List<Fixtures.LineSpec> lines = Fixtures.defaultLines();
        CheckoutOrderSubmissionRequest a = Fixtures.submission(lines, 0, null);
        CheckoutOrderSubmissionRequest b = Fixtures.submission(List.of(lines.get(2), lines.get(0), lines.get(1)), 0, null);
        assertThat(CheckoutRules.itemsHash(a)).isEqualTo(CheckoutRules.itemsHash(b));
    }

    @Test
    void promotionRevisionMustBeZeroIffNoPromotion() {
        CheckoutOrderSubmissionRequest request = Fixtures.withRevision(Fixtures.submission(Fixtures.defaultLines(), 0, null), 5);
        assertThat(CheckoutRules.validate(request)).anyMatch(e -> e.field().equals("promotion_campaign_revision"));
    }

    @Test
    void amountsMustMatch() {
        CheckoutOrderSubmissionRequest request = Fixtures.withFinal(Fixtures.submission(Fixtures.defaultLines(), 0, null), 1);
        assertThat(CheckoutRules.validate(request)).anyMatch(e -> e.field().equals("pricing.final_amount"));
    }
}
