package com.platform.inventoryservice.infrastructure.event;

import com.platform.inventoryservice.domain.event.ReturnReceivedAllPayload;
import com.platform.inventoryservice.domain.event.ShipToCustomerPayload;
import com.platform.inventoryservice.domain.event.StockChangedPayload;
import com.platform.inventoryservice.domain.event.StockMutationPayload;
import org.springframework.stereotype.Component;

@Component
public class PartitionKeyResolver {

    public String forStockMutation(StockMutationPayload payload) {
        return payload.skuId();   // per-SKU ordering
    }

    public String forStockChanged(StockChangedPayload payload) {
        return payload.skuId();
    }

    public String forReturnReceivedAll(ReturnReceivedAllPayload payload) {
        return String.valueOf(payload.orderId());   // per-order ordering
    }

    public String forShipToCustomer(ShipToCustomerPayload payload) {
        return String.valueOf(payload.orderId());
    }
}
