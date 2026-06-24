package com.platform.inventoryservice.controller;

import com.platform.inventoryservice.common.CorrelationIdContext;
import com.platform.inventoryservice.domain.enums.ReferenceType;
import com.platform.inventoryservice.dto.request.RestockRequest;
import com.platform.inventoryservice.dto.response.RestockResponse;
import com.platform.inventoryservice.port.inbound.RestockPort;
import com.platform.inventoryservice.service.command.RestockCommand;
import com.platform.inventoryservice.service.result.RestockResult;
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
public class RestockController {

    private final RestockPort restockPort;

    @RateLimiter(name = "restock")
    @PostMapping("/restock")
    public ResponseEntity<RestockResponse> restock(
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationIdHeader,
        @RequestBody @Valid RestockRequest request
    ) {
        ReferenceType refType = request.referenceType() == null
            ? ReferenceType.ADMIN_RESTOCK : request.referenceType();

        String correlationId = correlationIdHeader != null && !correlationIdHeader.isBlank()
            ? correlationIdHeader
            : CorrelationIdContext.get();

        RestockCommand cmd = new RestockCommand(
            request.warehouseId(),
            request.skuId(),
            request.productId(),
            request.quantity(),
            refType,
            request.orderId(),
            request.orderItemId(),
            request.refundRequestId(),
            correlationId
        );

        RestockResult result = restockPort.restock(cmd);
        return ResponseEntity.ok(RestockResponse.from(result));
    }
}
