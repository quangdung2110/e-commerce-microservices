package com.platform.inventoryservice.port.inbound;

import com.platform.inventoryservice.service.command.RestockCommand;
import com.platform.inventoryservice.service.result.RestockResult;

public interface RestockPort {
    RestockResult restock(RestockCommand cmd);
}
