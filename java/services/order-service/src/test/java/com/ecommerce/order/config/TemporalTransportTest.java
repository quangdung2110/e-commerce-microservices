package com.ecommerce.order.config;

import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static com.ecommerce.order.config.OrderProperties.TemporalTlsMode.MTLS;
import static com.ecommerce.order.config.OrderProperties.TemporalTlsMode.PLAINTEXT;
import static com.ecommerce.order.config.OrderProperties.TemporalTlsMode.TLS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemporalTransportTest {
    @TempDir
    Path directory;

    @Test
    void productionRejectsPlaintextAndDevelopmentKeepsItsLocalChannel() throws Exception {
        var properties = temporal(PLAINTEXT, "");
        var options = WorkflowServiceStubsOptions.newBuilder();
        assertThatThrownBy(() -> TemporalConfig.configureTransport(options, properties, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("TLS is required");
        TemporalConfig.configureTransport(options, properties, false);
        assertThat(options.build().getEnableHttps()).isFalse();
        assertThat(options.build().getSslContext()).isNull();
    }

    @Test
    void tlsRequiresAnApiKeyAndConfiguresTransportSecurity() throws Exception {
        var options = WorkflowServiceStubsOptions.newBuilder();
        assertThatThrownBy(() -> TemporalConfig.configureTransport(options, temporal(TLS, ""), true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("API key");

        Path apiKey = Files.writeString(directory.resolve("key"), "sandbox-fixture-key");
        TemporalConfig.configureTransport(options, temporal(TLS, apiKey.toString()), true);
        var configured = options.build();
        assertThat(configured.getEnableHttps()).isTrue();
        assertThat(configured.getGrpcMetadataProviders()).hasSize(1);
    }

    @Test
    void mutualTlsRequiresAnExplicitClientCertificate() {
        var options = WorkflowServiceStubsOptions.newBuilder();
        assertThatThrownBy(() -> TemporalConfig.configureTransport(options, temporal(MTLS, ""), true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("client certificate");
    }

    private static OrderProperties.Temporal temporal(OrderProperties.TemporalTlsMode mode, String apiKeyFile) {
        return new OrderProperties.Temporal(true, "temporal.sandbox.test:7233", "order-test", true,
                "checkout", "refund", "reconciliation", Duration.ofSeconds(30), 3,
                new OrderProperties.TemporalTransport(mode, "", "", "", apiKeyFile));
    }
}
