package com.ecommerce.checkout.crypto;

import com.ecommerce.checkout.config.CheckoutProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Envelope encryption for everything Checkout stores that carries PII or the raw token (data.md):
 * every value gets its own random AES-256 data key, which is itself wrapped with the versioned
 * key-encryption key from the secret manager, and the value is sealed with AES-GCM.
 *
 * <p>Wire form: {@code e1.<kek-version>.<wrapped-dek>.<sealed-value>} (base64url parts). The
 * {@code purpose} is authenticated data, so a ciphertext cannot be replayed under another field.
 * Redis Lua only reads plaintext control fields, so nothing here needs to run inside Redis.
 */
@Component
public class EnvelopeCipher {

    private static final String PREFIX = "e1";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int DEK_BYTES = 32;

    private final KeyRing keys;
    private final SecureRandom random = SecureIds.random();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    private final Base64.Decoder decoder = Base64.getUrlDecoder();

    public EnvelopeCipher(CheckoutProperties properties) {
        this.keys = KeyRing.parse(properties.keys().encryptionKeys(), properties.keys().encryptionActiveVersion(),
                32, 32);
    }

    public String activeVersion() {
        return keys.activeVersion();
    }

    public String encrypt(String purpose, String plaintext) {
        return encrypt(purpose, plaintext.getBytes(StandardCharsets.UTF_8));
    }

    public String encrypt(String purpose, byte[] plaintext) {
        try {
            byte[] dek = new byte[DEK_BYTES];
            random.nextBytes(dek);
            String version = keys.activeVersion();
            byte[] wrapped = seal(keys.key(version), dek, ("dek:" + purpose).getBytes(StandardCharsets.UTF_8));
            byte[] sealed = seal(dek, plaintext, ("val:" + purpose).getBytes(StandardCharsets.UTF_8));
            return PREFIX + "." + version + "." + encoder.encodeToString(wrapped) + "."
                    + encoder.encodeToString(sealed);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    public String decrypt(String purpose, String ciphertext) {
        return new String(decryptBytes(purpose, ciphertext), StandardCharsets.UTF_8);
    }

    public byte[] decryptBytes(String purpose, String ciphertext) {
        String[] parts = ciphertext.split("\\.", -1);
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            throw new IllegalArgumentException("not an envelope ciphertext");
        }
        try {
            byte[] kek = keys.key(parts[1]);
            byte[] dek = open(kek, decoder.decode(parts[2]), ("dek:" + purpose).getBytes(StandardCharsets.UTF_8));
            return open(dek, decoder.decode(parts[3]), ("val:" + purpose).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("ciphertext cannot be opened", e);
        }
    }

    private byte[] seal(byte[] key, byte[] plaintext, byte[] aad) throws GeneralSecurityException {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(aad);
        byte[] encrypted = cipher.doFinal(plaintext);
        byte[] out = new byte[IV_BYTES + encrypted.length];
        System.arraycopy(iv, 0, out, 0, IV_BYTES);
        System.arraycopy(encrypted, 0, out, IV_BYTES, encrypted.length);
        return out;
    }

    private byte[] open(byte[] key, byte[] sealed, byte[] aad) throws GeneralSecurityException {
        if (sealed.length <= IV_BYTES) {
            throw new IllegalArgumentException("sealed value too short");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES));
        cipher.updateAAD(aad);
        return cipher.doFinal(sealed, IV_BYTES, sealed.length - IV_BYTES);
    }
}
