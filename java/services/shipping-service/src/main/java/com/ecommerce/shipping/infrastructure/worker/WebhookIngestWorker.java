package com.ecommerce.shipping.infrastructure.worker;

import com.ecommerce.shipping.application.port.out.OutboxRepositoryPort;
import com.ecommerce.shipping.application.port.out.ProviderStatusMappingRepositoryPort;
import com.ecommerce.shipping.application.port.out.ShipmentEventStorePort;
import com.ecommerce.shipping.application.port.out.ShipmentRepositoryPort;
import com.ecommerce.shipping.application.port.out.ShippingProviderCatalogPort;
import com.ecommerce.shipping.domain.enums.ShipmentStatus;
import com.ecommerce.shipping.domain.enums.ShipmentTransitionEvent;
import com.ecommerce.shipping.domain.enums.ShippingEventType;
import com.ecommerce.shipping.domain.model.OutboxEvent;
import com.ecommerce.shipping.domain.model.Shipment;
import com.ecommerce.shipping.domain.model.ShipmentEvent;
import com.ecommerce.shipping.domain.model.ShippingProvider;
import com.ecommerce.shipping.domain.service.ProviderStatusMappingDomainService;
import com.ecommerce.shipping.domain.service.ShipmentStateMachineDomainService;
import com.ecommerce.shipping.infrastructure.persistence.postgres.repository.WebhookIdempotencyJpaRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// Theo thiết kế shipping_workers.md mục 2: consumer t.shipping.webhooks.raw xử lý chuẩn hóa/FSM.
@Component
public class WebhookIngestWorker {
	private static final Logger LOGGER = LoggerFactory.getLogger(WebhookIngestWorker.class);

	private final ObjectMapper objectMapper;
	private final ShipmentRepositoryPort shipmentRepositoryPort;
	private final ShipmentEventStorePort shipmentEventStorePort;
	private final ShippingProviderCatalogPort shippingProviderCatalogPort;
	private final ProviderStatusMappingRepositoryPort providerStatusMappingRepositoryPort;
	private final ProviderStatusMappingDomainService providerStatusMappingDomainService;
	private final ShipmentStateMachineDomainService shipmentStateMachineDomainService;
	private final WebhookIdempotencyJpaRepository webhookIdempotencyJpaRepository;
	private final OutboxRepositoryPort outboxRepositoryPort;

	public WebhookIngestWorker(ObjectMapper objectMapper,
							   ShipmentRepositoryPort shipmentRepositoryPort,
							   ShipmentEventStorePort shipmentEventStorePort,
							   ShippingProviderCatalogPort shippingProviderCatalogPort,
							   ProviderStatusMappingRepositoryPort providerStatusMappingRepositoryPort,
							   ProviderStatusMappingDomainService providerStatusMappingDomainService,
							   ShipmentStateMachineDomainService shipmentStateMachineDomainService,
							   WebhookIdempotencyJpaRepository webhookIdempotencyJpaRepository,
							   OutboxRepositoryPort outboxRepositoryPort) {
		this.objectMapper = objectMapper;
		this.shipmentRepositoryPort = shipmentRepositoryPort;
		this.shipmentEventStorePort = shipmentEventStorePort;
		this.shippingProviderCatalogPort = shippingProviderCatalogPort;
		this.providerStatusMappingRepositoryPort = providerStatusMappingRepositoryPort;
		this.providerStatusMappingDomainService = providerStatusMappingDomainService;
		this.shipmentStateMachineDomainService = shipmentStateMachineDomainService;
		this.webhookIdempotencyJpaRepository = webhookIdempotencyJpaRepository;
		this.outboxRepositoryPort = outboxRepositoryPort;
	}

