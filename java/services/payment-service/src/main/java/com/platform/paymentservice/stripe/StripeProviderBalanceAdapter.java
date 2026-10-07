package com.platform.paymentservice.stripe;

import com.platform.paymentservice.application.port.out.ProviderBalancePort;
import com.stripe.exception.StripeException;
import com.stripe.model.BalanceTransaction;
import com.stripe.model.StripeCollection;
import com.stripe.net.RequestOptions;
import com.stripe.param.BalanceTransactionListParams;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

final class StripeProviderBalanceAdapter implements ProviderBalancePort {
    private final StripeGateway gateway;
    private final RequestOptions options;

    StripeProviderBalanceAdapter(StripeGateway gateway, Duration connectTimeout,
                                 Duration readTimeout, int maxNetworkRetries) {
        this.gateway = Objects.requireNonNull(gateway);
        this.options = RequestOptions.builder()
                .setConnectTimeout(exactMillis(connectTimeout, "connectTimeout"))
                .setReadTimeout(exactMillis(readTimeout, "readTimeout"))
                .setMaxNetworkRetries(maxNetworkRetries).build();
    }

    @Override
    public Page list(Instant windowFrom, Instant windowTo, String startingAfter, int limit) {
        try {
            BalanceTransactionListParams.Created created = BalanceTransactionListParams.Created.builder()
                    .setGte(windowFrom.getEpochSecond()).setLt(windowTo.getEpochSecond()).build();
            BalanceTransactionListParams.Builder builder = BalanceTransactionListParams.builder()
                    .setCreated(created).setLimit((long) limit);
            if (startingAfter != null && !startingAfter.isBlank()) builder.setStartingAfter(startingAfter);
            StripeCollection<BalanceTransaction> page = gateway.listBalanceTransactions(builder.build(), options);
            List<Entry> entries = page.getData().stream().map(this::map).toList();
            String next = entries.isEmpty() ? startingAfter : entries.getLast().id();
            return new Page(entries, next, Boolean.TRUE.equals(page.getHasMore()));
        } catch (StripeException failure) {
            throw new ProviderBalanceException("BALANCE_LIST_UNAVAILABLE", failure);
        }
    }

    private Entry map(BalanceTransaction value) {
        return new Entry(value.getId(), normalized(value.getType()), value.getSource(),
                required(value.getAmount(), "amount"), required(value.getFee(), "fee"),
                required(value.getNet(), "net"), upper(value.getCurrency()),
                Instant.ofEpochSecond(required(value.getCreated(), "created")));
    }

    private static long required(Long value, String field) {
        if (value == null) throw new ProviderBalanceException("BALANCE_" + field.toUpperCase(Locale.ROOT) + "_MISSING", null);
        return value;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private static int exactMillis(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative() || duration.toMillis() > Integer.MAX_VALUE)
            throw new IllegalArgumentException(name + " must be a positive integer millisecond duration");
        return Math.toIntExact(duration.toMillis());
    }
}
