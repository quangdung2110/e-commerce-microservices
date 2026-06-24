package com.platform.inventoryservice.port.inbound;

import com.platform.inventoryservice.service.command.RefundExpectationsCommand;
import com.platform.inventoryservice.service.result.RefundExpectationsResult;

public interface RefundExpectationsPort {
    RefundExpectationsResult registerExpectations(RefundExpectationsCommand cmd);
}