	@KafkaListener(topics = "${shipping.topics.webhooks-raw:t.shipping.webhooks.raw}", groupId = "shipping-webhook-processor")
	@Transactional
	public void consumeRawWebhook(Map<String, Object> envelope) {
		WebhookPayload payload = parsePayload(envelope);
		ShippingProvider provider = shippingProviderCatalogPort.findByCode(payload.provider())
				.orElseThrow(() -> new IllegalArgumentException("Unknown provider: " + payload.provider()));

		int inserted = webhookIdempotencyJpaRepository.insertIgnore(provider.code(), payload.providerEventId());
		if (inserted == 0) {
			return;
		}

		Shipment shipment = shipmentRepositoryPort.findByTrackingNumber(payload.trackingNumber())
				.orElseThrow(() -> new IllegalArgumentException("Shipment not found for tracking: " + payload.trackingNumber()));

		ShipmentStatus mappedStatus = providerStatusMappingDomainService.mapToStandardizedStatus(
				providerStatusMappingRepositoryPort.findByProvider(provider.id()),
				payload.providerStatus());

		ShipmentTransitionEvent transitionEvent = resolveTransitionEvent(shipment.standardizedStatus(), mappedStatus);
		if (transitionEvent == null || !shipmentStateMachineDomainService.canTransition(
				shipment.standardizedStatus(), transitionEvent, retryCount(shipment.packageInfo()))) {
			LOGGER.warn("Invalid transition from {} to {} for shipment {}",
					shipment.standardizedStatus(), mappedStatus, shipment.id());
			return;
		}

		OffsetDateTime updatedAt = OffsetDateTime.ofInstant(payload.occurredAt(), OffsetDateTime.now().getOffset());
		boolean updated = shipmentRepositoryPort.updateStatusWithOptimisticLock(
				shipment.id(),
				shipment.version(),
				payload.providerStatus(),
				mappedStatus.name(),
				updatedAt);
		if (!updated) {
			LOGGER.warn("Optimistic lock conflict while processing webhook for shipment {}", shipment.id());
			return;
		}

		shipmentEventStorePort.append(new ShipmentEvent(
				shipment.id(),
				payload.occurredAt(),
				mappedStatus,
				payload.providerStatus(),
				payload.description(),
				payload.location(),
				payload.rawBody()));

		Map<String, Object> eventPayload = new HashMap<>();
		eventPayload.put("shipment_id", shipment.id());
		eventPayload.put("order_id", shipment.orderId());
		eventPayload.put("parcel_id", shipment.parcelId());
		eventPayload.put("tracking_number", shipment.trackingNumber());
		eventPayload.put("courier_code", provider.code());
		eventPayload.put("old_status", shipment.standardizedStatus().name());
		eventPayload.put("new_status", mappedStatus.name());
		eventPayload.put("provider_original_status", payload.providerStatus());
		eventPayload.put("location", payload.location());
		eventPayload.put("occurred_at", payload.occurredAt().toString());
		outboxRepositoryPort.save(newOutbox(shipment.id(), ShippingEventType.SHIPMENT_STATUS_UPDATED.name(), eventPayload));

		if (mappedStatus == ShipmentStatus.DELIVERED) {
			outboxRepositoryPort.save(newOutbox(shipment.id(), ShippingEventType.SHIPMENT_DELIVERED.name(), Map.of(
					"shipment_id", shipment.id(),
					"order_id", shipment.orderId(),
					"parcel_id", shipment.parcelId(),
					"tracking_number", shipment.trackingNumber(),
					"delivered_at", payload.occurredAt().toString())));
		}
		if (mappedStatus == ShipmentStatus.RETURNED) {
			outboxRepositoryPort.save(newOutbox(shipment.id(), ShippingEventType.SHIPMENT_RETURNED.name(), Map.of(
					"shipment_id", shipment.id(),
					"order_id", shipment.orderId(),
					"parcel_id", shipment.parcelId(),
					"tracking_number", shipment.trackingNumber(),
					"returned_at", payload.occurredAt().toString())));
		}
	}

	private WebhookPayload parsePayload(Map<String, Object> envelope) {
		try {
			String provider = String.valueOf(envelope.get("provider"));
			String rawBody = String.valueOf(envelope.get("raw_body"));
			Map<String, Object> raw = objectMapper.readValue(rawBody, new TypeReference<>() {
			});
			String tracking = asString(envelope.get("tracking_number"));
			if (tracking == null || tracking.isBlank()) {
				tracking = asString(raw.get("tracking_number"));
			}
			String providerStatus = asString(raw.get("provider_status"));
			if (providerStatus == null || providerStatus.isBlank()) {
				providerStatus = asString(raw.get("status"));
			}
			String eventId = asString(raw.get("event_id"));
			if (eventId == null || eventId.isBlank()) {
				eventId = UUID.randomUUID().toString();
			}
			Instant occurredAt = raw.get("occurred_at") == null
					? Instant.now()
					: Instant.parse(String.valueOf(raw.get("occurred_at")));

			return new WebhookPayload(
					provider,
					tracking,
					providerStatus,
					eventId,
					occurredAt,
					asString(raw.get("location")),
					asString(raw.getOrDefault("description", "status update")),
					rawBody);
		} catch (Exception ex) {
			throw new IllegalArgumentException("Cannot parse webhook envelope", ex);
		}
	}

	private String asString(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	private ShipmentTransitionEvent resolveTransitionEvent(ShipmentStatus current, ShipmentStatus next) {
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
		if (current == ShipmentStatus.DELIVERED && next == ShipmentStatus.RETURNED) {
			return ShipmentTransitionEvent.RETURN_PICKUP;
		}
		return null;
	}

	private int retryCount(Map<String, Object> packageInfo) {
		if (packageInfo == null || packageInfo.isEmpty()) {
			return 0;
		}
		Object retry = packageInfo.get("failed_retry_count");
		if (retry == null) {
			return 0;
		}
		try {
			return Integer.parseInt(String.valueOf(retry));
		} catch (NumberFormatException ex) {
			return 0;
		}
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

	private record WebhookPayload(
			String provider,
			String trackingNumber,
			String providerStatus,
			String providerEventId,
			Instant occurredAt,
			String location,
			String description,
			String rawBody
	) {
	}
}
