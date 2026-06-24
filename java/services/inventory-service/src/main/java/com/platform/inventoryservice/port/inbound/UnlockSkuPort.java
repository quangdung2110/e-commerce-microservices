package com.platform.inventoryservice.port.inbound;

import com.platform.inventoryservice.service.command.UnlockSkuCommand;
import com.platform.inventoryservice.service.result.UnlockSkuResult;

public interface UnlockSkuPort {
    UnlockSkuResult unlock(UnlockSkuCommand cmd);
}
