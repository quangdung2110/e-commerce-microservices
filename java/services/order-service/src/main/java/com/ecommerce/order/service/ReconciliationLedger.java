package com.ecommerce.order.service;

import com.ecommerce.order.common.Ids;
import com.ecommerce.order.domain.RefundTypes.ObligationType;
import com.ecommerce.order.store.ReconciliationStore;
import com.ecommerce.order.store.ReconciliationStore.ObligationRow;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Opens/attaches typed obligations under the per-order envelope and keeps the envelope
 * status derived from its children (ADR-018). Callers hold the order row lock.
 */
@Component
public class ReconciliationLedger {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationLedger.class);

    public record Evidence(String source, String reference, String sha256, Long version, String detailJson) {
    }

    private final ReconciliationStore store;
    private final MeterRegistry meters;

    public ReconciliationLedger(ReconciliationStore store, MeterRegistry meters) {
        this.store = store;
        this.meters = meters;
    }

    /** Returns the active obligation for (type, resource), creating it when absent. */
    public ObligationRow open(long orderId, ObligationType type, String resourceId, String detail,
                              List<Evidence> evidence) {
        ReconciliationStore.CaseRow envelope = store.ensureCase(orderId);
        ObligationRow obligation = store.activeObligation(orderId, type, resourceId).orElse(null);
        boolean created = obligation == null;
        if (created) {
            UUID id = Ids.newUuid();
            store.insertObligation(id, envelope.caseId(), orderId, type, resourceId, detail);
            obligation = store.obligation(id).orElseThrow();
            meters.counter("order.reconciliation.obligation_opened", "type", type.name()).increment();
            log.warn("Reconciliation obligation opened order={} type={} resource={} detail={}", orderId, type,
                    resourceId, detail);
        }
        for (Evidence e : evidence) {
            store.addEvidence(obligation.obligationId(), e.source(), e.reference(), e.sha256(), e.version(),
                    e.detailJson());
        }
        if (created || !evidence.isEmpty()) {
            store.bumpCase(orderId, envelopeStatus(orderId));
        }
        return obligation;
    }

    public void attachEvidence(UUID obligationId, long orderId, Evidence e) {
        store.addEvidence(obligationId, e.source(), e.reference(), e.sha256(), e.version(), e.detailJson());
        store.bumpCase(orderId, envelopeStatus(orderId));
    }

    /** Closes one obligation; the envelope closes only when every obligation is closed. */
    public void close(long orderId, UUID obligationId, String resolution, String accountingRef) {
        store.updateObligation(obligationId, "CLOSED", resolution, accountingRef);
        store.bumpCase(orderId, envelopeStatus(orderId));
    }

    public void closeActive(long orderId, ObligationType type, String resourceId, String resolution) {
        store.activeObligation(orderId, type, resourceId)
                .ifPresent(o -> close(orderId, o.obligationId(), resolution, null));
    }

    public void setStatus(long orderId, UUID obligationId, String status, String resolution, String accountingRef) {
        store.updateObligation(obligationId, status, resolution, accountingRef);
        store.bumpCase(orderId, envelopeStatus(orderId));
    }

    public boolean hasOpen(long orderId, ObligationType type) {
        return store.obligations(orderId).stream().anyMatch(o -> o.type() == type && o.open());
    }

    public boolean hasOpenPostCaptureRepair(long orderId) {
        return store.obligations(orderId).stream().anyMatch(o -> o.open() && o.type().postCaptureRepair());
    }

    private String envelopeStatus(long orderId) {
        List<ObligationRow> all = store.obligations(orderId);
        if (all.stream().noneMatch(ObligationRow::open)) {
            return "CLOSED";
        }
        if (all.stream().filter(ObligationRow::open).allMatch(o -> "DECIDED".equals(o.status()))) {
            return "DECIDED";
        }
        if (all.stream().filter(ObligationRow::open).anyMatch(o -> "OPEN".equals(o.status()))) {
            return "OPEN";
        }
        return "EVIDENCE_PENDING";
    }
}
