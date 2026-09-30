package com.platform.paymentservice.adapter.out.crypto;

import com.platform.paymentservice.application.port.out.SecretCipherPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

@Configuration
class LocalCryptoConfiguration {
    @Bean
    @ConditionalOnProperty(name = "payment.crypto.local-key")
    SecretCipherPort localSecretCipher(
            @Value("${payment.crypto.local-key}") String encodedKey,
            Environment environment) {
        Set<String> profiles = Set.of(environment.getActiveProfiles());
        if (!profiles.contains("dev") && !profiles.contains("test")) {
            throw new IllegalStateException(
                    "payment.crypto.local-key is permitted only in dev or test profile");
        }
        byte[] key = Base64.getDecoder().decode(encodedKey);
        return new LocalAesGcmSecretCipher(keyId(key), key, Map.of());
    }

    private static String keyId(byte[] key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
            return "local-" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
