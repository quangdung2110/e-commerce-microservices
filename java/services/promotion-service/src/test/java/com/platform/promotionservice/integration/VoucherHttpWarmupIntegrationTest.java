package com.platform.promotionservice.integration;

import com.platform.promotionservice.config.VoucherReadWarmup;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.BeforeEach;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@Import(VoucherHttpWarmupIntegrationTest.AuditConfig.class)
class VoucherHttpWarmupIntegrationTest extends AbstractPromotionIntegrationTest {
    @Autowired private VoucherReadWarmup warmup;
    @Autowired private MeterRegistry meters;
    @LocalServerPort private int port;
    @Autowired private StartupAudit audit;

    @Override
    @BeforeEach
    void resetState() {
        // Preserve the before/after startup state under audit; the base class would truncate it here.
    }

    @Test
    void realHttpOptionsArePrimedBeforeReadinessWithNoWalletOrFinancialWrites() throws Exception {
        assertThat(warmup.health().getStatus().getCode()).isEqualTo("UP");
        assertThat(meters.get("promotion.voucher.startup.warmup").tag("outcome", "success").timer().count()).isEqualTo(1);
        assertThat(meters.get("http.server.requests").tag("uri", "/internal/v1/promotion-voucher-options")
                .tag("status", "200").timer().count()).isEqualTo(3);
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                    + "/actuator/health/readiness")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
        }
        assertThat(audit.snapshot()).isEqualTo(audit.before);
    }

    @TestConfiguration
    static class AuditConfig {
        @Bean StartupAudit startupAudit(JdbcTemplate jdbc) { return new StartupAudit(jdbc); }
    }

    static class StartupAudit implements ApplicationRunner, Ordered {
        private final JdbcTemplate jdbc;
        private Map<String, String> before;
        StartupAudit(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
        @Override public void run(ApplicationArguments args) { before = snapshot(); }
        Map<String, String> snapshot() {
            Map<String, String> rows = new LinkedHashMap<>();
            for (String table : List.of("user_coupon_wallets", "coupon_claims", "promotion_command_receipts",
                    "promotion_outbox_events", "promotion_budgets")) {
                rows.put(table, jdbc.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text, '[]') FROM "
                        + table + " t", String.class));
            }
            return rows;
        }
    }
}
