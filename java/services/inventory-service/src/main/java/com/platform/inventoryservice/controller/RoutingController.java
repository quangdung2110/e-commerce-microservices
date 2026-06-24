package com.platform.inventoryservice.controller;

import com.platform.inventoryservice.domain.vo.CustomerLocation;
import com.platform.inventoryservice.domain.vo.RoutePlan;
import com.platform.inventoryservice.dto.request.RoutingOptionsRequest;
import com.platform.inventoryservice.dto.response.RoutingOptionsResponse;
import com.platform.inventoryservice.port.inbound.RoutingPort;
import com.platform.inventoryservice.routing.RoutingItem;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/inventory")
@RequiredArgsConstructor
public class RoutingController {

    private final RoutingPort routingPort;

    @PostMapping("/routing-options")
    public ResponseEntity<RoutingOptionsResponse> route(
        @RequestBody @Valid RoutingOptionsRequest request
    ) {
        CustomerLocation location = new CustomerLocation(
            request.customerLocation().cityCode(),
            request.customerLocation().districtCode()
        );

        List<RoutingItem> items = request.items().stream()
            .map(i -> new RoutingItem(i.skuId(), i.quantity()))
            .toList();

        RoutePlan plan = routingPort.route(location, items);

        return ResponseEntity.ok(RoutingOptionsResponse.from(plan));
    }
}
