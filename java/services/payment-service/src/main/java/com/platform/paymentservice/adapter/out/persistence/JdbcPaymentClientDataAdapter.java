package com.platform.paymentservice.adapter.out.persistence;

import com.platform.paymentservice.application.port.out.EncryptedSecret;
import com.platform.paymentservice.application.port.out.PaymentClientDataPort;
import com.platform.paymentservice.domain.PaymentStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public final class JdbcPaymentClientDataAdapter implements PaymentClientDataPort {
    private final JdbcClient jdbc;

    public JdbcPaymentClientDataAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StoredClientData> find(String transactionId) {
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT id, payment_attempt_number, status, client_secret_ciphertext,
                       client_secret_key_id, client_expires_at
                  FROM payment_transactions WHERE id = :id
                """)
                .param("id", transactionId).query().listOfRows();
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> row = rows.getFirst();
        byte[] ciphertext = (byte[]) row.get("client_secret_ciphertext");
        String keyId = (String) row.get("client_secret_key_id");
        return Optional.of(new StoredClientData(
                (String) row.get("id"),
                ((Number) row.get("payment_attempt_number")).intValue(),
                PaymentStatus.valueOf((String) row.get("status")),
                ciphertext == null ? null : new EncryptedSecret(ciphertext, keyId),
                instant(row.get("client_expires_at"))));
    }

    private static Instant instant(Object value) {
        return value == null ? null : ((Timestamp) value).toInstant();
    }
}
