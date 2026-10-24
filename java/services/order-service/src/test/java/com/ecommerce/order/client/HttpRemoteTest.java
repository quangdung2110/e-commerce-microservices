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

    @Test
    void payment_absence_proof_must_match_the_command_and_be_complete() throws Exception {
        var request = mapper.readTree("{\"order_id\":\"10001\",\"payment_attempt_number\":1}");
        var proof = mapper.readTree("""
                {"code":"PROVIDER_CREATE_NOT_ACCEPTED","retryable":false,"no_payment_created":true,
                 "order_id":"10001","payment_attempt_number":1,
                 "transaction_id":"TXN_01K5V7M4R8Q2K7V6W5X1Y0ZABC",
                 "provider_operation_id":"POP_01K5V7M4R8Q2K7V6W5X1Y0ZABD"}
                """);
        assertThat(HttpGateways.validateInitiationProof(HttpRemote.classify(422, proof), request).isRejected()).isTrue();
        for (String field : new String[]{"no_payment_created", "transaction_id", "provider_operation_id", "order_id", "payment_attempt_number", "retryable"}) {
            var incomplete = ((com.fasterxml.jackson.databind.node.ObjectNode) proof).deepCopy();
            incomplete.remove(field);
            assertThat(HttpGateways.validateInitiationProof(HttpRemote.classify(422, incomplete), request).isUnknown()).isTrue();
        }
        var mismatch = ((com.fasterxml.jackson.databind.node.ObjectNode) proof).deepCopy().put("order_id", "10002");
        assertThat(HttpGateways.validateInitiationProof(HttpRemote.classify(422, mismatch), request).isUnknown()).isTrue();
        assertThat(HttpGateways.validateInitiationProof(HttpRemote.classify(503, proof), request).isUnknown()).isTrue();
        // A legacy rejection without absence proof must never authorize resource release.
        assertThat(HttpRemote.classify(503, mapper.readTree("{\"code\":\"PROVIDER_REQUEST_REJECTED\",\"retryable\":false}")).isUnknown()).isTrue();
    }
}
