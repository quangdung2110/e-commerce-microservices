package com.ecommerce.search.controller.api;

import com.ecommerce.search.dto.SuggestResponse;
import com.ecommerce.search.service.SuggestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the autocomplete/search-as-you-type API
 * ({@code GET /api/v1/search/suggest}).
 *
 * <p>Returns ranked suggestions as the user types in the search box.
 * Results come from the pre-computed {@code search_suggestions} index
 * which is rebuilt atomically on a daily cadence.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/search/suggest")
@RequiredArgsConstructor
public class SuggestController {

    private final SuggestService suggestService;

    /**
     * Get autocomplete suggestions for the given prefix.
     *
     * @param q     the user's current input (required, at least 2 characters)
     * @param limit max number of suggestions (optional, default 8, max 20)
     * @return a {@link SuggestResponse} with ranked suggestions
     */
    @GetMapping
    public ResponseEntity<SuggestResponse> suggest(
            @RequestParam String q,
            @RequestParam(required = false) Integer limit) {

        log.debug("GET /api/v1/search/suggest?q={}&limit={}", q, limit);
        SuggestResponse response = suggestService.suggest(q, limit);
        return ResponseEntity.ok(response);
    }
}
