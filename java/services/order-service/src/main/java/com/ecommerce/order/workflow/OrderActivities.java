package com.ecommerce.order.workflow;

import com.ecommerce.order.workflow.ActivityResults.Applied;
import com.ecommerce.order.workflow.ActivityResults.CancelResult;
import com.ecommerce.order.workflow.ActivityResults.NextAttempt;
import com.ecommerce.order.workflow.ActivityResults.Outcome;
import com.ecommerce.order.workflow.ActivityResults.Prepared;
import com.ecommerce.order.workflow.ActivityResults.Ready;
import com.ecommerce.order.workflow.ActivityResults.RefundState;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.Map;

/**
 * Every Order activity (architecture.md §3.3). Implementations are DB-idempotent and use
 * stable remote command identities, so Temporal retries and workflow message redelivery
 * can never double-apply a transition.
 */
@ActivityInterface
public interface OrderActivities {

    // Checkout (WF-ORDER-004/005/009/011)
    @ActivityMethod(name = "CreateOrderActivity")
    Prepared prepareOrder(String checkoutToken);

    @ActivityMethod(name = "ReserveInventoryActivity")
    Outcome reserveInventory(long orderId);

    @ActivityMethod(name = "ReservePromotionActivity")
    Outcome reservePromotion(long orderId, String promotionId);

    @ActivityMethod(name = "InitiatePaymentActivity")
    Outcome initiatePayment(long orderId, int attemptNumber, String operationId, String returnUrl);

    @ActivityMethod(name = "MarkPaymentReadyActivity")
    Ready markPaymentReady(long orderId);

    @ActivityMethod(name = "FailPreparationActivity")
    void failPreparation(long orderId, String errorCode, String failedPromotionId, String failedReason);

    @ActivityMethod(name = "UpdateOrderStatusActivity")
    Applied applyPaymentResult(long orderId, Map<String, String> attributes);

    @ActivityMethod(name = "BeginDeadlineCancellationActivity")
    Applied beginDeadlineCancellation(long orderId);

    @ActivityMethod(name = "CancelPaymentActivity")
    CancelResult cancelPayment(long orderId);

    @ActivityMethod(name = "RollbackPromotionActivity")
    Outcome releasePromotion(long orderId, String promotionId);

    @ActivityMethod(name = "RollbackInventoryActivity")
    Outcome releaseInventory(long orderId);

    @ActivityMethod(name = "CancelOrderActivity")
    Outcome completeCancellation(long orderId);

    @ActivityMethod(name = "CommitInventoryActivity")
    Outcome commitInventory(long orderId);

    @ActivityMethod(name = "ConfirmPromotionActivity")
    Outcome confirmPromotion(long orderId, String promotionId);

    @ActivityMethod(name = "EscalateFinancialReconciliationActivity")
    void escalatePostCapture(long orderId, String obligationType, String detail);

    @ActivityMethod(name = "CompletePostPaymentActivity")
    boolean completePostPayment(long orderId);

    @ActivityMethod(name = "PrepareNextPaymentAttemptActivity")
    NextAttempt prepareNextAttempt(long orderId, String operationId, String returnUrl);

    @ActivityMethod(name = "FinishPaymentAttemptOperationActivity")
    void finishAttemptOperation(long orderId, String operationId, int attemptNumber, String outcome);

    @ActivityMethod(name = "RejectOperationActivity")
    void rejectOperation(String operationId, String errorCode, String detail);

    // Refund (WF-ORDER-008)
    @ActivityMethod(name = "LoadRefundActivity")
    RefundState loadRefund(long orderId, String mode, String attemptId);

    @ActivityMethod(name = "RegisterRefundScopeActivity")
    Outcome registerRefundScope(long orderId);

    @ActivityMethod(name = "EnterManualVerificationActivity")
    void enterManualVerification(long orderId, String reason);

    @ActivityMethod(name = "CheckRefundGateActivity")
    String checkRefundGate(long orderId);

    @ActivityMethod(name = "InitiateRefundActivity")
    Outcome initiateRefund(long orderId);

    @ActivityMethod(name = "FinalizeRefundResultActivity")
    String applyRefundResult(long orderId, Map<String, String> attributes);

    @ActivityMethod(name = "ReleaseCommittedOrderBeforeDispatchActivity")
    Outcome releaseBeforeDispatch(long orderId, String attemptId);

    @ActivityMethod(name = "MarkAdminAttemptRecoveryActivity")
    void markAdminAttemptRecovery(String attemptId);

    // Scope finalization (WF-ORDER-008a)
    @ActivityMethod(name = "ExecuteRefundScopeFinalizationActivity")
    String executeFinalization(long orderId, int generation);

    @ActivityMethod(name = "MarkFinalizationRecoveryActivity")
    void markFinalizationRecovery(long orderId, int generation);

    // Financial reconciliation (WF-ORDER-010)
    @ActivityMethod(name = "ResumePostCaptureRepairActivity")
    String resumePostCaptureRepair(long orderId);
}
