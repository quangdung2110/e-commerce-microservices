package com.ecommerce.search.service;

import com.ecommerce.search.domain.document.CategoryDocument;
import com.ecommerce.search.repository.CategoryRepository;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FacetLabelResolverTest {

    private final CategoryRepository repository = mock(CategoryRepository.class);
    private final AtomicLong clock = new AtomicLong();
    private final FacetLabelResolver resolver = new FacetLabelResolver(repository, clock::get);

    @Test
    void keysLabelsByTheFoldedCodeThatAppearsInTokens() {
        Map<String, String> labels = FacetLabelResolver.labelsOf(List.of(
                category("phones", def("Brand", "Thương hiệu"), def("screen size", "Kích thước màn hình"))));

        assertThat(labels).containsEntry("brand", "Thương hiệu").containsEntry("screen size", "Kích thước màn hình");
    }

    @Test
    void theFirstCategoryOfTheScanWinsWhenSnapshotsDisagree() {
        Map<String, String> labels = FacetLabelResolver.labelsOf(List.of(
                category("shallow", def("brand", "Thương hiệu")),
                category("deep", def("brand", "Hãng"))));

        assertThat(labels).containsEntry("brand", "Thương hiệu");
    }

    @Test
    void ignoresBlankLabelsAndCategoriesWithoutDescriptors() {
        Map<String, String> labels = FacetLabelResolver.labelsOf(List.of(
                category("old-producer"),
                category("blank", def("brand", "  "), def(null, "No code"))));

        assertThat(labels).isEmpty();
    }

    @Test
    void readsTheBranchForACategoryAndEverythingForAnUnscopedSearch() throws IOException {
        when(repository.findBranch("root")).thenReturn(List.of(category("phones", def("brand", "Thương hiệu"))));
        when(repository.findAllVisible()).thenReturn(List.of(category("books", def("author", "Tác giả"))));

        assertThat(resolver.labels(Optional.of("root"))).containsOnlyKeys("brand");
        assertThat(resolver.labels(Optional.empty())).containsOnlyKeys("author");
    }

    @Test
    void servesFromCacheUntilTheTtlElapses() throws IOException {
        when(repository.findBranch("root")).thenReturn(List.of(category("phones", def("brand", "Thương hiệu"))));

        resolver.labels(Optional.of("root"));
        clock.addAndGet(FacetLabelResolver.TTL.toNanos() - 1);
        resolver.labels(Optional.of("root"));
        verify(repository, times(1)).findBranch("root");

        clock.addAndGet(1);
        resolver.labels(Optional.of("root"));
        verify(repository, times(2)).findBranch("root");
    }

    @Test
    void evictAllForcesTheNextRequestToRead() throws IOException {
        when(repository.findBranch("root")).thenReturn(List.of());

        resolver.labels(Optional.of("root"));
        resolver.evictAll();
        resolver.labels(Optional.of("root"));

        verify(repository, times(2)).findBranch("root");
    }

    @Test
    void aReadFailureFallsBackToNoLabelsInsteadOfFailingTheFacetRequest() throws IOException {
        when(repository.findBranch("root")).thenThrow(new IOException("es down"));

        assertThat(resolver.labels(Optional.of("root"))).isEmpty();
    }

    @Test
    void aReadFailureKeepsServingTheLastGoodLabelsAndIsNotCached() throws IOException {
        when(repository.findBranch("root"))
                .thenReturn(List.of(category("phones", def("brand", "Thương hiệu"))))
                .thenThrow(new IOException("es down"))
                .thenReturn(List.of(category("phones", def("brand", "Hãng"))));
        resolver.labels(Optional.of("root"));

        clock.addAndGet(FacetLabelResolver.TTL.toNanos());
        assertThat(resolver.labels(Optional.of("root"))).containsEntry("brand", "Thương hiệu");

        assertThat(resolver.labels(Optional.of("root"))).containsEntry("brand", "Hãng");
        verify(repository, never()).findAllVisible();
    }

    private static CategoryDocument.FacetDefinition def(String code, String label) {
        return new CategoryDocument.FacetDefinition(code, label, "TEXT");
    }

    private static CategoryDocument category(String id, CategoryDocument.FacetDefinition... definitions) {
        return new CategoryDocument(id, id, id, null, List.of(id), List.of(id), 0, true,
                definitions.length == 0 ? null : List.of(definitions),
                null, null, false, null, null, null, 1L, "evt");
    }
}
