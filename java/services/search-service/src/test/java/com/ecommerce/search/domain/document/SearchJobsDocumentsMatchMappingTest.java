package com.ecommerce.search.domain.document;

import com.ecommerce.search.config.ElasticsearchConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code search_jobs} is {@code dynamic: strict}: one field the mapping does not declare makes
 * the whole write fail. Jackson turns any {@code isXxx()} method of a record into such a field
 * ({@code ReindexRunDocument.isTerminal()} once did, which broke every reindex trigger), so the
 * JSON of every document written there is checked against the canonical mapping.
 */
class SearchJobsDocumentsMatchMappingTest {

    private static final ObjectMapper MAPPER = new ElasticsearchConfig().esObjectMapper();

    @Test
    void everyFieldOfEveryDocumentWrittenToSearchJobsIsDeclaredByTheMapping() throws IOException {
        Set<String> declared = declaredFields();
        Instant now = Instant.parse("2026-10-10T00:00:00Z");

        Map<String, Object> documents = new java.util.LinkedHashMap<>();
        documents.put("run", new ReindexRunDocument(ReindexRunDocument.DOC_KIND, "run", "BUILDING", "BUILDING",
                "reason", "actor", now, now, now, "CODE", "message", "products_v2", Map.of("a", 1),
                List.of(Map.of("category", List.of())), Map.of("stage", "BUILD_CATALOG")));
        documents.put("request", new ReindexRequestDocument(ReindexRequestDocument.DOC_KIND, "reason", now, now,
                Map.of("roots", Map.of("r", 1))));
        documents.put("retired", new IndexRetiredDocument(IndexRetiredDocument.DOC_KIND, "products_v1", "run", now));
        documents.put("cancel", new ReindexCancelDocument(ReindexCancelDocument.DOC_KIND, "run", "actor", now));
        documents.put("lease", new LeaseLockDocument(LeaseLockDocument.DOC_KIND, "full-reindex", "run", now, 3L, now));

        documents.forEach((name, document) -> {
            Set<String> written = new LinkedHashSet<>();
            MAPPER.valueToTree(document).fieldNames().forEachRemaining(written::add);
            assertThat(declared).as("fields of the %s document", name).containsAll(written);
        });
    }

    private static Set<String> declaredFields() throws IOException {
        try (var in = new ClassPathResource("search/schema/search_jobs.index.json").getInputStream()) {
            JsonNode properties = MAPPER.readTree(in).path("mappings").path("properties");
            Set<String> fields = new LinkedHashSet<>();
            properties.fieldNames().forEachRemaining(fields::add);
            return fields;
        }
    }
}
