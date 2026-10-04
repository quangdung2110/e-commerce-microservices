package com.ecommerce.inventory.web;

import com.ecommerce.inventory.service.AvailabilityReadService;
import com.ecommerce.inventory.web.dto.ReadDtos;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Function;

/** contracts/http/inventory/read/v1: advisory per-SKU availability for Product Detail BFF. */
@RestController
public class AvailabilityReadController {

    private final AvailabilityReadService service;

    public AvailabilityReadController(AvailabilityReadService service) {
        this.service = service;
    }

    @PostMapping("/internal/inventory/v1/sku-availabilities:batch-get")
    public ReadDtos.BatchSkuAvailabilityResponse batchGet(@Valid @RequestBody ReadDtos.BatchSkuAvailabilityRequest request) {
        RequestRules.requireCorrelation();
        RequestRules.unique("sku_ids", request.skuIds(), Function.identity());
        return service.read(request.skuIds());
    }
}
