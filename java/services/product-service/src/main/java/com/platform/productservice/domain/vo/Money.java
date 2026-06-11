package com.platform.productservice.domain.vo;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * Money value object representing a monetary amount with currency.
 * Immutable and comparable.
 */
public final class Money implements Comparable<Money>, Serializable {

    private static final long serialVersionUID = 1L;
    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;

    private final BigDecimal amount;
    private final Currency currency;

    private Money(BigDecimal amount, Currency currency) {
        this.amount = amount.setScale(SCALE, ROUNDING_MODE);
        this.currency = currency;
    }

    public static Money of(BigDecimal amount) {
        Objects.requireNonNull(amount, "Amount must not be null");
        return new Money(amount, Currency.getInstance("USD"));
    }

    public static Money of(double amount) {
        return new Money(BigDecimal.valueOf(amount), Currency.getInstance("USD"));
    }

    public static Money zero() {
        return new Money(BigDecimal.ZERO, Currency.getInstance("USD"));
    }

    public static Money cents(long cents) {
        return new Money(BigDecimal.valueOf(cents).divide(BigDecimal.valueOf(100), SCALE, ROUNDING_MODE),
                         Currency.getInstance("USD"));
    }

    public Money add(Money other) {
        validateCurrency(other);
        return new Money(this.amount.add(other.amount), this.currency);
    }

    public Money subtract(Money other) {
        validateCurrency(other);
        return new Money(this.amount.subtract(other.amount), this.currency);
    }

    public Money multiply(double multiplier) {
        return new Money(this.amount.multiply(BigDecimal.valueOf(multiplier)), this.currency);
    }

    public boolean isPositive() {
        return amount.compareTo(BigDecimal.ZERO) > 0;
    }

    public boolean isZero() {
        return amount.compareTo(BigDecimal.ZERO) == 0;
    }

    public boolean isGreaterThan(Money other) {
        validateCurrency(other);
        return this.amount.compareTo(other.amount) > 0;
    }

    public Money min(Money other) {
        return this.isGreaterThan(other) ? other : this;
    }

    public Money max(Money other) {
        return this.isGreaterThan(other) ? this : other;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Currency getCurrency() {
        return currency;
    }

    @Override
    public int compareTo(Money other) {
        validateCurrency(other);
        return this.amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Money money = (Money) o;
        return Objects.equals(amount, money.amount) && Objects.equals(currency, money.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount, currency);
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }

    private void validateCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch: " + this.currency + " vs " + other.currency);
        }
    }
}
