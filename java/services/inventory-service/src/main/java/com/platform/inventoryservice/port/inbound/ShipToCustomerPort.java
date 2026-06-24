package com.platform.inventoryservice.port.inbound;

import com.platform.inventoryservice.service.command.ShipToCustomerCommand;
import com.platform.inventoryservice.service.result.ShipToCustomerResult;

public interface ShipToCustomerPort {
    ShipToCustomerResult ship(ShipToCustomerCommand cmd);
}
