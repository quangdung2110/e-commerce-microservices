package com.ecommerce.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.HealthStatus;
import co.elastic.clients.elasticsearch.nodes.Stats;
import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.common.SearchException;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.repository.es.IndexAdmin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What a cluster must offer before a reindex is accepted (WF-SEARCH-009 preconditions). */
class ReindexPreconditionsTest {

    private ElasticsearchClient client;
    private IndexAdmin indexAdmin;
    private SearchProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        client = mock(ElasticsearchClient.class, Answers.RETURNS_DEEP_STUBS);
        indexAdmin = mock(IndexAdmin.class);
        properties = mock(SearchProperties.class);
        when(properties.aliases()).thenReturn(new SearchProperties.Aliases(
                "products", "products_write", "categories", "categories_write", "search_suggestions", "search_jobs"));
        when(indexAdmin.primaryStoreBytes("products")).thenReturn(800L);
        when(indexAdmin.primaryStoreBytes("categories")).thenReturn(200L);
        givenLifecycle(true);
    }

    @SuppressWarnings("unchecked")
    private void givenFreeDisk(long... freeBytesPerNode) throws IOException {
        Map<String, Stats> nodes = new java.util.LinkedHashMap<>();
        for (int i = 0; i < freeBytesPerNode.length; i++) {
            Stats node = mock(Stats.class, Answers.RETURNS_DEEP_STUBS);
            when(node.roles()).thenReturn(List.of());
            when(node.fs().total().availableInBytes()).thenReturn(freeBytesPerNode[i]);
            nodes.put("node-" + i, node);
        }
        when(client.nodes().stats(any(Function.class)).nodes()).thenReturn(nodes);
    }

    private void givenLifecycle(boolean enabled) {
        when(properties.lifecycle()).thenReturn(new SearchProperties.Lifecycle(
                Duration.ofMinutes(2), Duration.ofSeconds(30), Duration.ofSeconds(30), 3, Duration.ofMinutes(2),
                true, enabled, 1.5, Duration.ofHours(24), "0 15 * * * *", true));
    }

    private ReindexPreconditions preconditions() {
        return new ReindexPreconditions(client, indexAdmin, properties);
    }

    @Test
    void aRedClusterIsRefused() throws IOException {
        when(client.cluster().health().status()).thenReturn(HealthStatus.Red);

        assertThatThrownBy(() -> preconditions().check())
                .isInstanceOfSatisfying(SearchException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.REINDEX_PRECONDITION_FAILED);
                    assertThat(e.getMessage()).contains("red");
                });
    }

    @Test
    void aYellowClusterWithRoomForAFullCopyIsAccepted() throws IOException {
        // Yellow is what a single-node cluster permanently reports; it still serves.
        when(client.cluster().health().status()).thenReturn(HealthStatus.Yellow);
        givenFreeDisk(1_500L);

        assertThatCode(() -> preconditions().check()).doesNotThrowAnyException();
    }

    @Test
    void freeDiskBelowTheRatioTimesTheCurrentPrimaryStoreIsRefused() throws IOException {
        when(client.cluster().health().status()).thenReturn(HealthStatus.Green);
        // Primary store is 800 + 200 = 1000 bytes; 1.5 x that is 1500.
        givenFreeDisk(700L, 700L);

        assertThatThrownBy(() -> preconditions().check())
                .isInstanceOfSatisfying(SearchException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.REINDEX_PRECONDITION_FAILED);
                    assertThat(e.code().retryable()).isTrue();
                    assertThat(e.getMessage()).contains("1400").contains("1500");
                });
    }

    @Test
    void theDiskIsSummedOverEveryNodeThatHoldsData() throws IOException {
        when(client.cluster().health().status()).thenReturn(HealthStatus.Green);
        givenFreeDisk(800L, 800L);

        assertThatCode(() -> preconditions().check()).doesNotThrowAnyException();
    }

    @Test
    void theChecksCanBeSwitchedOff() throws IOException {
        givenLifecycle(false);

        preconditions().check();

        verify(client, never()).cluster();
        verify(indexAdmin, never()).primaryStoreBytes(anyString());
    }
}
