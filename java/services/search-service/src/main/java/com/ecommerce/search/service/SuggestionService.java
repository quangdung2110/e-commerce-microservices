package com.ecommerce.search.service;

import com.ecommerce.search.domain.document.SuggestionDocument;
import com.ecommerce.search.dto.SearchDtos;
import com.ecommerce.search.repository.SuggestionRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * {@code WF-SEARCH-002} — the search-as-you-type dropdown.
 *
 * <p>The public response exposes only {@code text} and {@code type}: the internal
 * {@code weight} is a ranking detail and the source identifiers are not exposed, because a
 * suggestion completes a query rather than linking to an entity.
 */
@Service
public class SuggestionService {

    private final SuggestionRepository repository;

    public SuggestionService(SuggestionRepository repository) {
        this.repository = repository;
    }

    public SearchDtos.SuggestionList suggest(String prefix, int limit) throws IOException {
        List<SuggestionDocument> documents = repository.suggest(prefix, limit);
        return new SearchDtos.SuggestionList(documents.stream()
                .map(doc -> new SearchDtos.Suggestion(doc.text(), doc.type()))
                .toList());
    }
}
