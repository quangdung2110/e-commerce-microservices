package com.platform.paymentservice.application.port.out;

import java.util.Arrays;
import java.util.Objects;

public final class EncryptedSecret {
    private final byte[] ciphertext;
    private final String keyId;

    public EncryptedSecret(byte[] ciphertext, String keyId) {
        if (ciphertext == null || ciphertext.length == 0) {
            throw new IllegalArgumentException("ciphertext must not be empty");
        }
        this.ciphertext = ciphertext.clone();
        this.keyId = Objects.requireNonNull(keyId);
    }

    public byte[] ciphertext() {
        return ciphertext.clone();
    }

    public String keyId() {
        return keyId;
    }

    @Override
    public String toString() {
        return "EncryptedSecret[ciphertext=REDACTED,keyId=" + keyId + "]";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EncryptedSecret encrypted
                && keyId.equals(encrypted.keyId)
                && Arrays.equals(ciphertext, encrypted.ciphertext);
    }

    @Override
    public int hashCode() {
        return 31 * keyId.hashCode() + Arrays.hashCode(ciphertext);
    }
}
