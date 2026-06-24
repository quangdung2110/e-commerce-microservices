package com.ecommerce.search.controller.api;

import com.ecommerce.search.dto.FacetResponse;
import com.ecommerce.search.service.FacetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the facet API
 * ({@code GET /api/v1/search/facets/{categoryId}}).
 *
 * <p>Returns all available filter groups (brands, price ranges, dynamic
 * attributes) and their product counts for a given category. Used by the
 * frontend to render the filter sidebar on category pages.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/search/facets")
@RequiredArgsConstructor
public class FacetController {

    private final FacetService facetService;

    /**
     * Get all facet filters for the specified category.
     *
     * @param categoryId the category ID to scope facets to
     * @return a {@link FacetResponse} with grouped filters and counts
     */
    @GetMapping("/{categoryId}")
    public ResponseEntity<FacetResponse> getFacets(
            @PathVariable String categoryId) {

        log.debug("GET /api/v1/search/facets/{}", categoryId);
        FacetResponse response = facetService.getFacets(categoryId);
        return ResponseEntity.ok(response);
    }
}
