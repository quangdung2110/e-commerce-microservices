package com.ecommerce.checkout.crypto;

import com.ecommerce.checkout.config.CheckoutProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.List;

/**
 * Lookup keys for Redis (data.md): {@code ref = version ":" hex(HMAC-SHA-256(key, purpose ":" value))}.
 * A raw checkout token or {@code Idempotency-Key} never appears in a key name, and the purpose
 * prefix keeps the two spaces from colliding. A record can be found under any configured version,
 * so rotating the active key does not orphan live sessions.
 */
@Component
public class TokenHasher {

    private static final HexFormat HEX = HexFormat.of();

    private final KeyRing keys;

    public TokenHasher(CheckoutProperties properties) {
        this.keys = KeyRing.parse(properties.keys().tokenHmacKeys(), properties.keys().tokenHmacActiveVersion(),
                32, 0);
    }

    public String tokenRef(String token) {
        return ref(keys.activeVersion(), "token", token);
    }

    /** Refs of the token under every configured key version, active first. */
    public List<String> tokenRefs(String token) {
        return keys.versionsActiveFirst().stream().map(version -> ref(version, "token", token)).toList();
    }

    public String idempotencyRef(String idempotencyKey) {
        return ref(keys.activeVersion(), "idem", idempotencyKey);
    }

    public List<String> idempotencyRefs(String idempotencyKey) {
        return keys.versionsActiveFirst().stream().map(version -> ref(version, "idem", idempotencyKey)).toList();
    }

    /** Short irreversible fingerprint that is safe to log in place of a token. */
    public String fingerprint(String token) {
        String ref = tokenRef(token);
        return ref.substring(ref.indexOf(':') + 1, ref.indexOf(':') + 13);
    }

    private String ref(String version, String purpose, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keys.key(version), "HmacSHA256"));
            byte[] digest = mac.doFinal((purpose + ":" + value).getBytes(StandardCharsets.UTF_8));
            return version + ":" + HEX.formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable", e);
        }
    }
}
