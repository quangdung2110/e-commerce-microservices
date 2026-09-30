package com.platform.paymentservice.integration;

import com.platform.paymentservice.adapter.out.crypto.LocalAesGcmSecretCipher;
import com.platform.paymentservice.application.port.out.EncryptedSecret;
import com.platform.paymentservice.application.service.PaymentClientDataResult;
import com.platform.paymentservice.application.service.PaymentClientDataService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentClientDataIT extends AbstractPaymentIT {
    private static final String TXN = "TXN_01K5V7M4R8Q2K7V6W5X1Y0ZABE";
    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");
    private static final byte[] KEY = "0123456789abcdef0123456789abcdef"
            .getBytes(StandardCharsets.US_ASCII);
    private final LocalAesGcmSecretCipher cipher =
            new LocalAesGcmSecretCipher("local-v1", KEY, Map.of());

    @Test
    void returns_plaintext_only_for_pending_and_before_client_expiry() {
        seed("PENDING", NOW.plusSeconds(30));
        PaymentClientDataService service = service(NOW);

        PaymentClientDataResult result = service.get(TXN);

        assertThat(result).isInstanceOf(PaymentClientDataResult.Usable.class);
        PaymentClientDataResult.Usable usable = (PaymentClientDataResult.Usable) result;
        assertThat(usable.clientSecret()).containsExactly("pi_client_secret".toCharArray());
        assertThat(usable.publishableKey()).isEqualTo("pk_test_documented");
        assertThat(usable.toString()).doesNotContain("pi_client_secret");
    }

    @Test
    void returns_unusable_at_expiry_without_decrypting_or_exposing_secret() {
        seed("PENDING", NOW);

        PaymentClientDataResult result = service(NOW).get(TXN);

        assertThat(result).isInstanceOf(PaymentClientDataResult.Unusable.class);
    }

    @Test
    void returns_unusable_outside_pending_even_when_ciphertext_still_exists() {
        seed("PROCESSING", NOW.plusSeconds(30));

        PaymentClientDataResult result = service(NOW).get(TXN);

        assertThat(result).isInstanceOf(PaymentClientDataResult.Unusable.class);
    }

    @Test
    void missing_transaction_is_not_found() {
        assertThat(service(NOW).get(TXN))
                .isInstanceOf(PaymentClientDataResult.NotFound.class);
    }

    @Test
    void unavailable_previous_key_degrades_to_unusable_without_plaintext() {
        seed("PENDING", NOW.plusSeconds(30));
        byte[] replacementKey = "abcdef0123456789abcdef0123456789"
                .getBytes(StandardCharsets.US_ASCII);
        LocalAesGcmSecretCipher withoutOldKey =
                new LocalAesGcmSecretCipher("local-v2", replacementKey, Map.of());
        PaymentClientDataService service = new PaymentClientDataService(
                clientData, withoutOldKey, () -> NOW, "pk_test_documented");

        assertThat(service.get(TXN)).isInstanceOf(PaymentClientDataResult.Unusable.class);
    }

    private void seed(String status, Instant clientExpiresAt) {
        EncryptedSecret encrypted = cipher.encrypt("pi_client_secret".toCharArray(), TXN);
        jdbc.update("""
                INSERT INTO payment_transactions
                  (id, order_id, payment_attempt_number, user_id, amount_minor, currency,
                   provider_transaction_id, client_secret_ciphertext, client_secret_key_id,
                   client_expires_at, status, expires_at)
                VALUES (?, 10002, 1, ?, 100, 'VND', 'pi_client', ?, ?, ?, ?, ?)
                """, TXN, UUID.randomUUID(), encrypted.ciphertext(), encrypted.keyId(),
                Timestamp.from(clientExpiresAt), status, Timestamp.from(NOW.plusSeconds(60)));
    }

    private PaymentClientDataService service(Instant now) {
        return new PaymentClientDataService(clientData, cipher, () -> now, "pk_test_documented");
    }
}
