package com.ecommerce.search.worker.productcards;

import com.ecommerce.search.config.ProductIndexProperties;
import com.ecommerce.search.domain.document.LeaseLockDocument;
import com.ecommerce.search.repository.JobStateRepository;
import com.ecommerce.search.repository.es.ProductCardIndex;
import com.ecommerce.search.repository.es.ProductCardStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** ADR-027: how the projector keeps product_cards in step with the SKU index. */
class ProductCardProjectorTest {

    private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");

    private final ProductCardStore store = mock(ProductCardStore.class);
    private final ProductCardIndex index = mock(ProductCardIndex.class);
    private final JobStateRepository jobs = mock(JobStateRepository.class);
    private final ProductIndexProperties properties = new ProductIndexProperties(true, Duration.ofSeconds(1),
            Duration.ofSeconds(10), 2000, 2, Duration.ofSeconds(5), Duration.ofSeconds(30));
    private ProductCardProjector projector;

    @BeforeEach
    void setUp() throws IOException {
        projector = new ProductCardProjector(store, index, jobs, properties, new SimpleMeterRegistry(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        LeaseLockDocument lease = new LeaseLockDocument(LeaseLockDocument.DOC_KIND, ProductCardProjector.LEASE,
                "owner", NOW.plusSeconds(30), 1L, NOW);
        when(jobs.acquireLease(eq(ProductCardProjector.LEASE), anyString(), any(), any())).thenReturn(Optional.of(lease));
        when(jobs.renewLease(any(), any(), any())).thenReturn(true);
        when(index.currentSourceIndex()).thenReturn(Optional.of("products_v2"));
        when(index.currentGeneration()).thenReturn(Optional.of("product_cards_v2"));
    }

    @Test
    void doesNothingWithoutTheLease() throws IOException {
        when(jobs.acquireLease(eq(ProductCardProjector.LEASE), anyString(), any(), any())).thenReturn(Optional.empty());

        projector.pollOnce();

        verifyNoInteractions(store);
        verify(index, never()).currentSourceIndex();
    }

    @Test
    void buildsANewGenerationWhenNoneMirrorsTheCurrentSkuIndex() throws IOException {
        when(index.mirroredSourceIndex()).thenReturn(Optional.of("products_v1"));
        when(index.createGeneration("products_v2")).thenReturn("product_cards_v3");
        doAnswer(invocation -> {
            Consumer<ProductCard> sink = invocation.getArgument(1);
            sink.accept(ProductCard.of("p-1", List.of(sku("p-1", "s-1", false))));
            sink.accept(ProductCard.of("p-2", List.of(sku("p-2", "s-2", true))));   // tombstoned: no card
            sink.accept(ProductCard.of("p-3", List.of(sku("p-3", "s-3", false))));
            return null;
        }).when(store).scanAllProducts(eq("products_v2"), any(), any());

        projector.pollOnce();

        verify(store).write(eq("product_cards_v3"), eq(List.of(
                ProductCard.of("p-1", List.of(sku("p-1", "s-1", false))),
                ProductCard.of("p-3", List.of(sku("p-3", "s-3", false))))));
        verify(index).publish("product_cards_v3");
        verify(index).recordProgress("product_cards_v3", "products_v2", NOW.minusSeconds(10));
        verify(jobs, org.mockito.Mockito.atLeastOnce()).releaseLease(any());
    }

    @Test
    void dropsTheGenerationWhenTheBuildFails() throws IOException {
        when(index.mirroredSourceIndex()).thenReturn(Optional.empty());
        when(index.createGeneration("products_v2")).thenReturn("product_cards_v1");
        doAnswer(invocation -> {
            throw new IOException("scan failed");
        }).when(store).scanAllProducts(eq("products_v2"), any(), any());

        try {
            projector.pollOnce();
        } catch (IOException expected) {
            // the loop logs and retries on the next poll
        }

        verify(index).discard("product_cards_v1");
        verify(index, never()).publish(anyString());
    }

    @Test
    void rebuildsChangedProductsOnceEvenWhenOverlappingPollsSeeTheSameChangeAgain() throws IOException {
        when(index.mirroredSourceIndex()).thenReturn(Optional.of("products_v2"));
        when(index.recordedProgress()).thenReturn(Optional.of(NOW.minusSeconds(60)));
        long writtenAt = NOW.minusSeconds(5).toEpochMilli();
        when(store.changes(eq("products_v2"), eq(NOW.minusSeconds(70)), any())).thenReturn(List.of(
                new ProductCardStore.Change("p-1", writtenAt, List.of()),
                new ProductCardStore.Change("p-1", writtenAt, List.of())));
        when(store.changes(eq("products_v2"), eq(NOW.minusSeconds(15)), any())).thenReturn(List.of(
                new ProductCardStore.Change("p-1", writtenAt, List.of())));
        ProductCard rebuilt = new ProductCard("p-1", writtenAt, List.of(sku("p-1", "s-1", false)));
        when(store.loadProducts(eq("products_v2"), anyCollection())).thenReturn(List.of(rebuilt));

        projector.pollOnce();
        projector.pollOnce();

        verify(store, times(1)).loadProducts("products_v2", List.of("p-1"));
        verify(store, times(1)).write("product_cards_v2", List.of(rebuilt));
        verify(store, never()).scanAllProducts(anyString(), any(), any());
    }

    @Test
    void rebuildsInBatchesOfTheConfiguredSize() throws IOException {
        when(index.mirroredSourceIndex()).thenReturn(Optional.of("products_v2"));
        when(index.recordedProgress()).thenReturn(Optional.of(NOW));
        long at = NOW.toEpochMilli();
        when(store.changes(eq("products_v2"), any(), any())).thenReturn(List.of(
                new ProductCardStore.Change("p-1", at, List.of()), new ProductCardStore.Change("p-2", at, List.of()),
                new ProductCardStore.Change("p-3", at, List.of())));
        when(store.loadProducts(eq("products_v2"), anyCollection())).thenReturn(List.of());

        projector.pollOnce();

        verify(store).loadProducts("products_v2", List.of("p-1", "p-2"));
        verify(store).loadProducts("products_v2", List.of("p-3"));
        verify(store, times(2)).write(eq("product_cards_v2"), anyList());
        assertThat(properties.rebuildBatch()).isEqualTo(2);
    }

    @Test
    void aPreparedCutoverDoesNotPublishAndIsDiscardedIfTheCallerAborts() throws IOException {
        when(index.createGeneration("products_v2")).thenReturn("product_cards_v9");
        try (var prepared = projector.prepareForCutover("products_v2", () -> {})) {
            assertThat(prepared.index()).isEqualTo("product_cards_v9");
            verify(index).recordProgress("product_cards_v9", "products_v2", NOW.minusSeconds(10));
            verify(index, never()).publish(anyString());
        }
        verify(index).discard("product_cards_v9");
        verify(jobs).releaseLease(any());
    }

    @Test
    void leaseLossAbortsThePrivateBuildWithoutPublishing() throws IOException {
        when(index.createGeneration("products_v2")).thenReturn("product_cards_v9");
        when(jobs.renewLease(any(), any(), any())).thenReturn(false);
        assertThatThrownBy(() -> projector.prepareForCutover("products_v2", () -> {}))
                .isInstanceOf(IOException.class).hasMessageContaining("lost");
        verify(index).discard("product_cards_v9");
        verify(index, never()).publish(anyString());
        verify(store, never()).write(anyString(), anyList());
    }

    @Test
    void bootstrapNeverPublishesCardsFromASourceThatStoppedServingDuringTheBuild() throws IOException {
        when(index.currentSourceIndex()).thenReturn(Optional.of("products_v2"), Optional.of("products_v3"));
        when(index.createGeneration("products_v2")).thenReturn("product_cards_v9");
        assertThatThrownBy(projector::pollOnce).isInstanceOf(IOException.class).hasMessageContaining("alias moved");
        verify(index, never()).publish(anyString());
        verify(index).discard("product_cards_v9");
    }

    private static ObjectNode sku(String productId, String skuId, boolean deleted) {
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("product_id", productId);
        node.put("sku_id", skuId);
        node.put("is_deleted", deleted);
        node.put("indexed_at", "2026-10-16T09:59:00.000Z");
        return node;
    }
}
