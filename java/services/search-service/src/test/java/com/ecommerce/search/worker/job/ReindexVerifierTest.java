package com.ecommerce.search.worker.job;

import com.ecommerce.search.domain.document.ProductDocument;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What the sample diff counts as a wrong SKU, and what it leaves to the tail replay. */
class ReindexVerifierTest {

    @Test
    void anIdenticalDocumentIsFine() {
        assertThat(ReindexVerifier.compare(doc(5L, 3L, 2L, 100L, "ACTIVE"), doc(5L, 3L, 2L, 100L, "ACTIVE"))).isNull();
    }

    @Test
    void aNewerRevisionInTheNewIndexIsFine() {
        // The export plus catch-up may be ahead of the index that is still serving.
        assertThat(ReindexVerifier.compare(doc(5L, 3L, 2L, 100L, "ACTIVE"), doc(6L, 4L, 3L, 90L, "HIDDEN"))).isNull();
    }

    @Test
    void aLowerCatalogRevisionIsAnError() {
        assertThat(ReindexVerifier.compare(doc(5L, 3L, 2L, 100L, "ACTIVE"), doc(4L, 3L, 2L, 100L, "ACTIVE")))
                .contains("catalog_revision 4").contains("has 5");
    }

    @Test
    void aMissingAvailabilityOrReviewRevisionIsAnErrorWhenTheCurrentIndexHasOne() {
        assertThat(ReindexVerifier.compare(doc(5L, 3L, 2L, 100L, "ACTIVE"), doc(5L, null, 2L, 100L, "ACTIVE")))
                .contains("availability_revision");
        assertThat(ReindexVerifier.compare(doc(5L, 3L, 2L, 100L, "ACTIVE"), doc(5L, 3L, null, 100L, "ACTIVE")))
                .contains("review_revision");
    }

    @Test
    void aSkuTheCurrentIndexNeverHadAvailabilityForMayHaveAny() {
        assertThat(ReindexVerifier.compare(doc(5L, null, null, 100L, "ACTIVE"), doc(5L, 1L, 1L, 100L, "ACTIVE")))
                .isNull();
    }

    @Test
    void aDifferentPriceAtTheSameCatalogRevisionIsAnError() {
        assertThat(ReindexVerifier.compare(doc(5L, 3L, 2L, 100L, "ACTIVE"), doc(5L, 3L, 2L, 120L, "ACTIVE")))
                .contains("price or status").contains("5");
    }

    @Test
    void aDifferentStatusAtTheSameCatalogRevisionIsAnError() {
        assertThat(ReindexVerifier.compare(doc(5L, 3L, 2L, 100L, "ACTIVE"), doc(5L, 3L, 2L, 100L, "HIDDEN")))
                .contains("price or status");
    }

    private static ProductDocument doc(Long catalog, Long availability, Long review, long price, String status) {
        return new ProductDocument("sku", "code", "prod", "slug", "cat", "cat", "Cat", java.util.List.of(),
                java.util.List.of(), "name", "desc", null, status, "ACTIVE", java.util.List.of(), java.util.List.of(),
                java.util.List.of(), price + 10, price, true, true, 0.0, 0L, false, null, null, null, null, null,
                null, null, catalog, availability, review, null, null, null);
    }
}
