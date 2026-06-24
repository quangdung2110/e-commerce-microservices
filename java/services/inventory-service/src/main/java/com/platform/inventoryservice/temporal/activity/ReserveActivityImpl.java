package com.platform.inventoryservice.temporal.activity;

import com.platform.inventoryservice.config.InventoryProperties;
import com.platform.inventoryservice.domain.entity.InventoryReservation;
import com.platform.inventoryservice.domain.enums.ChangeType;
import com.platform.inventoryservice.domain.enums.ReservationStatus;
import com.platform.inventoryservice.domain.exception.DomainException;
import com.platform.inventoryservice.domain.exception.InvariantDriftException;
import com.platform.inventoryservice.domain.exception.OutOfStockException;
import com.platform.inventoryservice.domain.exception.SkuSuspendedException;
import com.platform.inventoryservice.domain.vo.SkuWarehouseKey;
import com.platform.inventoryservice.port.outbound.cache.StockAtomicOps;
import com.platform.inventoryservice.port.outbound.cache.StockMutationResult;
import com.platform.inventoryservice.port.outbound.database.InventoryReservationRepository;
import com.platform.inventoryservice.port.outbound.metrics.InventoryMetrics;
import com.platform.inventoryservice.temporal.ReserveInventoryActivity;
import com.platform.inventoryservice.temporal.command.ReserveCommand;
import com.platform.inventoryservice.temporal.command.ReserveCommandItem;
import com.platform.inventoryservice.temporal.result.ReserveResult;
import com.platform.inventoryservice.temporal.result.ReserveResultItem;
import io.temporal.activity.Activity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReserveActivityImpl implements ReserveInventoryActivity {

    private final StockAtomicOps stockOps;
    private final InventoryReservationRepository reservationRepo;
    private final InventoryProperties props;
    private final InventoryMetrics metrics;

    @Override
    @Transactional
    public ReserveResult reserve(ReserveCommand cmd) {
        String correlationId = Activity.getExecutionContext().getInfo().getWorkflowId();
        Instant reservedAt = Instant.now();
        Instant expiresAt = reservedAt.plus(
            Duration.ofMinutes(props.getRestock().getReservationTtlMinutes()));
        List<ReserveResultItem> results = new ArrayList<>();
        List<ReserveCommandItem> applied = new ArrayList<>();

        try {
            // 1. Lua reserve cho từng item
            for (ReserveCommandItem item : cmd.items()) {
                SkuWarehouseKey key = new SkuWarehouseKey(item.skuId(), item.warehouseId());

                StockMutationResult res = stockOps.reserve(
                    key, cmd.orderId(), item.orderItemId(),
                    item.productId(), item.quantity(), correlationId);

                switch (res.returnCode()) {
                    case APPLIED -> {
                        applied.add(item);

                        // 2. Insert reservation row (idempotent via partial UNIQUE)
                        InventoryReservation reservation = InventoryReservation.newReserved(
                            cmd.orderId(), item.orderItemId(),
                            item.skuId(), item.productId(), item.warehouseId(),
                            item.quantity(), cmd.routePlanId(), expiresAt);
                        reservationRepo.insertIfAbsent(reservation);

                        results.add(new ReserveResultItem(
                            item.orderItemId(), item.skuId(), item.productId(), item.warehouseId(),
                            item.quantity(), ReservationStatus.RESERVED));
                    }
                    case IDEMPOTENT_HIT -> {
                        log.info("Reserve idempotent hit for sku={} order={}",
                                 item.skuId(), cmd.orderId());
                        results.add(new ReserveResultItem(
                            item.orderItemId(), item.skuId(), item.productId(), item.warehouseId(),
                            item.quantity(), ReservationStatus.RESERVED));
                    }
                    case OUT_OF_STOCK -> throw new OutOfStockException(item.skuId(), item.warehouseId());
                    case SKU_SUSPENDED -> throw new SkuSuspendedException(item.skuId(), item.warehouseId(), "MANUAL_REVIEW");
                    case INVARIANT_DRIFT -> throw new InvariantDriftException(item.skuId(), item.warehouseId());
                    default -> throw new IllegalStateException("Unexpected return code: " + res.returnCode());
                }
            }

            metrics.recordReserveSuccess(cmd.items().size());
            return new ReserveResult(cmd.orderId(), results, reservedAt, expiresAt);

        } catch (Exception e) {
            if (!applied.isEmpty()) {
                log.warn("Exception during reserve, rolling back {} applied items for order {}", applied.size(), cmd.orderId());
                rollbackApplied(cmd.orderId(), applied, ChangeType.ORDER_CANCELLED, correlationId);
            }
            if (e instanceof DomainException) {
                metrics.recordReserveFailure(e.getClass().getSimpleName());
            } else {
                metrics.recordReserveFailure("InternalError");
            }
            throw e;   // Temporal retry policy sẽ xử lý
        }
    }

    private void rollbackApplied(long orderId, List<ReserveCommandItem> applied,
                                 ChangeType changeType, String correlationId) {
        for (ReserveCommandItem item : applied) {
            try {
                SkuWarehouseKey key = new SkuWarehouseKey(item.skuId(), item.warehouseId());
                stockOps.release(key, orderId, item.orderItemId(),
                                 item.productId(), item.quantity(),
                                 correlationId, changeType);
            } catch (Exception e) {
                log.error("Rollback failed for order={} sku={}", orderId, item.skuId(), e);
                // Drift risk: log alert; admin manual reconcile
            }
        }
    }
}
