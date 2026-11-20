package com.platform.paymentservice.adapter.in.http;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RequestCorrelationTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-01";

    @Test
    void keepsAValidRequestIdAndPassesTheTraceContextThrough() {
        RequestCorrelation c = RequestCorrelation.fromHeaders("checkout:req.0001", TRACEPARENT, "vendor=a");

        assertThat(c.requestId()).isEqualTo("checkout:req.0001");
        assertThat(c.traceparent()).isEqualTo(TRACEPARENT);
        assertThat(c.tracestate()).isEqualTo("vendor=a");
        assertThat(c.traceId()).isEqualTo(TRACE_ID);
    }

    @Test
    void replacesAMalformedRequestIdWithTheTraceIdAndNeverFails() {
        for (String bad : new String[]{"short", "x".repeat(129), "has space in it", "bad/char/!!", ""}) {
            assertThat(RequestCorrelation.fromHeaders(bad, TRACEPARENT, null).requestId())
                    .as(bad).isEqualTo(TRACE_ID);
        }
    }

    @Test
    void mintsAUuidWhenNeitherRequestIdNorTraceparentIsUsable() {
        RequestCorrelation c = RequestCorrelation.fromHeaders(null, null, null);

        assertThat(c.requestId()).matches(RequestCorrelation.REQUEST_ID_PATTERN).hasSize(36);
        assertThat(c.traceparent()).isNull();
        assertThat(c.traceId()).isNull();
    }

    @Test
    void dropsAnInvalidTraceparentInsteadOfForwardingIt() {
        String[] invalid = {
                "garbage",
                "00-" + "0".repeat(32) + "-00f067aa0ba902b7-01",
                "00-" + TRACE_ID + "-" + "0".repeat(16) + "-01",
                "ff-" + TRACE_ID + "-00f067aa0ba902b7-01",
                "00-" + TRACE_ID.toUpperCase() + "-00f067aa0ba902b7-01",
                "00-" + TRACE_ID + "-00f067aa0ba902b7",
        };
        for (String value : invalid) {
            RequestCorrelation c = RequestCorrelation.fromHeaders("request-id-1", value, "vendor=a");
            assertThat(c.traceparent()).as(value).isNull();
            assertThat(c.tracestate()).as("tracestate without traceparent").isNull();
        }
    }

    @Test
    void effectiveTimeoutIsTheConfiguredOneWhenNoRequestOrNoDeadline() {
        RequestCorrelation.clear();
        assertThat(RequestCorrelation.effectiveTimeout(Duration.ofSeconds(8), Duration.ofMillis(500)))
                .isEqualTo(Duration.ofSeconds(8));

        RequestCorrelation.set(RequestCorrelation.fromHeaders("request-id-1", null, null));
        try {
            assertThat(RequestCorrelation.effectiveTimeout(Duration.ofSeconds(8), Duration.ofMillis(500)))
                    .isEqualTo(Duration.ofSeconds(8));
        } finally {
            RequestCorrelation.clear();
        }
    }

    @Test
    void effectiveTimeoutIsKeptUnderTheCallerBudgetLeftMinusTheReserve() {
        RequestCorrelation.set(RequestCorrelation.fromHeaders("request-id-1", null, null, "3000"));
        try {
            Duration effective = RequestCorrelation.effectiveTimeout(Duration.ofSeconds(8), Duration.ofMillis(500));

            assertThat(effective).isLessThanOrEqualTo(Duration.ofMillis(2500)).isGreaterThan(Duration.ofMillis(2000));
            // A configured value already under the budget is not raised.
            assertThat(RequestCorrelation.effectiveTimeout(Duration.ofMillis(100), Duration.ofMillis(500)))
                    .isEqualTo(Duration.ofMillis(100));
        } finally {
            RequestCorrelation.clear();
        }
    }

    @Test
    void effectiveTimeoutGoesNonPositiveOnceTheBudgetIsSpent() {
        RequestCorrelation.set(RequestCorrelation.fromHeaders("request-id-1", null, null, "300"));
        try {
            assertThat(RequestCorrelation.effectiveTimeout(Duration.ofSeconds(8), Duration.ofMillis(500)).isNegative())
                    .isTrue();
        } finally {
            RequestCorrelation.clear();
        }
    }

    @Test
    void dropsAnOversizedTracestate() {
        assertThat(RequestCorrelation.fromHeaders("request-id-1", TRACEPARENT, "k=" + "v".repeat(600)).tracestate())
                .isNull();
    }
}
