package com.ecommerce.shipping.infrastructure.worker;

import com.ecommerce.shipping.application.port.out.IdempotencyPort;
import com.ecommerce.shipping.application.port.out.OutboxRepositoryPort;
import com.ecommerce.shipping.application.port.out.ProviderStatusMappingRepositoryPort;
import com.ecommerce.shipping.application.port.out.ShipmentEventStorePort;
import com.ecommerce.shipping.application.port.out.ShipmentRepositoryPort;
import com.ecommerce.shipping.application.port.out.ShippingProviderCatalogPort;
import com.ecommerce.shipping.application.port.out.ShippingProviderGatewayPort;
import com.ecommerce.shipping.domain.enums.ShipmentStatus;
import com.ecommerce.shipping.domain.enums.ShipmentTransitionEvent;
import com.ecommerce.shipping.domain.enums.ShippingEventType;
import com.ecommerce.shipping.domain.model.OutboxEvent;
import com.ecommerce.shipping.domain.model.Shipment;
import com.ecommerce.shipping.domain.model.ShipmentEvent;
import com.ecommerce.shipping.domain.model.ShippingProvider;
import com.ecommerce.shipping.domain.service.ProviderStatusMappingDomainService;
import com.ecommerce.shipping.domain.service.ShipmentStateMachineDomainService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

// Theo thiết kế shipping_workers.md mục 4: quét shipment stale mỗi 2 giờ và emit SHIPMENT_DELAYED.
@Component
public class StaleShipmentDetectorWorker {

    private final ShipmentRepositoryPort shipmentRepositoryPort;
    private final ShippingProviderCatalogPort shippingProviderCatalogPort;
    private final ShippingProviderGatewayPort shippingProviderGatewayPort;
    private final ProviderStatusMappingRepositoryPort providerStatusMappingRepositoryPort;
    private final ProviderStatusMappingDomainService providerStatusMappingDomainService;
    private final ShipmentStateMachineDomainService shipmentStateMachineDomainService;
    private final ShipmentEventStorePort shipmentEventStorePort;
    private final OutboxRepositoryPort outboxRepositoryPort;
    private final IdempotencyPort idempotencyPort;

    public StaleShipmentDetectorWorker(ShipmentRepositoryPort shipmentRepositoryPort,
                                       ShippingProviderCatalogPort shippingProviderCatalogPort,
                                       ShippingProviderGatewayPort shippingProviderGatewayPort,
                                       ProviderStatusMappingRepositoryPort providerStatusMappingRepositoryPort,
                                       ProviderStatusMappingDomainService providerStatusMappingDomainService,
                                       ShipmentStateMachineDomainService shipmentStateMachineDomainService,
                                       ShipmentEventStorePort shipmentEventStorePort,
                                       OutboxRepositoryPort outboxRepositoryPort,
                                       IdempotencyPort idempotencyPort) {
        this.shipmentRepositoryPort = shipmentRepositoryPort;
        this.shippingProviderCatalogPort = shippingProviderCatalogPort;
        this.shippingProviderGatewayPort = shippingProviderGatewayPort;
        this.providerStatusMappingRepositoryPort = providerStatusMappingRepositoryPort;
        this.providerStatusMappingDomainService = providerStatusMappingDomainService;
        this.shipmentStateMachineDomainService = shipmentStateMachineDomainService;
        this.shipmentEventStorePort = shipmentEventStorePort;
        this.outboxRepositoryPort = outboxRepositoryPort;
        this.idempotencyPort = idempotencyPort;
    }

    @Scheduled(cron = "${shipping.workers.stale-detector.cron:0 0 */2 * * *}")
    void detectStaleShipments() {
        OffsetDateTime staleBefore = OffsetDateTime.now().minusHours(48);
        for (Shipment shipment : shipmentRepositoryPort.findStaleShipments(staleBefore, 100)) {
            ShippingProvider provider = shippingProviderCatalogPort.findById(shipment.providerId()).orElse(null);
            if (provider == null) {
                continue;
            }

            ShippingProviderGatewayPort.ProviderTrackingProbe probe =
                    shippingProviderGatewayPort.getTrackingStatus(provider.code(), shipment.trackingNumber());

            if (probe != null && probe.found()) {
                handleProviderRefresh(shipment, provider, probe);
                continue;
            }

            emitDelayedEvent(shipment, provider, staleBefore, 48, 1);
            emitDelayedEvent(shipment, provider, staleBefore.minusHours(24), 72, 2);
        }
    }

