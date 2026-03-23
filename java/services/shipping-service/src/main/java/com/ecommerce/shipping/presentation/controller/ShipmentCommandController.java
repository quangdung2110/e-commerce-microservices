package com.ecommerce.shipping.presentation.controller;

import com.ecommerce.shipping.application.dto.CancelShipmentCommand;
import com.ecommerce.shipping.application.dto.CreateShipmentCommand;
import com.ecommerce.shipping.application.dto.CreateShipmentResult;
import com.ecommerce.shipping.application.port.in.CancelShipmentUseCase;
import com.ecommerce.shipping.application.port.in.CreateShipmentUseCase;
import com.ecommerce.shipping.presentation.dto.CancelShipmentRequest;
import com.ecommerce.shipping.presentation.dto.CreateShipmentRequest;
import com.ecommerce.shipping.presentation.dto.CreateShipmentResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Theo thiết kế shipping_workflow.md mục 2-3: create-shipment và cancel shipment.
@RestController
@RequestMapping("/v1/shipping")
public class ShipmentCommandController {

	private final CreateShipmentUseCase createShipmentUseCase;
	private final CancelShipmentUseCase cancelShipmentUseCase;

	public ShipmentCommandController(CreateShipmentUseCase createShipmentUseCase,
									 CancelShipmentUseCase cancelShipmentUseCase) {
		this.createShipmentUseCase = createShipmentUseCase;
		this.cancelShipmentUseCase = cancelShipmentUseCase;
	}

	@PostMapping("/create-shipment")
	public CreateShipmentResponse createShipment(@Valid @RequestBody CreateShipmentRequest request) {
		CreateShipmentResult result = createShipmentUseCase.createShipment(new CreateShipmentCommand(
				request.orderId(),
				request.parcelId(),
				request.courierCode(),
				request.serviceType(),
				request.sender(),
				request.receiver(),
				request.items(),
				request.codAmount(),
				request.totalWeightGrams(),
				request.note()));

		return new CreateShipmentResponse(
				result.shipmentId(),
				result.trackingNumber(),
				result.labelUrl(),
				result.estimatedDeliveryDate());
	}

	@PostMapping("/{shipmentId}/cancel")
	public ResponseEntity<Void> cancelShipment(@PathVariable Long shipmentId,
											   @Valid @RequestBody CancelShipmentRequest request) {
		cancelShipmentUseCase.cancelShipment(new CancelShipmentCommand(shipmentId, request.reason()));
		return ResponseEntity.noContent().build();
	}
}
