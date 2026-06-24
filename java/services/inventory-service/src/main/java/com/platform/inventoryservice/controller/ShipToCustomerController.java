package com.platform.inventoryservice.controller;

import com.platform.inventoryservice.common.CorrelationIdContext;
import com.platform.inventoryservice.dto.request.ShipToCustomerRequest;
import com.platform.inventoryservice.dto.response.ShipToCustomerResponse;
import com.platform.inventoryservice.port.inbound.ShipToCustomerPort;
import com.platform.inventoryservice.service.command.ShipToCustomerCommand;
import com.platform.inventoryservice.service.result.ShipToCustomerResult;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/inventory")
@RequiredArgsConstructor
public class ShipToCustomerController {

    private final ShipToCustomerPort shipService;

    @RateLimiter(name = "ship-to-customer")
    @PostMapping("/ship-to-customer")
    public ResponseEntity<ShipToCustomerResponse> ship(
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
        @RequestBody @Valid ShipToCustomerRequest request
    ) {
        ShipToCustomerResult result = shipService.ship(new ShipToCustomerCommand(
            request.orderId(),
            request.warehouseId(),
            request.shippedAt(),
            request.courierNote(),
            correlationId != null ? correlationId : CorrelationIdContext.get()
        ));

        return ResponseEntity.ok(ShipToCustomerResponse.from(result));
    }
}