    private void handleProviderRefresh(Shipment shipment,
                                       ShippingProvider provider,
                                       ShippingProviderGatewayPort.ProviderTrackingProbe probe) {
        ShipmentStatus mappedStatus = providerStatusMappingDomainService.mapToStandardizedStatus(
                providerStatusMappingRepositoryPort.findByProvider(provider.id()),
                probe.providerStatus());

        if (mappedStatus == shipment.standardizedStatus()) {
            return;
        }

        ShipmentTransitionEvent transitionEvent = resolveTransition(shipment.standardizedStatus(), mappedStatus);
        if (transitionEvent == null || !shipmentStateMachineDomainService.canTransition(
                shipment.standardizedStatus(), transitionEvent, 0)) {
            return;
        }

        Instant now = Instant.now();
        boolean updated = shipmentRepositoryPort.updateStatusWithOptimisticLock(
                shipment.id(),
                shipment.version(),
                probe.providerStatus(),
                mappedStatus.name(),
                OffsetDateTime.ofInstant(now, OffsetDateTime.now().getOffset()));
        if (!updated) {
            return;
        }

        shipmentEventStorePort.append(new ShipmentEvent(
                shipment.id(),
                now,
                mappedStatus,
                probe.providerStatus(),
                "stale detector refresh",
                probe.location(),
                "{}"));

        outboxRepositoryPort.save(newOutbox(shipment.id(), ShippingEventType.SHIPMENT_STATUS_UPDATED.name(), Map.of(
                "shipment_id", shipment.id(),
                "order_id", shipment.orderId(),
                "parcel_id", shipment.parcelId(),
                "tracking_number", shipment.trackingNumber(),
                "courier_code", provider.code(),
                "old_status", shipment.standardizedStatus().name(),
                "new_status", mappedStatus.name(),
                "provider_original_status", probe.providerStatus(),
                "location", probe.location(),
                "occurred_at", now.toString())));
    }

    private void emitDelayedEvent(Shipment shipment,
                                  ShippingProvider provider,
                                  OffsetDateTime staleThreshold,
                                  int thresholdHours,
                                  int escalationLevel) {
        if (shipment.updatedAt() == null || shipment.updatedAt().isAfter(staleThreshold)) {
            return;
        }

        String key = escalationLevel == 1
                ? "shipping:delayed:sent:" + shipment.id()
                : "shipping:delayed:sent:" + shipment.id() + ":lvl2";
        if (!idempotencyPort.acquire(key, Duration.ofDays(7))) {
            return;
        }

        long hoursSinceUpdate = ChronoUnit.HOURS.between(shipment.updatedAt(), OffsetDateTime.now());
        outboxRepositoryPort.save(newOutbox(shipment.id(), ShippingEventType.SHIPMENT_DELAYED.name(), Map.of(
                "shipment_id", shipment.id(),
                "order_id", shipment.orderId(),
                "parcel_id", shipment.parcelId(),
                "tracking_number", shipment.trackingNumber(),
                "courier_code", provider.code(),
                "current_status", shipment.standardizedStatus().name(),
                "last_update_at", shipment.updatedAt().toString(),
                "hours_since_update", hoursSinceUpdate,
                "threshold_hours", thresholdHours,
                "escalation_level", escalationLevel,
                "detected_at", OffsetDateTime.now().toString())));
    }

    private ShipmentTransitionEvent resolveTransition(ShipmentStatus current, ShipmentStatus next) {
        if (current == ShipmentStatus.PENDING && next == ShipmentStatus.READY_TO_SHIP) {
            return ShipmentTransitionEvent.LABEL_CONFIRMED;
        }
        if (current == ShipmentStatus.READY_TO_SHIP && next == ShipmentStatus.IN_TRANSIT) {
            return ShipmentTransitionEvent.PICKED_UP;
        }
        if (current == ShipmentStatus.IN_TRANSIT && next == ShipmentStatus.DELIVERED) {
            return ShipmentTransitionEvent.DELIVERED;
        }
        if (current == ShipmentStatus.IN_TRANSIT && next == ShipmentStatus.FAILED) {
            return ShipmentTransitionEvent.DELIVERY_FAILED;
        }
        if (current == ShipmentStatus.FAILED && next == ShipmentStatus.IN_TRANSIT) {
            return ShipmentTransitionEvent.RETRY_DELIVERY;
        }
        if (current == ShipmentStatus.FAILED && next == ShipmentStatus.RETURNED) {
            return ShipmentTransitionEvent.RETURN_INITIATED;
        }
        return null;
    }

    private OutboxEvent newOutbox(Long aggregateId, String eventType, Map<String, Object> payload) {
        return new OutboxEvent(
                UUID.randomUUID(),
                "Shipment",
                aggregateId,
                eventType,
                payload,
                "PENDING",
                0,
                5,
                null,
                null,
                OffsetDateTime.now());
    }
}
