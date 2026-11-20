package com.ecommerce.search.worker.job;

import com.ecommerce.search.common.SearchMetrics;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.document.LeaseLockDocument;
import com.ecommerce.search.repository.JobStateRepository;
import com.ecommerce.search.repository.es.IndexAdmin;
import com.ecommerce.search.worker.productcards.ProductCardProjector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Retention must protect both a live PIT and an in-progress private build. */
class ProductCardCleanupTest {
    @Test void aRetiredGenerationWithAnOpenPitIsKept() throws Exception { run(true, false); }
    @Test void aGenerationWithoutPitsIsDeletedAfterRetention() throws Exception { run(false, false); }
    @Test void aProjectorLeaseProtectsPrivateGenerationsBeforeTheyAreCheckpointed() throws Exception { run(false, true); }

    private void run(boolean openPit, boolean projectorActive) throws Exception {
        var indices = mock(IndexAdmin.class);
        var jobs = mock(JobStateRepository.class);
        var properties = mock(SearchProperties.class, Answers.RETURNS_DEEP_STUBS);
        when(properties.lifecycle().oldIndexRetention()).thenReturn(Duration.ofHours(24));
        when(properties.lifecycle().leaseDuration()).thenReturn(Duration.ofMinutes(2));
        when(properties.aliases().products()).thenReturn("products");
        when(properties.aliases().categories()).thenReturn("categories");
        when(properties.aliases().searchSuggestions()).thenReturn("search_suggestions");
        var lease = new LeaseLockDocument("LEASE_LOCK", "index-cleanup", "test", Instant.now().plusSeconds(120), 1L, Instant.now());
        when(jobs.acquireLease(eq("index-cleanup"), anyString(), any(), any())).thenReturn(Optional.of(lease));
        when(jobs.retiredIndices()).thenReturn(new HashMap<>(java.util.Map.of("product_cards_v1", Instant.now().minus(Duration.ofDays(2)))));
        when(indices.versionedIndices("product_cards")).thenReturn(List.of("product_cards_v1", "product_cards_v2"));
        when(indices.resolveAlias("product_cards")).thenReturn(Optional.of("product_cards_v2"));
        when(indices.indexExists(anyString())).thenReturn(true);
        when(indices.hasOpenSearchContexts("product_cards_v1")).thenReturn(openPit);
        if (projectorActive) when(jobs.readLease(ProductCardProjector.LEASE)).thenReturn(Optional.of(lease));
        new IndexCleanupJob(indices, jobs, properties, new SearchMetrics(new SimpleMeterRegistry())).run();
        if (openPit || projectorActive) verify(indices, never()).deleteIndex("product_cards_v1");
        else verify(indices).deleteIndex("product_cards_v1");
    }
}
