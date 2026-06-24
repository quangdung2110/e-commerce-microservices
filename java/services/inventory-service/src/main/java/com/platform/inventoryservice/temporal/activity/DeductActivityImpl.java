package com.platform.inventoryservice.temporal.activity;

import com.platform.inventoryservice.domain.entity.InventoryReservation;
import com.platform.inventoryservice.domain.enums.ReservationStatus;
import com.platform.inventoryservice.domain.exception.DomainException;
import com.platform.inventoryservice.domain.exception.InsufficientReservedException;
import com.platform.inventoryservice.domain.exception.InvariantDriftException;
import com.platform.inventoryservice.domain.exception.SkuSuspendedException;
import com.platform.inventoryservice.domain.vo.SkuWarehouseKey;
import com.platform.inventoryservice.port.outbound.cache.StockAtomicOps;
import com.platform.inventoryservice.port.outbound.cache.StockMutationResult;
import com.platform.inventoryservice.port.outbound.database.InventoryReservationRepository;
import com.platform.inventoryservice.port.outbound.metrics.InventoryMetrics;
import com.platform.inventoryservice.temporal.DeductInventoryActivity;
import com.platform.inventoryservice.temporal.command.DeductCommand;
import com.platform.inventoryservice.temporal.command.DeductCommandItem;
import com.platform.inventoryservice.temporal.result.DeductResult;
import com.platform.inventoryservice.temporal.result.DeductResultItem;
import io.temporal.activity.Activity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class DeductActivityImpl implements DeductInventoryActivity {

    private final StockAtomicOps stockOps;
    private final InventoryReservationRepository reservationRepo;
    private final InventoryMetrics metrics;

    @Override
    @Transactional
    public DeductResult deduct(DeductCommand cmd) {
        String correlationId = Activity.getExecutionContext().getInfo().getWorkflowId();
        Instant deductedAt = Instant.now();
        List<DeductResultItem> results = new ArrayList<>();

        for (DeductCommandItem item : cmd.items()) {
            SkuWarehouseKey key = new SkuWarehouseKey(item.skuId(), item.warehouseId());

            StockMutationResult res = stockOps.deduct(
                key, cmd.orderId(), item.orderItemId(),
                item.productId(), item.quantity(), correlationId);

            switch (res.returnCode()) {
                case APPLIED -> {
                    // Reservation row UPDATE status RESERVED → DEDUCTED.
                    Optional<InventoryReservation> existing = reservationRepo
                        .findActiveByOrderSkuWarehouse(cmd.orderId(), item.skuId(), item.warehouseId());
                    if (existing.isPresent()) {
                        reservationRepo.updateStatus(existing.get().getId(), ReservationStatus.DEDUCTED);
                    }
                    results.add(new DeductResultItem(
                        item.orderItemId(), item.skuId(), item.warehouseId(),
                        item.quantity(), ReservationStatus.DEDUCTED));
                }
                case IDEMPOTENT_HIT -> {
                    log.info("Deduct idempotent hit sku={} order={}",
                             item.skuId(), cmd.orderId());
                    results.add(new DeductResultItem(
                        item.orderItemId(), item.skuId(), item.warehouseId(),
                        item.quantity(), ReservationStatus.DEDUCTED));
                }
                case INSUFFICIENT_RESERVED -> {
                    throw new InsufficientReservedException(item.skuId(), item.warehouseId());
                }
                case SKU_SUSPENDED -> {
                    throw new SkuSuspendedException(
                        item.skuId(), item.warehouseId(), "MANUAL_REVIEW");
                }
                case INVARIANT_DRIFT -> {
                    throw new InvariantDriftException(item.skuId(), item.warehouseId());
                }
                default -> throw new IllegalStateException(
                    "Unexpected return code: " + res.returnCode());
            }
        }

        metrics.recordDeductSuccess(cmd.items().size());
        return new DeductResult(cmd.orderId(), results, deductedAt);
    }
}
