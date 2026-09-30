package com.platform.paymentservice.stripe;

import com.platform.paymentservice.application.port.out.ProviderBalancePort;
import com.stripe.model.BalanceTransaction;
import com.stripe.model.StripeCollection;
import com.stripe.net.RequestOptions;
import com.stripe.param.BalanceTransactionListParams;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StripeProviderBalanceAdapterTest {
    @Test
    void maps_pinned_sdk_balance_page_with_window_cursor_and_money_fields() throws Exception {
        BalanceTransaction transaction = new BalanceTransaction();
        transaction.setId("txn_123"); transaction.setType("charge");
        transaction.setSource("ch_123"); transaction.setAmount(10_000L);
        transaction.setFee(300L); transaction.setNet(9_700L);
        transaction.setCurrency("vnd"); transaction.setCreated(1_798_800_000L);
        FakeGateway gateway = new FakeGateway(transaction);
        StripeProviderBalanceAdapter adapter = new StripeProviderBalanceAdapter(gateway,
                Duration.ofSeconds(5), Duration.ofSeconds(15), 2);

        ProviderBalancePort.Page page = adapter.list(
                Instant.ofEpochSecond(1_798_700_000L), Instant.ofEpochSecond(1_798_900_000L),
                "txn_before", 100);

        assertThat(page.entries()).containsExactly(new ProviderBalancePort.Entry(
                "txn_123", "charge", "ch_123", 10_000, 300, 9_700,
                "VND", Instant.ofEpochSecond(1_798_800_000L)));
        assertThat(page.nextCursor()).isEqualTo("txn_123");
        assertThat(page.hasMore()).isFalse();
        assertThat(gateway.params.getStartingAfter()).isEqualTo("txn_before");
        assertThat(gateway.params.getLimit()).isEqualTo(100L);
        assertThat(gateway.options.getMaxNetworkRetries()).isEqualTo(2);
    }

    private static final class FakeGateway implements StripeGateway {
        private final BalanceTransaction value;
        private BalanceTransactionListParams params;
        private RequestOptions options;
        private FakeGateway(BalanceTransaction value) { this.value = value; }
        @Override public com.stripe.model.PaymentIntent createPaymentIntent(
                com.stripe.param.PaymentIntentCreateParams p, RequestOptions o) { throw new UnsupportedOperationException(); }
        @Override public com.stripe.model.PaymentIntent cancelPaymentIntent(String id,
                com.stripe.param.PaymentIntentCancelParams p, RequestOptions o) { throw new UnsupportedOperationException(); }
        @Override public com.stripe.model.PaymentIntent retrievePaymentIntent(String id, RequestOptions o) {
            throw new UnsupportedOperationException();
        }
        @Override public com.stripe.model.StripeSearchResult<com.stripe.model.PaymentIntent> searchPaymentIntents(
                com.stripe.param.PaymentIntentSearchParams p, RequestOptions o) { throw new UnsupportedOperationException(); }
        @Override public StripeCollection<BalanceTransaction> listBalanceTransactions(
                BalanceTransactionListParams params, RequestOptions options) {
            this.params = params; this.options = options;
            StripeCollection<BalanceTransaction> page = new StripeCollection<>();
            page.setData(List.of(value)); page.setHasMore(false); return page;
        }
    }
}
