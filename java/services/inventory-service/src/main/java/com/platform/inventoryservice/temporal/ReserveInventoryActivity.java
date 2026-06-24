package com.platform.inventoryservice.temporal;

import com.platform.inventoryservice.temporal.command.ReserveCommand;
import com.platform.inventoryservice.temporal.result.ReserveResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface ReserveInventoryActivity {
    @ActivityMethod(name = "ReserveInventory")
    ReserveResult reserve(ReserveCommand cmd);
}
