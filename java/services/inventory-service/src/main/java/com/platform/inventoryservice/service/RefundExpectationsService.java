package com.platform.inventoryservice.service;

import com.platform.inventoryservice.domain.entity.InventoryReservation;
import com.platform.inventoryservice.domain.enums.ReservationStatus;
import com.platform.inventoryservice.domain.exception.RefundScopeMismatchException;
import com.platform.inventoryservice.domain.exception.ReservationNotFoundException;
import com.platform.inventoryservice.port.inbound.RefundExpectationsPort;
import com.platform.inventoryservice.port.outbound.database.InventoryReservationRepository;
import com.platform.inventoryservice.port.outbound.metrics.InventoryMetrics;
import com.platform.inventoryservice.service.command.RefundExpectationsCommand;
import com.platform.inventoryservice.service.result.RefundExpectationsResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RefundExpectationsService implements RefundExpectationsPort {

    private final InventoryReservationRepository reservationRepo;
    private final InventoryMetrics metrics;

    @Override
    @Transactional
    public RefundExpectationsResult registerExpectations(RefundExpectationsCommand cmd) {
        // 1. Idempotency check
        List<InventoryReservation> existing =
            reservationRepo.findRefundExpectedByOrderIdAndRefundRequestId(
                cmd.orderId(), cmd.refundRequestId());

        Set<Long> existingSet = existing.stream()
            .map(InventoryReservation::getOrderItemId)
            .collect(Collectors.toSet());
        Set<Long> requestedSet = new TreeSet<>(cmd.orderItemIds());

        if (!existingSet.isEmpty() && !existingSet.equals(requestedSet)) {
            // Conflict: same (order, refund_request_id) but different items
            throw new RefundScopeMismatchException(
                new ArrayList<>(existingSet), cmd.orderItemIds());
        }

        if (!existingSet.isEmpty()) {
            // Idempotent hit — same scope
            log.info("Refund expectations idempotent hit order={} rrid={}",
                     cmd.orderId(), cmd.refundRequestId());
            return new RefundExpectationsResult(
                cmd.orderId(), cmd.refundRequestId(), existingSet.size());
        }

        // 2. Validate at least 1 DEDUCTED reservation exists
        List<InventoryReservation> allDeducted = reservationRepo
            .findByOrderIdAndOrderItemIdInAndStatus(
                cmd.orderId(), cmd.orderItemIds(), ReservationStatus.DEDUCTED);

        if (allDeducted.isEmpty()) {
            throw new ReservationNotFoundException(
                "No DEDUCTED reservations for order=" + cmd.orderId()
                + " items=" + cmd.orderItemIds());
        }

        // 3. Update reservations
        int markedCount = reservationRepo.markRefundExpected(
            cmd.orderId(), cmd.refundRequestId(), cmd.orderItemIds());

        metrics.recordRefundExpectation(markedCount);

        return new RefundExpectationsResult(
            cmd.orderId(), cmd.refundRequestId(), markedCount);
    }
}
