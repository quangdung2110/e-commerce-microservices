package com.platform.promotionservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Cấu hình riêng của Promotion Service (docs/promotion/operations.md §3). Mọi
 * thời gian UTC; mọi tiền là integer VND.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "promotion")
public class PromotionProperties {

    private Admin admin = new Admin();
    private Internal internal = new Internal();
    private Product product = new Product();
    private Snapshot snapshot = new Snapshot();
    private Claim claim = new Claim();
    private Reservation reservation = new Reservation();
    private Finalizer finalizer = new Finalizer();
    private Schedule schedule = new Schedule();
    private Reconciliation reconciliation = new Reconciliation();
    private Retention outbox = new Retention(Duration.ofDays(14), Duration.ofHours(1));
    private Retention receipt = new Retention(Duration.ofDays(90), Duration.ofHours(1));
    private Markdown markdown = new Markdown();
    private Kafka kafka = new Kafka();
    private Workers workers = new Workers();

    @Getter
    @Setter
    public static class Admin {
        private String requiredRole = "ADMIN";
        private String userIdHeader = "X-User-Id";
        private String rolesHeader = "X-User-Roles";
    }

    @Getter
    @Setter
    public static class Internal {
        /**
         * /internal/v1/** chỉ đi qua service network; identity do mesh/ingress xác minh
         * và đặt vào header này. Application chỉ tin header đã được hạ tầng xác thực.
         */
        private String identityHeader = "X-Service-Identity";
        /** Caller của POST /internal/v1/promotion-discount-calculations (Checkout). */
        private List<String> calculationClients = new ArrayList<>(List.of("checkout-service"));
        /** Caller của POST /internal/v1/promotion-voucher-options (Checkout, ADR-018). */
        private List<String> optionsClients = new ArrayList<>(List.of("checkout-service"));
        /** Caller của /internal/v1/promotion-allocation/** (Order/Temporal). */
        private List<String> allocationClients = new ArrayList<>(List.of("order-service"));
    }

    @Getter
    @Setter
    public static class Product {
        private boolean validationEnabled = true;
        private String baseUrl = "http://localhost:8082";
        private String identityHeader = "X-Service-Identity";
        private String identity = "promotion-service";
        private Duration timeout = Duration.ofSeconds(2);
    }

    @Getter
    @Setter
    public static class Snapshot {
        private Duration ttl = Duration.ofSeconds(3600);
    }

    @Getter
    @Setter
    public static class Claim {
        private Duration pendingStaleAfter = Duration.ofMinutes(5);
        private Duration sweeperInterval = Duration.ofSeconds(60);
        private long maxPoolSize = 1_000_000L;
        private int poolBatchSize = 5_000;
        private int pendingRetryAfterSeconds = 2;
    }

    @Getter
    @Setter
    public static class Reservation {
        private Duration businessTtl = Duration.ofMinutes(15);
        private Duration cleanupGrace = Duration.ofMinutes(5);
        private Duration sweeperInterval = Duration.ofSeconds(60);
    }

    @Getter
    @Setter
    public static class Finalizer {
        private Duration scanInterval = Duration.ofSeconds(60);
        private Duration staleAfter = Duration.ofSeconds(30);
        private int batchSize = 200;
    }

    @Getter
    @Setter
    public static class Schedule {
        private Duration loopInterval = Duration.ofMillis(500);
        private Duration fallbackInterval = Duration.ofSeconds(15);
        private int batchSize = 100;
        private Duration fallbackLookback = Duration.ofDays(1);
    }

    @Getter
    @Setter
    public static class Reconciliation {
        private Duration interval = Duration.ofMinutes(5);
        private Duration recentWindow = Duration.ofMinutes(15);
        private Duration lockTtl = Duration.ofSeconds(30);
    }

    @Getter
    @Setter
    public static class Retention {
        private Duration retention;
        private Duration trimInterval;

        public Retention() {
        }

        public Retention(Duration retention, Duration trimInterval) {
            this.retention = retention;
            this.trimInterval = trimInterval;
        }
    }

    @Getter
    @Setter
    public static class Markdown {
        private String dataschema = "/contracts/events/promotion/v1/schemas/product-markdown-snapshot.schema.json";
    }

    @Getter
    @Setter
    public static class Kafka {
        private String productTopic = "t.product.events";
        private String productDlqTopic = "t.product.events.promotion.dlq";
        private String groupId = "promotion-product-tombstone-consumer";
        private int retryAttempts = 4;
        private Duration retryBackoff = Duration.ofMillis(500);
        private boolean consumerEnabled = true;
    }

    @Getter
    @Setter
    public static class Workers {
        private boolean enabled = true;
    }
}
