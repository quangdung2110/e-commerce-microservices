package com.platform.paymentservice.adapter.out.crypto;

import com.platform.paymentservice.application.port.out.EncryptedSecret;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalAesGcmSecretCipherTest {
    private static final byte[] OLD_KEY = "0123456789abcdef0123456789abcdef"
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NEW_KEY = "abcdef0123456789abcdef0123456789"
            .getBytes(StandardCharsets.US_ASCII);

    @Test
    void encrypts_with_context_and_never_exposes_plaintext_in_value_rendering() {
        LocalAesGcmSecretCipher cipher = new LocalAesGcmSecretCipher("key-v1", OLD_KEY, Map.of());
        char[] plaintext = "pi_test_secret_value".toCharArray();

        EncryptedSecret encrypted = cipher.encrypt(plaintext, "TXN_CONTEXT");

        assertThat(new String(encrypted.ciphertext(), StandardCharsets.ISO_8859_1))
                .doesNotContain("pi_test_secret_value");
        assertThat(encrypted.toString()).doesNotContain("pi_test_secret_value");
        assertThat(cipher.decrypt(encrypted, "TXN_CONTEXT"))
                .containsExactly(plaintext);
        assertThatThrownBy(() -> cipher.decrypt(encrypted, "TXN_OTHER"))
                .isInstanceOf(IllegalStateException.class);
        Arrays.fill(plaintext, '\0');
    }

    @Test
    void rotation_decrypts_pending_rows_with_the_previous_key() {
        LocalAesGcmSecretCipher oldCipher = new LocalAesGcmSecretCipher("key-v1", OLD_KEY, Map.of());
        EncryptedSecret oldRow = oldCipher.encrypt("pi_old_secret".toCharArray(), "TXN_ROTATION");
        LocalAesGcmSecretCipher rotated = new LocalAesGcmSecretCipher(
                "key-v2", NEW_KEY, Map.of("key-v1", OLD_KEY));

        assertThat(rotated.decrypt(oldRow, "TXN_ROTATION"))
                .containsExactly("pi_old_secret".toCharArray());
        assertThat(rotated.encrypt("pi_new_secret".toCharArray(), "TXN_ROTATION").keyId())
                .isEqualTo("key-v2");
    }
}
