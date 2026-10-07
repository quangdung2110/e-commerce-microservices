package com.platform.paymentservice.adapter.out.persistence;

import com.platform.paymentservice.application.port.out.IdGeneratorPort;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

@Component
public final class UlidIdGenerator implements IdGeneratorPort {
    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private final Clock clock;
    private final SecureRandom random;

    public UlidIdGenerator() {
        this(Clock.systemUTC(), new SecureRandom());
    }

    UlidIdGenerator(Clock clock, SecureRandom random) {
        this.clock = clock;
        this.random = random;
    }

    @Override public String nextPaymentTransactionId() { return "TXN_" + ulid(); }
    @Override public String nextProviderOperationId() { return "POP_" + ulid(); }
    @Override public String nextReviewCaseId() { return "PRC_" + ulid(); }
    @Override public String nextRefundTransactionId() { return "RFD_" + ulid(); }
    @Override public String nextDisputeTransactionId() { return "DSP_" + ulid(); }
    @Override public String nextFinancialJournalId() { return "FJN_" + ulid(); }
    @Override public UUID nextEventId() { return UUID.randomUUID(); }

    private String ulid() {
        long timestamp = clock.millis();
        if (timestamp < 0 || timestamp > 0xFFFFFFFFFFFFL) {
            throw new IllegalStateException("clock is outside ULID timestamp range");
        }
        byte[] bytes = new byte[16];
        bytes[0] = (byte) (timestamp >>> 40);
        bytes[1] = (byte) (timestamp >>> 32);
        bytes[2] = (byte) (timestamp >>> 24);
        bytes[3] = (byte) (timestamp >>> 16);
        bytes[4] = (byte) (timestamp >>> 8);
        bytes[5] = (byte) timestamp;
        byte[] randomness = new byte[10];
        random.nextBytes(randomness);
        System.arraycopy(randomness, 0, bytes, 6, randomness.length);
        BigInteger value = new BigInteger(1, bytes);
        char[] encoded = new char[26];
        BigInteger radix = BigInteger.valueOf(32);
        for (int index = encoded.length - 1; index >= 0; index--) {
            BigInteger[] division = value.divideAndRemainder(radix);
            encoded[index] = CROCKFORD[division[1].intValue()];
            value = division[0];
        }
        return new String(encoded);
    }
}
