package com.platform.paymentservice.stripe;

import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeSearchResult;
import com.stripe.model.StripeCollection;
import com.stripe.model.Refund;
import com.stripe.model.Dispute;
import com.stripe.model.BalanceTransaction;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.PaymentIntentSearchParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.RefundListParams;
import com.stripe.param.BalanceTransactionListParams;

interface StripeGateway {
    PaymentIntent createPaymentIntent(PaymentIntentCreateParams params, RequestOptions options)
            throws StripeException;

    PaymentIntent cancelPaymentIntent(String paymentIntentId, PaymentIntentCancelParams params,
                                      RequestOptions options) throws StripeException;

    PaymentIntent retrievePaymentIntent(String paymentIntentId, RequestOptions options)
            throws StripeException;

    StripeSearchResult<PaymentIntent> searchPaymentIntents(PaymentIntentSearchParams params,
                                                           RequestOptions options)
            throws StripeException;

    default Refund createRefund(RefundCreateParams params, RequestOptions options) throws StripeException {
        throw new UnsupportedOperationException("refund create is not configured");
    }

    default Refund retrieveRefund(String refundId, RequestOptions options) throws StripeException {
        throw new UnsupportedOperationException("refund retrieve is not configured");
    }

    default StripeCollection<Refund> listRefunds(RefundListParams params, RequestOptions options)
            throws StripeException {
        throw new UnsupportedOperationException("refund list is not configured");
    }

    default Dispute retrieveDispute(String disputeId, RequestOptions options) throws StripeException {
        throw new UnsupportedOperationException("dispute retrieve is not configured");
    }

    default StripeCollection<BalanceTransaction> listBalanceTransactions(
            BalanceTransactionListParams params, RequestOptions options) throws StripeException {
        throw new UnsupportedOperationException("balance transaction list is not configured");
    }
}
