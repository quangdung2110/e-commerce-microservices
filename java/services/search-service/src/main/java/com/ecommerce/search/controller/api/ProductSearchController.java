package com.ecommerce.search.controller.api;

import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.dto.ProductSearchResponse;
import com.ecommerce.search.service.ProductSearchService;
import com.ecommerce.search.service.SearchParams;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for the product search API
 * ({@code GET /api/v1/search/products}).
 *
 * <p>The primary search endpoint serving category browsing, full-text search,
 * and product discovery with cursor-based pagination and dynamic facets.
 *
 * <p>Dynamic filters are passed as {@code filters[key]=value} query parameters
 * (e.g. {@code filters[brand]=Apple&filters[color]=Đen}) and are extracted
 * from the raw query string.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/search/products")
@RequiredArgsConstructor
public class ProductSearchController {

    private final ProductSearchService productSearchService;
    private final SearchProperties properties;

    /**
     * Execute a product search with the given parameters.
     *
     * @param q            full-text search query (optional)
     * @param category     category ID to filter by (optional)
     * @param priceMin     minimum price (optional)
     * @param priceMax     maximum price (optional)
     * @param inStock      only show in-stock items (optional, defaults to true)
     * @param hasPromotion only show promoted items (optional)
     * @param sort         sort order (relevance/price_asc/price_desc/newest/rating)
     * @param cursor       base64-encoded pagination cursor (optional)
     * @param size         results per page (optional, default 20, max 100)
     * @param request      the raw HTTP request (used to extract dynamic filters)
     * @return a {@link ProductSearchResponse} with results and facets
     */
    @GetMapping
    public ResponseEntity<ProductSearchResponse> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Long priceMin,
            @RequestParam(required = false) Long priceMax,
            @RequestParam(required = false) Boolean inStock,
            @RequestParam(required = false) Boolean hasPromotion,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size,
            HttpServletRequest request) {

        log.debug("GET /api/v1/search/products?q={}&category={}&sort={}&size={}",
            q, category, sort, size);

        // Extract dynamic filters from query string (filters[key]=value format)
        Map<String, List<String>> filters = parseDynamicFilters(request);

        // Build validated search params
        SearchParams params = SearchParams.of(
            q, category, filters, priceMin, priceMax,
            inStock, hasPromotion, sort, cursor, size, properties);

        // Execute search
        ProductSearchResponse response = productSearchService.search(params);
        return ResponseEntity.ok(response);
    }

    /**
     * Parse dynamic filter parameters from the raw query string.
     *
     * <p>Supports the format {@code filters[key]=value1&filters[key]=value2}
     * for multi-value filters. Known parameters (q, category, etc.) are
     * ignored — only {@code filters[...]} entries are extracted.
     *
     * @param request the HTTP servlet request
     * @return a map of filter key to list of values, or {@code null} if none
     */
    private static Map<String, List<String>> parseDynamicFilters(HttpServletRequest request) {
        String queryString = request.getQueryString();
        if (queryString == null || queryString.isEmpty()) {
            return null;
        }

        Map<String, List<String>> filters = new HashMap<>();
        String[] pairs = queryString.split("&");

        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            if (idx < 0) continue;

            String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
            String value = idx + 1 < pair.length()
                ? URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8)
                : "";

            // Match filters[key] pattern
            if (key.startsWith("filters[") && key.endsWith("]")) {
                String filterName = key.substring(8, key.length() - 1);
                if (!filterName.isEmpty()) {
                    filters.computeIfAbsent(filterName, k -> new ArrayList<>()).add(value);
                }
            }
        }

        return filters.isEmpty() ? null : filters;
    }
}
