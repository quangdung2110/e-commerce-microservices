package com.platform.inventoryservice.config;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Data
@ConfigurationProperties(prefix = "inventory")
public class InventoryProperties {

    private Redis redis = new Redis();
    private Kafka kafka = new Kafka();
    private Temporal temporal = new Temporal();
    private Restock restock = new Restock();
    private Routing routing = new Routing();
    private Cleanup cleanup = new Cleanup();

    @Data
    @NoArgsConstructor
    public static class Redis {
        private Cluster cluster = new Cluster();
        private Lettuce lettuce = new Lettuce();
        private long timeoutMs = 2000;
        private int idempotencyTtlDays = 30;
        private int suspensionTtlHours = 24;

        @Data
        @NoArgsConstructor
        public static class Cluster {
            private List<String> nodes;
            private int maxRedirects = 3;
            private String password;
        }

        @Data
        @NoArgsConstructor
        public static class Lettuce {
            private Pool pool = new Pool();

            @Data
            @NoArgsConstructor
            public static class Pool {
                private int maxActive = 32;
                private int maxIdle = 16;
                private int minIdle = 4;
            }
        }
    }

    @Data
    @NoArgsConstructor
    public static class Kafka {
        private String bootstrapServers;
        private String topicEvents = "t.inventory.events";
        private String consumerGroupRelay = "inventory-stream-relay";
        private String consumerGroupLedger = "inventory-ledger-writer";
        private long pollIntervalMs = 100;
    }

    @Data
    @NoArgsConstructor
    public static class Temporal {
        private String target;
        private String namespace = "inventory";
        private String taskQueue = "inventory-activities";
        private String workerIdentity;
    }

    @Data
    @NoArgsConstructor
    public static class Restock {
        private int reservationTtlMinutes = 30;
    }

    @Data
    @NoArgsConstructor
    public static class Routing {
        private int planTtlMinutes = 20;
    }

    @Data
    @NoArgsConstructor
    public static class Cleanup {
        private String cron = "0 0 2 * * *";
        private int watermarkHours = 24;
        private int batchSize = 10000;
    }
}