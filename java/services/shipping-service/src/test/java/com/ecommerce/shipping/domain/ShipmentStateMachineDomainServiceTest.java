package com.ecommerce.shipping.domain;

import com.ecommerce.shipping.domain.enums.ShipmentStatus;
import com.ecommerce.shipping.domain.enums.ShipmentTransitionEvent;
import com.ecommerce.shipping.domain.service.ShipmentStateMachineDomainService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShipmentStateMachineDomainServiceTest {

    private final ShipmentStateMachineDomainService service = new ShipmentStateMachineDomainService();

    @Test
    void shouldAllowPendingToReadyToShip() {
        // Theo thiết kế shipping_state_machine.md mục 1.2: S1 PENDING + LABEL_CONFIRMED -> READY_TO_SHIP
        assertTrue(service.canTransition(ShipmentStatus.PENDING, ShipmentTransitionEvent.LABEL_CONFIRMED, 0));
        assertEquals(
                ShipmentStatus.READY_TO_SHIP,
                service.resolveNextStatus(ShipmentStatus.PENDING, ShipmentTransitionEvent.LABEL_CONFIRMED, 0));
    }

    @Test
    void shouldBlockRetryDeliveryWhenExceedLimit() {
        // Theo thiết kế shipping_state_machine.md mục 1.3: FAILED -> IN_TRANSIT tối đa 3 lần
        assertFalse(service.canTransition(ShipmentStatus.FAILED, ShipmentTransitionEvent.RETRY_DELIVERY, 3));
        assertThrows(
                IllegalStateException.class,
                () -> service.resolveNextStatus(ShipmentStatus.FAILED, ShipmentTransitionEvent.RETRY_DELIVERY, 3));
    }

    @Test
    void shouldRejectTransitionFromTerminalState() {
        // Theo thiết kế shipping_state_machine.md mục 1.3: terminal states không có transition ra
        assertFalse(service.canTransition(ShipmentStatus.DELIVERED, ShipmentTransitionEvent.RETURN_PICKUP, 0));
    }
}
