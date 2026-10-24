package com.platform.productservice.infrastructure.grpc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The log fields of a Product RPC follow the contracts ADR-002 request ID format. */
class ProductRpcTimingTest {

    @Test
    void keepsRequestIdsInTheAdr002FormatIncludingDotsAndColons() {
        assertThat(ProductRpcTiming.requestId("checkout:req.0001")).isEqualTo("checkout:req.0001");
        assertThat(ProductRpcTiming.requestId("4bf92f3577b34da6a3ce929d0e0e4736"))
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    void logsAMissingOrMalformedRequestIdAsDashInsteadOfRejectingTheCall() {
        for (String bad : new String[]{null, "", "short", "x".repeat(129), "has space in it", "bad/char/!!"}) {
            assertThat(ProductRpcTiming.requestId(bad)).as(String.valueOf(bad)).isEqualTo("-");
        }
    }

    @Test
    void takesTheTraceIdFromAWellFormedTraceparentOnly() {
        assertThat(ProductRpcTiming.traceId("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"))
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(ProductRpcTiming.traceId("garbage")).isEqualTo("-");
        assertThat(ProductRpcTiming.traceId(null)).isEqualTo("-");
    }
}
