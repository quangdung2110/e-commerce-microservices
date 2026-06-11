package com.platform.productservice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Generic paginated response wrapper.
 *
 * @param <T> the type of items in the page
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PagedResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious
) {

    public static <T> PagedResponse<T> of(
            List<T> items,
            int page,
            int size,
            long totalElements
    ) {
        int totalPages = size > 0 ? (int) Math.ceil((double) totalElements / size) : 0;
        return new PagedResponse<>(
                items,
                page,
                size,
                totalElements,
                totalPages,
                page < totalPages,
                page > 1
        );
    }
}
