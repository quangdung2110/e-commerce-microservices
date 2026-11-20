package com.platform.paymentservice.adapter.in.http;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;

/** contracts ADR-005. */
class RetryAfterHeaderTest {

    private static HttpHeaders applied(int status, boolean retryable) {
        HttpHeaders headers = new HttpHeaders();
        RetryAfterHeader.apply(headers, status, retryable);
        return headers;
    }

    @Test
    void aRetryable503CarriesTheDefaultWait() {
        assertThat(applied(503, true).getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
    }

    @Test
    void a503ThatIsNotRetryableCarriesNone() {
        assertThat(applied(503, false).containsHeader(HttpHeaders.RETRY_AFTER)).isFalse();
    }

    @Test
    void a429AlwaysCarriesOne() {
        assertThat(applied(429, true).getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(applied(429, false).getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    }

    @Test
    void otherStatusesCarryNone() {
        assertThat(applied(500, true).containsHeader(HttpHeaders.RETRY_AFTER)).isFalse();
        assertThat(applied(400, false).containsHeader(HttpHeaders.RETRY_AFTER)).isFalse();
    }
}
