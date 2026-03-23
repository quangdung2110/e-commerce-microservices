package com.ecommerce.shipping.presentation.controller;

import com.ecommerce.shipping.application.dto.TrackingEventsResult;
import com.ecommerce.shipping.application.port.in.GetTrackingEventsUseCase;
import com.ecommerce.shipping.presentation.dto.TrackingEventsResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Theo thiết kế shipping_workflow.md mục 4: tracking events endpoint.
@RestController
@RequestMapping("/v1/shipping")
public class ShipmentTrackingController {

	private final GetTrackingEventsUseCase getTrackingEventsUseCase;

	public ShipmentTrackingController(GetTrackingEventsUseCase getTrackingEventsUseCase) {
		this.getTrackingEventsUseCase = getTrackingEventsUseCase;
	}

	@GetMapping("/{trackingNumber}/events")
	public TrackingEventsResponse getTrackingEvents(@PathVariable String trackingNumber,
													@RequestParam(name = "limit", defaultValue = "100") int limit) {
		TrackingEventsResult result = getTrackingEventsUseCase.getTrackingEvents(trackingNumber, limit);
		return new TrackingEventsResponse(
				result.trackingNumber(),
				result.courierCode(),
				result.currentStatus(),
				result.events().stream()
						.map(event -> new TrackingEventsResponse.TrackingEventResponse(
								event.status(),
								event.location(),
								event.timestamp()))
						.toList());
	}
}
