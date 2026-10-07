package com.platform.paymentservice.adapter.out.crypto;

import com.platform.paymentservice.application.port.out.EncryptedSecret;
import com.platform.paymentservice.application.port.out.SecretCipherPort;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class LocalAesGcmSecretCipher implements SecretCipherPort {
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final byte FORMAT_VERSION = 1;

    private final String currentKeyId;
    private final Map<String, SecretKeySpec> keys;
    private final SecureRandom random;

    public LocalAesGcmSecretCipher(String currentKeyId, byte[] currentKey,
                                   Map<String, byte[]> previousKeys) {
        this(currentKeyId, currentKey, previousKeys, new SecureRandom());
    }

    LocalAesGcmSecretCipher(String currentKeyId, byte[] currentKey,
                            Map<String, byte[]> previousKeys, SecureRandom random) {
        this.currentKeyId = requireKeyId(currentKeyId);
        this.random = Objects.requireNonNull(random);
        Map<String, SecretKeySpec> configured = new LinkedHashMap<>();
        configured.put(currentKeyId, key(currentKey));
        previousKeys.forEach((id, material) -> configured.put(requireKeyId(id), key(material)));
        this.keys = Map.copyOf(configured);
    }

    @Override
    public EncryptedSecret encrypt(char[] plaintext, String context) {
        Objects.requireNonNull(plaintext);
        if (plaintext.length == 0) throw new IllegalArgumentException("plaintext must not be empty");
        byte[] clear = encode(plaintext);
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(currentKeyId), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(contextBytes(context));
            byte[] sealed = cipher.doFinal(clear);
            ByteBuffer payload = ByteBuffer.allocate(1 + iv.length + sealed.length);
            payload.put(FORMAT_VERSION).put(iv).put(sealed);
            return new EncryptedSecret(payload.array(), currentKeyId);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("client secret encryption failed", exception);
        } finally {
            Arrays.fill(clear, (byte) 0);
        }
    }

    @Override
    public char[] decrypt(EncryptedSecret encrypted, String context) {
        Objects.requireNonNull(encrypted);
        SecretKeySpec key = keys.get(encrypted.keyId());
        if (key == null) throw new IllegalStateException("client secret key is unavailable");
        byte[] payload = encrypted.ciphertext();
        if (payload.length <= 1 + IV_BYTES || payload[0] != FORMAT_VERSION) {
            throw new IllegalArgumentException("unsupported client secret ciphertext");
        }
        byte[] iv = Arrays.copyOfRange(payload, 1, 1 + IV_BYTES);
        byte[] sealed = Arrays.copyOfRange(payload, 1 + IV_BYTES, payload.length);
        byte[] clear = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(contextBytes(context));
            clear = cipher.doFinal(sealed);
            return decode(clear);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("client secret decryption failed", exception);
        } finally {
            Arrays.fill(payload, (byte) 0);
            Arrays.fill(iv, (byte) 0);
            Arrays.fill(sealed, (byte) 0);
            if (clear != null) Arrays.fill(clear, (byte) 0);
        }
    }

    private static byte[] encode(char[] value) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("client secret is not valid UTF-8", exception);
        }
    }

    private static char[] decode(byte[] value) {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(value));
            char[] characters = new char[decoded.remaining()];
            decoded.get(characters);
            return characters;
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("decrypted client secret is not valid UTF-8", exception);
        }
    }

    private static byte[] contextBytes(String context) {
        if (context == null || context.isBlank()) throw new IllegalArgumentException("encryption context is required");
        return context.getBytes(StandardCharsets.UTF_8);
    }

    private static SecretKeySpec key(byte[] material) {
        if (material == null || material.length != KEY_BYTES) {
            throw new IllegalArgumentException("AES-256 key must contain exactly 32 bytes");
        }
        return new SecretKeySpec(material.clone(), "AES");
    }

    private static String requireKeyId(String keyId) {
        if (keyId == null || keyId.isBlank() || keyId.length() > 64) {
            throw new IllegalArgumentException("invalid key id");
        }
        return keyId;
    }
}
