package com.ecommerce.inventory.web;

import com.ecommerce.inventory.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** contracts ADR-005: every retryable 503 carries Retry-After, with the raiser's estimate when it has one. */
class ProblemsRetryAfterTest {

    private final Problems problems = new Problems(new ObjectMapper());

    private ResponseEntity<Object> respond(ErrorCode code, Integer retryAfter) {
        return problems.response(code, "detail", List.of(), null, retryAfter);
    }

    @Test
    void everyRetryableServiceUnavailableCodeCarriesTheDefaultWhenNoEstimateWasGiven() {
        for (ErrorCode code : ErrorCode.values()) {
            if (code.status() == 503 && code.retryable()) {
                assertThat(respond(code, null).getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).as(code.name()).isEqualTo("2");
            }
        }
    }

    @Test
    void anEstimateFromTheRaiserWinsOverTheDefault() {
        assertThat(respond(ErrorCode.INVENTORY_BUSY, 5).getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
    }

    @Test
    void nothingElseGetsTheHeader() {
        for (ErrorCode code : ErrorCode.values()) {
            if (code.status() != 503 && code.status() != 429) {
                assertThat(respond(code, null).getHeaders().containsKey(HttpHeaders.RETRY_AFTER)).as(code.name()).isFalse();
            }
        }
    }
}
