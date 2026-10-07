package com.platform.paymentservice.domain;

import java.util.Objects;

public record Money(long amountMinor, String currency) {
    public Money {
        if (amountMinor <= 0) throw new IllegalArgumentException("amountMinor must be positive");
        if (!Objects.equals(currency, "VND")) throw new IllegalArgumentException("currency must be VND");
    }
}
