package com.ecommerce.search.controller.api;

import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.common.SearchException;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.model.AttributeToken;
import com.ecommerce.search.domain.model.SearchQuery;
import com.ecommerce.search.domain.model.SortOption;
import com.ecommerce.search.dto.SearchDtos;
import com.ecommerce.search.service.CategoryFacetService;
import com.ecommerce.search.service.CategoryTreeService;
import com.ecommerce.search.service.ProductSearchService;
import com.ecommerce.search.service.SuggestionService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The four anonymous storefront endpoints of
 * {@code contracts/http/search/v2/openapi.yaml}.
 *
 * <p>These are public reads behind the Gateway. The controller deliberately ignores every
 * {@code X-User-*} header: the trust model for this path is anonymous, and a service that
 * read identity here would be exploitable if it ever gained a public ingress
 * ({@code docs/search/interfaces.md §2}).
 *
 * <p>The controller only validates and normalises; ranking, DSL and cursor handling belong
 * to the query layer.
 */
@RestController
public class ProductSearchController {

    private static final int SUGGEST_MIN_LENGTH = 2;
    private static final int Q_MAX_LENGTH = 256;
    private static final int CATEGORY_MAX_LENGTH = 180;
    private static final int FILTER_MAX_ITEMS = 50;
    private static final int FILTER_TOKEN_MAX_LENGTH = 256;
    private static final int CURSOR_MAX_LENGTH = 16384;

    private final ProductSearchService searchService;
    private final SuggestionService suggestionService;
    private final CategoryTreeService categoryTreeService;
    private final CategoryFacetService facetService;
    private final SearchProperties properties;

    public ProductSearchController(ProductSearchService searchService,
                                   SuggestionService suggestionService,
                                   CategoryTreeService categoryTreeService,
                                   CategoryFacetService facetService,
                                   SearchProperties properties) {
        this.searchService = searchService;
        this.suggestionService = suggestionService;
        this.categoryTreeService = categoryTreeService;
        this.facetService = facetService;
        this.properties = properties;
    }

    @GetMapping(value = "/api/v2/search/products", produces = MediaType.APPLICATION_JSON_VALUE)
    public SearchDtos.SearchPage searchProducts(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(name = "filter", required = false) List<String> filters,
            @RequestParam(name = "price_min", required = false) Long priceMin,
            @RequestParam(name = "price_max", required = false) Long priceMax,
            @RequestParam(name = "in_stock", required = false, defaultValue = "true") boolean inStock,
            @RequestParam(name = "has_promotion", required = false) Boolean hasPromotion,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) throws IOException {

        validatePriceRange(priceMin, priceMax);
        validateBounds(q, category, filters, cursor);

        SearchQuery request = new SearchQuery(
                SearchQuery.normalizeQueryText(q),
                Optional.ofNullable(category).filter(value -> !value.isBlank()),
                filters == null ? List.of() : filters,
                Optional.ofNullable(priceMin),
                Optional.ofNullable(priceMax),
                inStock,
                Optional.ofNullable(hasPromotion),
                SortOption.parse(sort),
                resolveSize(size),
                Optional.ofNullable(cursor).filter(value -> !value.isBlank()));

        return searchService.search(request);
    }

    @GetMapping(value = "/api/v2/search/suggestions", produces = MediaType.APPLICATION_JSON_VALUE)
    public SearchDtos.SuggestionList suggestions(
            @RequestParam String q,
            @RequestParam(required = false) Integer limit) throws IOException {

        String prefix = q == null ? "" : q.trim();
        if (prefix.length() < SUGGEST_MIN_LENGTH) {
            throw SearchException.invalidParameter("q", "must be at least 2 characters");
        }
        if (q.length() > Q_MAX_LENGTH) {
            throw SearchException.invalidParameter("q", "must be at most " + Q_MAX_LENGTH + " characters");
        }
        int resolvedLimit = limit == null ? 8 : limit;
        if (resolvedLimit < 1 || resolvedLimit > 20) {
            throw SearchException.invalidParameter("limit", "must be between 1 and 20");
        }
        return suggestionService.suggest(prefix, resolvedLimit);
    }

    @GetMapping(value = "/api/v2/search/categories", produces = MediaType.APPLICATION_JSON_VALUE)
    public SearchDtos.CategoryTree categories() throws IOException {
        return categoryTreeService.tree();
    }

    @GetMapping(value = "/api/v2/search/categories/{category_id}/facets", produces = MediaType.APPLICATION_JSON_VALUE)
    public SearchDtos.FacetSet categoryFacets(@PathVariable("category_id") String categoryId) throws IOException {
        return facetService.facetsOf(categoryId);
    }

    private int resolveSize(Integer size) {
        if (size == null) {
            return properties.pagination().defaultSize();
        }
        if (size < 1 || size > properties.pagination().maxSize()) {
            throw SearchException.invalidParameter("size",
                    "must be between 1 and " + properties.pagination().maxSize());
        }
        return size;
    }

    /**
     * The limits {@code contracts/http/search/v2/openapi.yaml} declares on the free-form
     * parameters. All violations are reported together. A blank {@code q}, {@code category}
     * or {@code cursor} is treated as not sent, which is what a browser form submits for an
     * empty field.
     */
    private static void validateBounds(String q, String category, List<String> filters, String cursor) {
        List<SearchException.FieldError> errors = new ArrayList<>();
        if (q != null && q.length() > Q_MAX_LENGTH) {
            errors.add(new SearchException.FieldError("q", "must be at most " + Q_MAX_LENGTH + " characters"));
        }
        if (category != null && category.length() > CATEGORY_MAX_LENGTH) {
            errors.add(new SearchException.FieldError("category",
                    "must be at most " + CATEGORY_MAX_LENGTH + " characters"));
        }
        if (filters != null) {
            if (filters.size() > FILTER_MAX_ITEMS) {
                errors.add(new SearchException.FieldError("filter",
                        "must repeat at most " + FILTER_MAX_ITEMS + " times"));
            }
            for (String token : filters) {
                if (token == null || token.isEmpty() || token.length() > FILTER_TOKEN_MAX_LENGTH) {
                    errors.add(new SearchException.FieldError("filter",
                            "each token must be 1 to " + FILTER_TOKEN_MAX_LENGTH + " characters"));
                    break;
                }
                if (AttributeToken.decode(token).isEmpty()) {
                    errors.add(new SearchException.FieldError("filter",
                            "'" + abbreviate(token) + "' is not a facet option value; send back FacetOption.value unchanged"));
                    break;
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new SearchException(ErrorCode.INVALID_QUERY_PARAMETER,
                    "Request has " + errors.size() + " invalid parameter(s)", errors, null);
        }
        if (cursor != null && cursor.length() > CURSOR_MAX_LENGTH) {
            throw SearchException.invalidCursor("cursor is longer than " + CURSOR_MAX_LENGTH + " characters");
        }
    }

    private static String abbreviate(String token) {
        return token.length() <= 40 ? token : token.substring(0, 40) + "...";
    }

    private static void validatePriceRange(Long priceMin, Long priceMax) {
        if (priceMin != null && priceMin < 0) {
            throw SearchException.invalidParameter("price_min", "must not be negative");
        }
        if (priceMax != null && priceMax < 0) {
            throw SearchException.invalidParameter("price_max", "must not be negative");
        }
        if (priceMin != null && priceMax != null && priceMax < priceMin) {
            throw SearchException.invalidParameter("price_max", "must be greater than or equal to price_min");
        }
    }
}
