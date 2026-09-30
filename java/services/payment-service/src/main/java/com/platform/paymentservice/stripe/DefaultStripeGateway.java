package com.platform.paymentservice.stripe;

import com.stripe.StripeClient;
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

final class DefaultStripeGateway implements StripeGateway {
    private final StripeClient client;

    DefaultStripeGateway(StripeClient client) {
        this.client = client;
    }

    @Override
    public PaymentIntent createPaymentIntent(PaymentIntentCreateParams params, RequestOptions options)
            throws StripeException {
        return client.v1().paymentIntents().create(params, options);
    }

    @Override
    public PaymentIntent cancelPaymentIntent(String paymentIntentId, PaymentIntentCancelParams params,
                                             RequestOptions options) throws StripeException {
        return client.v1().paymentIntents().cancel(paymentIntentId, params, options);
    }

    @Override
    public PaymentIntent retrievePaymentIntent(String paymentIntentId, RequestOptions options)
            throws StripeException {
        return client.v1().paymentIntents().retrieve(paymentIntentId, options);
    }

    @Override
    public StripeSearchResult<PaymentIntent> searchPaymentIntents(PaymentIntentSearchParams params,
                                                                  RequestOptions options)
            throws StripeException {
        return client.v1().paymentIntents().search(params, options);
    }

    @Override
    public Refund createRefund(RefundCreateParams params, RequestOptions options)
            throws StripeException {
        return client.v1().refunds().create(params, options);
    }

    @Override
    public Refund retrieveRefund(String refundId, RequestOptions options)
            throws StripeException {
        return client.v1().refunds().retrieve(refundId, options);
    }

    @Override
    public StripeCollection<Refund> listRefunds(RefundListParams params, RequestOptions options)
            throws StripeException {
        return client.v1().refunds().list(params, options);
    }

    @Override
    public Dispute retrieveDispute(String disputeId, RequestOptions options) throws StripeException {
        return client.v1().disputes().retrieve(disputeId, options);
    }

    @Override
    public StripeCollection<BalanceTransaction> listBalanceTransactions(
            BalanceTransactionListParams params, RequestOptions options) throws StripeException {
        return client.v1().balanceTransactions().list(params, options);
    }
}
