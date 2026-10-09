package com.ecommerce.inventory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/** Service configuration under {@code inventory.*} (application.yml). */
@ConfigurationProperties(prefix = "inventory")
public record InventoryProperties(
        boolean writeFreeze,
        Identity identity,
        Command command,
        Reservation reservation,
        Availability availability,
        Outbox outbox,
        Export export,
        Workers workers) {

    public record Identity(
            String serviceHeader,
            String userIdHeader,
            String rolesHeader,
            String sessionHeader,
            String adminRole,
            List<String> previewClients,
            List<String> workflowClients,
            List<String> availabilityReadClients,
            List<String> exportClients) {
    }

    public record Command(Duration lockTimeout, int internalRetries) {
    }

    public record Reservation(Duration paymentWindow, Duration orphanAlertAfter) {
    }

    public record Availability(Duration freshnessBudget) {
    }

    public record Outbox(long maxUnsequenced, Duration retention) {
    }

    public record Export(Duration snapshotTtl) {
    }

    public record Workers(
            boolean enabled,
            String instanceId,
            Duration projectorInterval,
            int projectorBatchSize,
            Duration fanoutInterval,
            int fanoutBatchSize,
            Duration readinessInterval,
            int readinessBatchSize,
            Duration sequencerInterval,
            int sequencerBatchSize,
            Duration sequencerLeaseTtl,
            Duration claimTtl,
            Duration backlogRefreshInterval,
            Duration orphanScanInterval,
            Duration bucketScanInterval,
            Duration availabilityAuditInterval,
            int availabilityAuditBatchSize,
            String ledgerReplayCron,
            Duration retentionInterval) {
    }
}
