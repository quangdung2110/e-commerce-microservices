package com.ecommerce.order.service;

import com.ecommerce.order.messaging.PaymentIntake;
import com.ecommerce.order.store.InboxStore;
import com.ecommerce.order.store.OrderStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Replays Payment facts parked {@code PENDING_IDENTITY_BIND} once Order has bound the
 * transaction identity returned by its own HTTP command (WF-ORDER-008, data.md §3.6).
 */
@Component
public class InboxReplayer {

    private final InboxStore inbox;
    private final OrderStore orders;
    private final PaymentIntake payments;
    private final TransactionTemplate tx;

    public InboxReplayer(InboxStore inbox, OrderStore orders, PaymentIntake payments, TransactionTemplate tx) {
        this.inbox = inbox;
        this.orders = orders;
        this.payments = payments;
        this.tx = tx;
    }

    public void replayIdentityBind(long orderId) {
        tx.executeWithoutResult(s -> {
            orders.lock(orderId);
            for (InboxStore.InboxRow row : inbox.lockParked(orderId, "PENDING_IDENTITY_BIND")) {
                payments.reprocess(row);
            }
        });
    }
}
