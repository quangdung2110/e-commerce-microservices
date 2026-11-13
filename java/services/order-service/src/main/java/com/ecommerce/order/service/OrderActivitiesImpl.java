package com.ecommerce.order.service;

import com.ecommerce.order.domain.RefundTypes.ObligationType;
import com.ecommerce.order.store.OrderStore;
import com.ecommerce.order.store.ReconciliationStore;
import com.ecommerce.order.workflow.ActivityResults.Applied;
import com.ecommerce.order.workflow.ActivityResults.CancelResult;
import com.ecommerce.order.workflow.ActivityResults.NextAttempt;
import com.ecommerce.order.workflow.ActivityResults.Outcome;
import com.ecommerce.order.workflow.ActivityResults.Prepared;
import com.ecommerce.order.workflow.ActivityResults.Ready;
import com.ecommerce.order.workflow.ActivityResults.RefundState;
import com.ecommerce.order.workflow.OrderActivities;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

/** Spring bean registered with every Order task queue; delegates to the saga services. */
@Component
public class OrderActivitiesImpl implements OrderActivities {

    private final CheckoutSaga checkout;
    private final RefundSaga refund;
    private final ScopeFinalization finalization;
    private final OrderStore orders;
    private final ReconciliationStore reconciliationStore;
    private final ReconciliationLedger reconciliation;
    private final TransactionTemplate tx;

    public OrderActivitiesImpl(CheckoutSaga checkout, RefundSaga refund, ScopeFinalization finalization,
                               OrderStore orders, ReconciliationStore reconciliationStore,
                               ReconciliationLedger reconciliation, TransactionTemplate tx) {
        this.checkout = checkout;
        this.refund = refund;
        this.finalization = finalization;
        this.orders = orders;
        this.reconciliationStore = reconciliationStore;
        this.reconciliation = reconciliation;
        this.tx = tx;
    }

    @Override
    public Prepared prepareOrder(String checkoutToken) {
        return checkout.prepareOrder(checkoutToken);
    }

    @Override
    public Outcome reserveInventory(long orderId) {
        return checkout.reserveInventory(orderId);
    }

    @Override
    public Outcome reservePromotion(long orderId, String promotionId) {
        return checkout.reservePromotion(orderId, promotionId);
    }

    @Override
    public Outcome initiatePayment(long orderId, int attemptNumber, String operationId, String returnUrl) {
        return checkout.initiatePayment(orderId, attemptNumber, operationId, returnUrl);
    }

    @Override
    public Ready markPaymentReady(long orderId) {
        return checkout.markPaymentReady(orderId);
    }

    @Override
    public void failPreparation(long orderId, String errorCode, String failedPromotionId, String failedReason) {
        checkout.failPreparation(orderId, errorCode, failedPromotionId, failedReason);
    }

    @Override
    public Applied applyPaymentResult(long orderId, Map<String, String> attributes) {
        return checkout.applyPaymentResult(orderId, attributes);
    }

    @Override
    public Applied beginDeadlineCancellation(long orderId) {
        return checkout.beginDeadlineCancellation(orderId);
    }

    @Override
    public CancelResult cancelPayment(long orderId) {
        return checkout.cancelPayment(orderId);
    }

    @Override
    public Outcome releasePromotion(long orderId, String promotionId) {
        return checkout.releasePromotion(orderId, promotionId);
    }

    @Override
    public Outcome releaseInventory(long orderId) {
        return checkout.releaseInventory(orderId);
    }

    @Override
    public Outcome completeCancellation(long orderId) {
        return checkout.completeCancellation(orderId);
    }

    @Override
    public Outcome commitInventory(long orderId) {
        return checkout.commitInventory(orderId);
    }

    @Override
    public Outcome confirmPromotion(long orderId, String promotionId) {
        return checkout.confirmPromotion(orderId, promotionId);
    }

    @Override
    public void escalatePostCapture(long orderId, String obligationType, String detail) {
        checkout.escalatePostCapture(orderId, obligationType, detail);
    }

    @Override
    public boolean completePostPayment(long orderId) {
        return checkout.completePostPayment(orderId);
    }

    @Override
    public NextAttempt prepareNextAttempt(long orderId, String operationId, String returnUrl) {
        return checkout.prepareNextAttempt(orderId, operationId, returnUrl);
    }

    @Override
    public void finishAttemptOperation(long orderId, String operationId, int attemptNumber, String outcome) {
        checkout.finishAttemptOperation(orderId, operationId, attemptNumber, outcome);
    }

    @Override
    public void rejectOperation(String operationId, String errorCode, String detail) {
        checkout.rejectOperation(operationId, errorCode, detail);
    }

    @Override
    public RefundState loadRefund(long orderId, String mode, String attemptId) {
        return refund.load(orderId, mode, attemptId);
    }

    @Override
    public Outcome registerRefundScope(long orderId) {
        return refund.registerScope(orderId);
    }

    @Override
    public void enterManualVerification(long orderId, String reason) {
        refund.enterManualVerification(orderId, reason);
    }

    @Override
    public String checkRefundGate(long orderId) {
        return refund.checkGate(orderId);
    }

    @Override
    public Outcome initiateRefund(long orderId) {
        return refund.initiateRefund(orderId);
    }

    @Override
    public String applyRefundResult(long orderId, Map<String, String> attributes) {
        return refund.applyResult(orderId, attributes);
    }

    @Override
    public Outcome releaseBeforeDispatch(long orderId, String attemptId) {
        return refund.releaseBeforeDispatch(orderId, attemptId);
    }

    @Override
    public void markAdminAttemptRecovery(String attemptId) {
        refund.markAdminAttemptRecovery(attemptId);
    }

    @Override
    public String executeFinalization(long orderId, int generation) {
        return finalization.execute(orderId, generation);
    }

    @Override
    public void markFinalizationRecovery(long orderId, int generation) {
        finalization.markRecovery(orderId, generation);
    }

    /**
     * RESUME_FORWARD_REPAIR for post-capture obligations: replays the original commit and
     * confirmation command IDs; only proven receipts close the obligations (T8.5).
     */
    @Override
    public String resumePostCaptureRepair(long orderId) {
        Outcome inventory = checkout.commitInventory(orderId);
        boolean allAccepted = inventory.isAccepted();
        boolean anyRejected = inventory.isRejected();
        for (OrderStore.PromotionRow promo : orders.promotions(orderId)) {
            Outcome confirm = checkout.confirmPromotion(orderId, promo.promotionId());
            allAccepted &= confirm.isAccepted();
            anyRejected |= confirm.isRejected();
        }
        if (!allAccepted) {
            return anyRejected ? "REJECTED" : "PENDING";
        }
        tx.executeWithoutResult(s -> {
            orders.lock(orderId);
            reconciliationStore.obligations(orderId).stream()
                    .filter(o -> o.open() && (o.type() == ObligationType.POST_CAPTURE_INVENTORY_REPAIR
                            || o.type() == ObligationType.POST_CAPTURE_PROMOTION_REPAIR))
                    .forEach(o -> reconciliation.close(orderId, o.obligationId(), "RESUME_FORWARD_REPAIR", null));
        });
        checkout.completePostPayment(orderId);
        return "COMPLETED";
    }
}
