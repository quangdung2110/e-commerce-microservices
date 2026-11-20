package com.ecommerce.search.repository.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.UpdateByQueryResponse;
import com.ecommerce.search.config.SearchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.io.IOException;
import java.time.Duration;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An update-by-query works on the last refreshed view, so a SKU written inside the refresh
 * interval conflicts and, with {@code conflicts=proceed}, is silently dropped. The writer
 * refreshes and repeats instead of reporting success.
 */
class ProjectionWriterRatingConflictTest {

    private ElasticsearchClient client;
    private ProjectionWriter writer;

    @BeforeEach
    void setUp() {
        client = mock(ElasticsearchClient.class, Answers.RETURNS_DEEP_STUBS);
        SearchProperties properties = mock(SearchProperties.class);
        when(properties.bulk()).thenReturn(new SearchProperties.Bulk(1000, 5_242_880L, 4, 20, 0.5, 1.1, 20, 8,
                Duration.ofMillis(500), Duration.ofSeconds(30), 300f));
        when(properties.query()).thenReturn(new SearchProperties.QueryBudget(Duration.ofSeconds(5),
                Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofMinutes(2), 1000, 50, 100));
        writer = new ProjectionWriter(client, new PainlessScripts(), properties);
    }

    @SuppressWarnings("unchecked")
    private void givenConflicts(Long... perCall) throws IOException {
        UpdateByQueryResponse first = response(perCall[0]);
        UpdateByQueryResponse[] rest = new UpdateByQueryResponse[perCall.length - 1];
        for (int i = 1; i < perCall.length; i++) {
            rest[i - 1] = response(perCall[i]);
        }
        when(client.updateByQuery(any(Function.class))).thenReturn(first, rest);
    }

    private static UpdateByQueryResponse response(Long conflicts) {
        UpdateByQueryResponse response = mock(UpdateByQueryResponse.class);
        when(response.versionConflicts()).thenReturn(conflicts);
        return response;
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCleanUpdateIsNotRepeated() throws IOException {
        givenConflicts(0L);

        writer.reviewMetricsPatch("products_write", "prod", 4.5, 10, 3, "2026-10-10T00:00:00Z", "evt");

        verify(client, times(1)).updateByQuery(any(Function.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aConflictIsRepeatedAfterARefreshUntilItClears() throws IOException {
        givenConflicts(1L, 1L, 0L);

        UpdateByQueryResponse last = writer.reviewMetricsPatch(
                "products_write", "prod", 4.5, 10, 3, "2026-10-10T00:00:00Z", "evt");

        assertThat(last.versionConflicts()).isZero();
        verify(client, times(3)).updateByQuery(any(Function.class));
        verify(client.indices(), times(2)).refresh(any(Function.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aConflictThatNeverClearsIsAnErrorTheCallerRetries() throws IOException {
        givenConflicts(1L);

        assertThatThrownBy(() -> writer.reviewMetricsPatch(
                "products_write", "prod", 4.5, 10, 3, "2026-10-10T00:00:00Z", "evt"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("version conflicts");
        verify(client, times(ProjectionWriter.MAX_CONFLICT_RETRIES + 1)).updateByQuery(any(Function.class));
    }
}
