package com.platform.inventoryservice.temporal.activity;

import com.platform.inventoryservice.domain.entity.InventoryReservation;
import com.platform.inventoryservice.domain.enums.ReservationStatus;
import com.platform.inventoryservice.domain.exception.DomainException;
import com.platform.inventoryservice.domain.exception.ReleaseUnderflowException;
import com.platform.inventoryservice.domain.exception.SkuSuspendedException;
import com.platform.inventoryservice.domain.vo.SkuWarehouseKey;
import com.platform.inventoryservice.port.outbound.cache.StockAtomicOps;
import com.platform.inventoryservice.port.outbound.cache.StockMutationResult;
import com.platform.inventoryservice.port.outbound.database.InventoryReservationRepository;
import com.platform.inventoryservice.port.outbound.metrics.InventoryMetrics;
import com.platform.inventoryservice.temporal.RollbackInventoryActivity;
import com.platform.inventoryservice.temporal.command.RollbackCommand;
import com.platform.inventoryservice.temporal.command.RollbackCommandItem;
import com.platform.inventoryservice.temporal.result.RollbackResult;
import com.platform.inventoryservice.temporal.result.RollbackResultItem;
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
public class RollbackActivityImpl implements RollbackInventoryActivity {

    private final StockAtomicOps stockOps;
    private final InventoryReservationRepository reservationRepo;
    private final InventoryMetrics metrics;

    @Override
    @Transactional
    public RollbackResult rollback(RollbackCommand cmd) {
        String correlationId = Activity.getExecutionContext().getInfo().getWorkflowId();
        Instant rolledBackAt = Instant.now();
        List<RollbackResultItem> results = new ArrayList<>();

        for (RollbackCommandItem item : cmd.items()) {
            SkuWarehouseKey key = new SkuWarehouseKey(item.skuId(), item.warehouseId());

            StockMutationResult res = stockOps.release(
                key, cmd.orderId(), item.orderItemId(),
                item.productId(), item.quantity(),
                correlationId, cmd.changeType());

            switch (res.returnCode()) {
                case APPLIED -> {
                    Optional<InventoryReservation> existing = reservationRepo
                        .findActiveByOrderSkuWarehouse(cmd.orderId(), item.skuId(), item.warehouseId());
                    if (existing.isPresent()) {
                        reservationRepo.updateStatus(existing.get().getId(), ReservationStatus.CANCELLED);
                    }
                    results.add(new RollbackResultItem(
                        item.orderItemId(), item.skuId(), item.warehouseId(),
                        item.quantity(), ReservationStatus.CANCELLED));
                }
                case IDEMPOTENT_HIT -> {
                    results.add(new RollbackResultItem(
                        item.orderItemId(), item.skuId(), item.warehouseId(),
                        item.quantity(), ReservationStatus.CANCELLED));
                }
                case RELEASE_UNDERFLOW -> {
                    throw new ReleaseUnderflowException(item.skuId(), item.warehouseId());
                }
                case SKU_SUSPENDED -> {
                    throw new SkuSuspendedException(
                        item.skuId(), item.warehouseId(), "MANUAL_REVIEW");
                }
                default -> throw new IllegalStateException(
                    "Unexpected return code: " + res.returnCode());
            }
        }

        metrics.recordRollbackSuccess(cmd.items().size(), cmd.changeType().name());
        return new RollbackResult(cmd.orderId(), results, cmd.changeType(), rolledBackAt);
    }
}
