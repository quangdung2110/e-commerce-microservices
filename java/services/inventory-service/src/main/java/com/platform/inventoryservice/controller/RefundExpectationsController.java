package com.platform.inventoryservice.controller;

import com.platform.inventoryservice.common.CorrelationIdContext;
import com.platform.inventoryservice.dto.request.RefundExpectationsRequest;
import com.platform.inventoryservice.dto.response.RefundExpectationsResponse;
import com.platform.inventoryservice.port.inbound.RefundExpectationsPort;
import com.platform.inventoryservice.service.command.RefundExpectationsCommand;
import com.platform.inventoryservice.service.result.RefundExpectationsResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/inventory/returns")
@RequiredArgsConstructor
public class RefundExpectationsController {

    private final RefundExpectationsPort refundService;

    @PostMapping("/refund-expectations")
    public ResponseEntity<RefundExpectationsResponse> registerExpectations(
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
        @RequestBody @Valid RefundExpectationsRequest request
    ) {
        RefundExpectationsResult result = refundService.registerExpectations(
            new RefundExpectationsCommand(
                request.orderId(),
                request.refundRequestId(),
                request.orderItemIds(),
                correlationId != null ? correlationId : CorrelationIdContext.get()
            ));

        return ResponseEntity.ok(new RefundExpectationsResponse(
            result.orderId(),
            result.refundRequestId(),
            result.markedCount()
        ));
    }
}
