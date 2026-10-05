package com.ecommerce.order.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HttpRemoteTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void classifiesDurableRejectionsVersusUnknownOutcomes() throws Exception {
        assertThat(HttpRemote.classify(200, mapper.readTree("{\"outcome\":\"RESERVED\"}")).isAccepted()).isTrue();
        RemoteResult conflict = HttpRemote.classify(409, mapper.readTree("{\"code\":\"RESERVATION_NOT_RESERVED\"}"));
        assertThat(conflict.isRejected()).isTrue();
        assertThat(conflict.errorCode()).isEqualTo("RESERVATION_NOT_RESERVED");
        // Payment's retryable problem (e.g. REQUEST_IN_PROGRESS) is never a negative business outcome.
        assertThat(HttpRemote.classify(409, mapper.readTree("{\"code\":\"REQUEST_IN_PROGRESS\",\"retryable\":true}"))
                .isUnknown()).isTrue();
        assertThat(HttpRemote.classify(503, null).isUnknown()).isTrue();
        assertThat(HttpRemote.classify(429, null).isUnknown()).isTrue();
        assertThat(HttpRemote.classify(404, null).errorCode()).isEqualTo("NOT_FOUND");
    }
}
