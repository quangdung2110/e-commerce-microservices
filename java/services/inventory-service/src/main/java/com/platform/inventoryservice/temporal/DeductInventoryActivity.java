package com.platform.inventoryservice.temporal;

import com.platform.inventoryservice.temporal.command.DeductCommand;
import com.platform.inventoryservice.temporal.result.DeductResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface DeductInventoryActivity {
    @ActivityMethod(name = "DeductInventory")
    DeductResult deduct(DeductCommand cmd);
}
