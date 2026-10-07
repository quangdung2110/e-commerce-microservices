package com.ecommerce.order.domain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Allocates the whole-order discount across immutable lines exactly once at checkout
 * (ADR-017): proportional to gross value, largest remainder, ties broken by the stable
 * line key. Shares always sum to the order discount and no share exceeds its gross.
 */
public final class DiscountAllocator {

    public record Line(long key, long grossMinor) {
    }

    private DiscountAllocator() {
    }

    public static Map<Long, Long> allocate(List<Line> lines, long totalDiscountMinor) {
        if (totalDiscountMinor < 0) {
            throw new IllegalArgumentException("discount must not be negative");
        }
        BigInteger totalGross = lines.stream().map(l -> BigInteger.valueOf(l.grossMinor()))
                .reduce(BigInteger.ZERO, BigInteger::add);
        if (BigInteger.valueOf(totalDiscountMinor).compareTo(totalGross) > 0) {
            throw new IllegalArgumentException("discount exceeds the gross amount");
        }
        Map<Long, Long> shares = new LinkedHashMap<>();
        if (totalDiscountMinor == 0 || totalGross.signum() == 0) {
            lines.forEach(l -> shares.put(l.key(), 0L));
            return shares;
        }
        record Part(long key, long floor, BigInteger remainder) {
        }
        List<Part> parts = new ArrayList<>();
        long allocated = 0;
        BigInteger discount = BigInteger.valueOf(totalDiscountMinor);
        for (Line line : lines) {
            BigInteger[] qr = BigInteger.valueOf(line.grossMinor()).multiply(discount).divideAndRemainder(totalGross);
            long floor = qr[0].longValueExact();
            parts.add(new Part(line.key(), floor, qr[1]));
            allocated += floor;
        }
        long leftover = totalDiscountMinor - allocated;
        List<Part> byRemainder = new ArrayList<>(parts);
        byRemainder.sort(Comparator.comparing(Part::remainder).reversed().thenComparingLong(Part::key));
        Map<Long, Long> extra = new LinkedHashMap<>();
        for (int i = 0; i < leftover; i++) {
            extra.merge(byRemainder.get(i).key(), 1L, Long::sum);
        }
        for (Part part : parts) {
            shares.put(part.key(), part.floor() + extra.getOrDefault(part.key(), 0L));
        }
        return shares;
    }
}
