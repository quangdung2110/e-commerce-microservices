package com.ecommerce.shipping.application.service;

import com.ecommerce.shipping.application.dto.CancelShipmentCommand;
import com.ecommerce.shipping.application.dto.CreateShipmentCommand;
import com.ecommerce.shipping.application.dto.CreateShipmentResult;
import com.ecommerce.shipping.application.port.in.CancelShipmentUseCase;
import com.ecommerce.shipping.application.port.in.CreateShipmentUseCase;
import com.ecommerce.shipping.application.port.out.IdempotencyPort;
import com.ecommerce.shipping.application.port.out.OutboxRepositoryPort;
import com.ecommerce.shipping.application.port.out.ShipmentRepositoryPort;
import com.ecommerce.shipping.application.port.out.ShippingProviderCatalogPort;
import com.ecommerce.shipping.application.port.out.ShippingProviderGatewayPort;
import com.ecommerce.shipping.domain.enums.ShipmentStatus;
import com.ecommerce.shipping.domain.enums.ShippingEventType;
import com.ecommerce.shipping.domain.model.OutboxEvent;
import com.ecommerce.shipping.domain.model.Shipment;
import com.ecommerce.shipping.domain.model.ShippingProvider;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// Theo thiết kế shipping_workflow.md mục 2-3: create/cancel shipment orchestration.
@Service
public class ShipmentCommandApplicationService implements CreateShipmentUseCase, CancelShipmentUseCase {

    private static final Duration CREATE_SHIPMENT_IDEMPOTENCY_TTL = Duration.ofHours(24);

    private final IdempotencyPort idempotencyPort;
    private final ShippingProviderCatalogPort shippingProviderCatalogPort;
    private final ShippingProviderGatewayPort shippingProviderGatewayPort;
    private final ShipmentRepositoryPort shipmentRepositoryPort;
    private final OutboxRepositoryPort outboxRepositoryPort;

    public ShipmentCommandApplicationService(IdempotencyPort idempotencyPort,
					     ShippingProviderCatalogPort shippingProviderCatalogPort,
					     ShippingProviderGatewayPort shippingProviderGatewayPort,
					     ShipmentRepositoryPort shipmentRepositoryPort,
					     OutboxRepositoryPort outboxRepositoryPort) {
	this.idempotencyPort = idempotencyPort;
	this.shippingProviderCatalogPort = shippingProviderCatalogPort;
	this.shippingProviderGatewayPort = shippingProviderGatewayPort;
	this.shipmentRepositoryPort = shipmentRepositoryPort;
	this.outboxRepositoryPort = outboxRepositoryPort;
    }

    @Override
    @Transactional
    public CreateShipmentResult createShipment(CreateShipmentCommand command) {
	validateCreateCommand(command);
	String idempotencyKey = "shipping:create:" + command.parcelId();
	boolean acquired = idempotencyPort.acquire(idempotencyKey, CREATE_SHIPMENT_IDEMPOTENCY_TTL);
	if (!acquired) {
	    Shipment existing = shipmentRepositoryPort.findByParcelId(command.parcelId())
		    .orElseThrow(() -> new IllegalStateException("Idempotency key exists but shipment not found"));
	    return new CreateShipmentResult(existing.id(), existing.trackingNumber(), null, null);
	}

	ShippingProvider provider = shippingProviderCatalogPort.findByCode(command.courierCode())
		.orElseThrow(() -> new IllegalArgumentException("Provider not found: " + command.courierCode()));

	ShippingProviderGatewayPort.CreateShipmentProviderResponse providerResponse =
		shippingProviderGatewayPort.createShipment(command);

	OffsetDateTime now = OffsetDateTime.now();
	Map<String, Object> packageInfo = new HashMap<>();
	packageInfo.put("items", command.items());
	packageInfo.put("total_weight_grams", command.totalWeightGrams());
	packageInfo.put("note", command.note());
	packageInfo.put("service_type", command.serviceType());

	Shipment createdShipment = shipmentRepositoryPort.save(new Shipment(
		null,
		command.parcelId(),
		command.orderId(),
		provider.id(),
		providerResponse.trackingNumber(),
		command.sender(),
		command.receiver(),
		packageInfo,
		ShipmentStatus.PENDING,
		providerResponse.providerStatus(),
		null,
		command.codAmount(),
		0L,
		now,
		now));

	outboxRepositoryPort.save(newOutboxEvent(
		createdShipment.id(),
		ShippingEventType.SHIPMENT_CREATED.name(),
		Map.of(
			"shipment_id", createdShipment.id(),
			"order_id", createdShipment.orderId(),
			"parcel_id", createdShipment.parcelId(),
			"tracking_number", createdShipment.trackingNumber(),
			"courier_code", provider.code(),
			"courier_name", provider.name(),
			"sender_address", createdShipment.senderInfo(),
			"receiver_address", createdShipment.receiverInfo(),
			"estimated_delivery_date", providerResponse.estimatedDeliveryDate(),
			"created_at", now.toString()),
		now));

	return new CreateShipmentResult(
		createdShipment.id(),
		createdShipment.trackingNumber(),
		providerResponse.labelUrl(),
		providerResponse.estimatedDeliveryDate());
    }

    @Override
    @Transactional
    public void cancelShipment(CancelShipmentCommand command) {
	if (command == null || command.shipmentId() == null) {
	    throw new IllegalArgumentException("shipmentId must not be null");
	}

	Shipment shipment = shipmentRepositoryPort.findById(command.shipmentId())
		.orElseThrow(() -> new IllegalArgumentException("Shipment not found: " + command.shipmentId()));
	if (shipment.standardizedStatus() != ShipmentStatus.PENDING) {
	    throw new IllegalStateException("Only PENDING shipment can be cancelled");
	}

	ShippingProvider provider = shippingProviderCatalogPort.findById(shipment.providerId())
		.orElseThrow(() -> new IllegalStateException("Provider not found by id: " + shipment.providerId()));

	shippingProviderGatewayPort.cancelShipment(provider.code(), shipment.trackingNumber(), command.reason());

	OffsetDateTime now = OffsetDateTime.now();
	boolean updated = shipmentRepositoryPort.updateStatusWithOptimisticLock(
		shipment.id(),
		shipment.version(),
		"cancel",
		ShipmentStatus.CANCELLED.name(),
		now);
	if (!updated) {
	    throw new IllegalStateException("Shipment status update failed due to optimistic lock conflict");
	}

	outboxRepositoryPort.save(newOutboxEvent(
		shipment.id(),
		ShippingEventType.SHIPMENT_CANCELLED.name(),
		Map.of(
			"shipment_id", shipment.id(),
			"order_id", shipment.orderId(),
			"parcel_id", shipment.parcelId(),
			"tracking_number", shipment.trackingNumber(),
			"reason", command.reason(),
			"cancelled_at", now.toString()),
		now));
    }

    private OutboxEvent newOutboxEvent(Long aggregateId,
				       String eventType,
				       Map<String, Object> payload,
				       OffsetDateTime now) {
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
		now);
    }

    private void validateCreateCommand(CreateShipmentCommand command) {
	if (command == null) {
	    throw new IllegalArgumentException("create shipment command must not be null");
	}
	if (command.orderId() == null || command.parcelId() == null) {
	    throw new IllegalArgumentException("orderId and parcelId must not be null");
	}
	if (command.courierCode() == null || command.courierCode().isBlank()) {
	    throw new IllegalArgumentException("courierCode must not be blank");
	}
    }
}
