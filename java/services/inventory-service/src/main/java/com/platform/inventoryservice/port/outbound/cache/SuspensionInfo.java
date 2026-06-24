package com.platform.inventoryservice.port.outbound.cache;

public record SuspensionInfo(String reason, long lockedAtMs, String status) {
}
