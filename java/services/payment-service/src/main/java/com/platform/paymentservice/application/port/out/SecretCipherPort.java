package com.platform.paymentservice.application.port.out;

public interface SecretCipherPort {
    EncryptedSecret encrypt(char[] plaintext, String context);

    char[] decrypt(EncryptedSecret encrypted, String context);
}
