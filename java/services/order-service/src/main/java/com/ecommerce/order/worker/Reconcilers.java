package com.ecommerce.order.worker;

import com.ecommerce.order.config.OrderProperties;
import com.ecommerce.order.service.InboxReplayer;
import com.ecommerce.order.service.OrderConvergence;
import com.ecommerce.order.service.WorkflowEnqueuer;
import com.ecommerce.order.store.InboxStore;
import com.ecommerce.order.store.OrderStore;
import com.ecommerce.order.store.OutboxStore;
import com.ecommerce.order.store.RefundStore;
import com.ecommerce.order.store.WorkflowOutboxStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Durable convergence owners (INV-ORD-030): replay parked identity bindings, re-dispatch
 * unfinished scope finalization (including financially terminal cases), converge
 * deliverable orders, and trim the outbox only behind exact delivery receipts.
 */
@Component
public class Reconcilers {

    private static final Logger log = LoggerFactory.getLogger(Reconcilers.class);

    private final InboxStore inbox;
    private final RefundStore refunds;
    private final OutboxStore outbox;
    private final InboxReplayer replayer;
    private final OrderConvergence convergence;
    private final WorkflowEnqueuer enqueuer;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final OrderProperties properties;

    public Reconcilers(InboxStore inbox, RefundStore refunds, OutboxStore outbox, InboxReplayer replayer,
                       OrderConvergence convergence, WorkflowEnqueuer enqueuer, NamedParameterJdbcTemplate jdbc,
                       TransactionTemplate tx, OrderProperties properties, WorkflowOutboxStore workflowOutbox,
                       OrderStore orders, MeterRegistry meters) {
        this.inbox = inbox;
        this.refunds = refunds;
        this.outbox = outbox;
        this.replayer = replayer;
        this.convergence = convergence;
        this.enqueuer = enqueuer;
        this.jdbc = jdbc;
        this.tx = tx;
        this.properties = properties;
        Gauge.builder("order.workflow_message.pending", workflowOutbox, WorkflowOutboxStore::pendingMessages)
                .register(meters);
        Gauge.builder("order.fulfillment.delivery_confirmation_overdue", orders,
                        o -> o.countDispatchedUnconfirmed(properties.fulfillment().deliveryConfirmationOverdue()))
                .description("Lines dispatched longer ago than order.fulfillment.delivery-confirmation-overdue without an Admin delivery confirmation")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${order.workers.reconcile-interval}", initialDelayString = "PT10S")
    public void reconcile() {
        if (!properties.workers().enabled()) {
            return;
        }
        try {
            reconcileOnce();
        } catch (RuntimeException e) {
            log.warn("Reconciler pass failed: {}", e.toString());
        }
    }

    /** One pass; public for tests. */
    public void reconcileOnce() {
        for (Long orderId : inbox.ordersWithParked("PENDING_IDENTITY_BIND", 100)) {
            replayer.replayIdentityBind(orderId);
        }
        for (RefundStore.CaseRow refundCase : refunds.unresolvedFinalization(100)) {
            tx.executeWithoutResult(s -> enqueuer.dispatch(refundCase.orderId(),
                    enqueuer.finalization(refundCase.orderId(), refundCase.finalizationGeneration())));
        }
        List<Long> deliverable = jdbc.query("""
                SELECT o.id FROM orders o
                WHERE o.status = 'PROCESSING'
                  AND NOT EXISTS (SELECT 1 FROM order_item_lines l
                                  WHERE l.order_id = o.id AND l.fulfillment_status <> 'DELIVERED')
                LIMIT 100
                """, Map.of(), (rs, i) -> rs.getLong(1));
        for (Long orderId : deliverable) {
            tx.executeWithoutResult(s -> convergence.tryComplete(orderId));
        }
    }

    @Scheduled(cron = "${order.outbox.trim-cron}")
    public void trimOutbox() {
        if (!properties.workers().enabled()) {
            return;
        }
        Duration retention = properties.outbox().retention();
        Duration safety = properties.outbox().receiptSafetyDelay();
        int deleted;
        int total = 0;
        do {
            deleted = outbox.trim(retention, safety, 1000);
            total += deleted;
        } while (deleted == 1000);
        long withoutReceipt = outbox.countWithoutReceipt(retention);
        log.info("Outbox trim deleted {} rows; {} rows past retention retained without exact receipt", total,
                withoutReceipt);
    }
}
