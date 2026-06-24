package com.platform.inventoryservice.temporal;

import com.platform.inventoryservice.temporal.command.RollbackCommand;
import com.platform.inventoryservice.temporal.result.RollbackResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface RollbackInventoryActivity {
    @ActivityMethod(name = "RollbackInventory")
    RollbackResult rollback(RollbackCommand cmd);
}
