package com.platform.inventoryservice.controller;

import com.platform.inventoryservice.common.CorrelationIdContext;
import com.platform.inventoryservice.dto.request.UnlockSkuRequest;
import com.platform.inventoryservice.dto.response.UnlockSkuResponse;
import com.platform.inventoryservice.port.inbound.UnlockSkuPort;
import com.platform.inventoryservice.service.command.UnlockSkuCommand;
import com.platform.inventoryservice.service.result.UnlockSkuResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/inventory/admin")
@RequiredArgsConstructor
public class UnlockSkuController {

    private final UnlockSkuPort unlockService;

    @PostMapping("/unlock-sku")
    public ResponseEntity<UnlockSkuResponse> unlock(
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
        @RequestBody @Valid UnlockSkuRequest request
    ) {
        UnlockSkuResult result = unlockService.unlock(new UnlockSkuCommand(
            request.skuId(),
            request.warehouseId(),
            request.reason(),
            correlationId != null ? correlationId : CorrelationIdContext.get()
        ));

        return ResponseEntity.ok(new UnlockSkuResponse(
            result.skuId(), result.warehouseId(),
            result.unlocked(), result.unlockedAt()
        ));
    }
}
