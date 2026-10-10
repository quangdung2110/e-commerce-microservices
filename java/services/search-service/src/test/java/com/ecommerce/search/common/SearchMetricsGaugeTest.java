package com.ecommerce.search.common;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code docs/search/operations.md §4} lists gauges that dashboards and alerts read after the
 * job that set them has long finished.
 */
class SearchMetricsGaugeTest {

    @Test
    void gaugesKeepTheirValueAfterGarbageCollection() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SearchMetrics metrics = new SearchMetrics(registry);

        metrics.reindexState("BUILDING");
        metrics.snapshotAge("review", 42.0);
        metrics.snapshotSessionTtl(3600.0);
        metrics.bulkInFlight("job", 1);

        for (int i = 0; i < 5; i++) {
            System.gc();
        }

        assertThat(registry.get("search_reindex_state").tag("phase", "BUILDING").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("search_review_snapshot_age_seconds").gauge().value()).isEqualTo(42.0);
        assertThat(registry.get("search_inventory_snapshot_session_ttl_seconds").gauge().value()).isEqualTo(3600.0);
        assertThat(registry.get("search_es_bulk_in_flight").tag("job", "job").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void exactlyOneReindexPhaseIsCurrent() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SearchMetrics metrics = new SearchMetrics(registry);

        metrics.reindexState("BUILDING");
        metrics.reindexState("CATCHING_UP");

        assertThat(registry.get("search_reindex_state").tag("phase", "BUILDING").gauge().value()).isEqualTo(0.0);
        assertThat(registry.get("search_reindex_state").tag("phase", "CATCHING_UP").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("search_reindex_state").gauges().stream().mapToDouble(g -> g.value()).sum())
                .isEqualTo(1.0);
    }

    @Test
    void aGaugeUpdatedLaterReportsTheNewValue() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SearchMetrics metrics = new SearchMetrics(registry);

        metrics.bulkInFlight("job", 1);
        metrics.bulkInFlight("job", 0);

        assertThat(registry.get("search_es_bulk_in_flight").tag("job", "job").gauge().value()).isEqualTo(0.0);
    }
}
