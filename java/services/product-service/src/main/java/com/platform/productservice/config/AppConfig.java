package com.platform.productservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class AppConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        return mapper;
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Configuration
    @ConfigurationProperties(prefix = "app")
    public static class AppProperties {

        private MarkdownProperties markdown = new MarkdownProperties();
        private OutboxProperties outbox = new OutboxProperties();
        private InventoryProperties inventory = new InventoryProperties();
        private CacheProperties cache = new CacheProperties();
        private LockProperties lock = new LockProperties();

        public MarkdownProperties getMarkdown() { return markdown; }
        public void setMarkdown(MarkdownProperties markdown) { this.markdown = markdown; }
        public OutboxProperties getOutbox() { return outbox; }
        public void setOutbox(OutboxProperties outbox) { this.outbox = outbox; }
        public InventoryProperties getInventory() { return inventory; }
        public void setInventory(InventoryProperties inventory) { this.inventory = inventory; }
        public CacheProperties getCache() { return cache; }
        public void setCache(CacheProperties cache) { this.cache = cache; }
        public LockProperties getLock() { return lock; }
        public void setLock(LockProperties lock) { this.lock = lock; }

        public static class MarkdownProperties {
            private String expiryCron = "0 0 3 * * ?";
            public String getExpiryCron() { return expiryCron; }
            public void setExpiryCron(String expiryCron) { this.expiryCron = expiryCron; }
        }

        public static class OutboxProperties {
            private int retentionDays = 7;
            private int batchSize = 100;
            public int getRetentionDays() { return retentionDays; }
            public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }
            public int getBatchSize() { return batchSize; }
            public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        }

        public static class InventoryProperties {
            private int syncBatchSize = 100;
            private long syncIntervalMs = 500;
            public int getSyncBatchSize() { return syncBatchSize; }
            public void setSyncBatchSize(int syncBatchSize) { this.syncBatchSize = syncBatchSize; }
            public long getSyncIntervalMs() { return syncIntervalMs; }
            public void setSyncIntervalMs(long syncIntervalMs) { this.syncIntervalMs = syncIntervalMs; }
        }

        public static class CacheProperties {
            private int skuTtlSeconds = 3600;
            private int markdownTtlBufferSeconds = 300;
            public int getSkuTtlSeconds() { return skuTtlSeconds; }
            public void setSkuTtlSeconds(int skuTtlSeconds) { this.skuTtlSeconds = skuTtlSeconds; }
            public int getMarkdownTtlBufferSeconds() { return markdownTtlBufferSeconds; }
            public void setMarkdownTtlBufferSeconds(int markdownTtlBufferSeconds) { this.markdownTtlBufferSeconds = markdownTtlBufferSeconds; }
        }

        public static class LockProperties {
            private int timeoutSeconds = 5;
            private int distributedLockTtlSeconds = 30;
            public int getTimeoutSeconds() { return timeoutSeconds; }
            public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
            public int getDistributedLockTtlSeconds() { return distributedLockTtlSeconds; }
            public void setDistributedLockTtlSeconds(int distributedLockTtlSeconds) { this.distributedLockTtlSeconds = distributedLockTtlSeconds; }
        }
    }
}
