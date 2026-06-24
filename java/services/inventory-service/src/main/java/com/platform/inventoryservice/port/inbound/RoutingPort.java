package com.platform.inventoryservice.port.inbound;

import com.platform.inventoryservice.domain.vo.CustomerLocation;
import com.platform.inventoryservice.domain.vo.RoutePlan;
import com.platform.inventoryservice.routing.RoutingItem;

import java.util.List;

public interface RoutingPort {
    RoutePlan route(CustomerLocation location, List<RoutingItem> items);
}
