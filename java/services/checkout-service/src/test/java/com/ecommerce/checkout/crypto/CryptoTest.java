package com.ecommerce.checkout.crypto;

import com.ecommerce.checkout.config.CheckoutProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoTest {

    private static String key(char fill) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) fill);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static CheckoutProperties properties(String hmacKeys, String hmacActive, String encKeys, String encActive) {
        return new CheckoutProperties("redis://x", new CheckoutProperties.Keys(hmacKeys, hmacActive, encKeys, encActive),
                null, null, null, null, null, null, null, null, false, Duration.ofMillis(200), Duration.ofMillis(100),
                Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(200));
    }

    @Test
    @DisplayName("Token and idempotency refs are deterministic, keyed, versioned and separated by purpose")
    void tokenHasher() {
        TokenHasher hasher = new TokenHasher(properties("v1:" + key('a'), "v1", "v1:" + key('b'), "v1"));
        String token = UUID.randomUUID().toString();

        assertThat(hasher.tokenRef(token)).isEqualTo(hasher.tokenRef(token));
        assertThat(hasher.tokenRef(token)).startsWith("v1:").hasSize(3 + 64).doesNotContain(token);
        assertThat(hasher.tokenRef(token)).isNotEqualTo(hasher.idempotencyRef(token));
        assertThat(hasher.tokenRef(token)).isNotEqualTo(hasher.tokenRef(UUID.randomUUID().toString()));
        assertThat(hasher.fingerprint(token)).hasSize(12).doesNotContain(token);
    }

    @Test
    @DisplayName("After a key rotation a record is still found under the old version")
    void rotation() {
        String token = UUID.randomUUID().toString();
        TokenHasher before = new TokenHasher(properties("v1:" + key('a'), "v1", "v1:" + key('b'), "v1"));
        TokenHasher after = new TokenHasher(properties("v1:" + key('a') + ",v2:" + key('c'), "v2", "v1:" + key('b'), "v1"));

        assertThat(after.tokenRef(token)).startsWith("v2:");
        assertThat(after.tokenRefs(token)).hasSize(2).contains(before.tokenRef(token));
        assertThat(after.tokenRefs(token).get(0)).isEqualTo(after.tokenRef(token));
    }

    @Test
    @DisplayName("Envelope encryption round-trips, is randomized and binds the ciphertext to its purpose")
    void envelopeCipher() {
        EnvelopeCipher cipher = new EnvelopeCipher(properties("v1:" + key('a'), "v1", "v1:" + key('b'), "v1"));
        String plain = "{\"recipient_name\":\"Nguyen Van A\"}";

        String one = cipher.encrypt("snapshot", plain);
        String two = cipher.encrypt("snapshot", plain);

        assertThat(cipher.decrypt("snapshot", one)).isEqualTo(plain);
        assertThat(one).isNotEqualTo(two).doesNotContain("Nguyen");
        assertThatThrownBy(() -> cipher.decrypt("problem", one)).isInstanceOf(IllegalArgumentException.class);
        String tampered = one.substring(0, one.length() - 2) + (one.endsWith("AA") ? "BB" : "AA");
        assertThatThrownBy(() -> cipher.decrypt("snapshot", tampered)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cipher.decrypt("snapshot", "plain text")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Data written under an old key version is readable after the active version changes")
    void encryptionKeyRotation() {
        EnvelopeCipher before = new EnvelopeCipher(properties("v1:" + key('a'), "v1", "v1:" + key('b'), "v1"));
        EnvelopeCipher after = new EnvelopeCipher(properties("v1:" + key('a'), "v1",
                "v1:" + key('b') + ",v2:" + key('d'), "v2"));
        String old = before.encrypt("payload", "secret payload");

        assertThat(after.decrypt("payload", old)).isEqualTo("secret payload");
        assertThat(after.encrypt("payload", "x")).startsWith("e1.v2.");
        assertThat(after.activeVersion()).isEqualTo("v2");
    }

    @Test
    @DisplayName("Key specs with a wrong length, bad base64, a missing active version or duplicates are refused")
    void keyRing() {
        assertThatThrownBy(() -> KeyRing.parse("v1:" + Base64.getEncoder().encodeToString(new byte[16]), "v1", 32, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KeyRing.parse("v1:not base64!", "v1", 32, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KeyRing.parse("v1:" + key('a'), "v2", 32, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KeyRing.parse("v1:" + key('a') + ",v1:" + key('b'), "v1", 32, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KeyRing.parse("v1:" + Base64.getEncoder().encodeToString(new byte[40]), "v1", 32, 32))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(KeyRing.parse("v1:" + key('a') + ",v2:" + key('b'), "v2", 32, 32).versionsActiveFirst())
                .isEqualTo(List.of("v2", "v1"));
    }

    @Test
    @DisplayName("Generated tokens are UUIDv4 and unique")
    void secureIds() {
        List<String> ids = java.util.stream.IntStream.range(0, 1000).mapToObj(i -> SecureIds.uuid()).toList();

        assertThat(ids).doesNotHaveDuplicates();
        assertThat(ids).allMatch(id -> UUID.fromString(id).version() == 4 && UUID.fromString(id).variant() == 2
                && id.equals(id.toLowerCase()));
    }
}
