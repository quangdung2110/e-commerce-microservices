package com.platform.inventoryservice.routing;

import com.platform.inventoryservice.domain.vo.RoutePlan;
import java.util.List;

record RoutePlanProto(
    long warehouseId,
    long totalAvailable,
    List<RoutePlan.RoutePlanItem> items
) {}
