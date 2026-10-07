package com.ecommerce.review.privacy;

import com.ecommerce.review.support.TestProperties;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-007: the author pseudonym and the deletion-guard key. */
class IdentityHmacTest {

    private static final UUID USER = UUID.fromString("6f9619ff-8b86-d011-b42d-00c04fc964ff");

    private final IdentityHmac hmac = new IdentityHmac(TestProperties.withSecrets("alias-secret", "guard-secret"));

    @Test
    void authorAliasIsDeterministicAndCarriesNoProfileData() {
        String first = hmac.authorDisplayName(USER);
        String second = hmac.authorDisplayName(USER);

        assertThat(first).isEqualTo(second);
        // "Khách hàng #" plus exactly twelve uppercase base32 characters.
        assertThat(first).matches("Khách hàng #[A-Z2-7]{12}");
    }

    @Test
    void aliasIsCaseInsensitiveInTheUuidItDerivesFrom() {
        UUID upperCaseSpelling = UUID.fromString(USER.toString().toUpperCase(java.util.Locale.ROOT));
        assertThat(hmac.authorDisplayName(upperCaseSpelling)).isEqualTo(hmac.authorDisplayName(USER));
    }

    @Test
    void differentAccountsGetDifferentAliases() {
        assertThat(hmac.authorDisplayName(UUID.randomUUID()))
                .isNotEqualTo(hmac.authorDisplayName(UUID.randomUUID()));
    }

    /** Rotating the alias secret deliberately starts a new pseudonym namespace. */
    @Test
    void aliasChangesWithTheSecret() {
        IdentityHmac rotated = new IdentityHmac(TestProperties.withSecrets("other-alias-secret", "guard-secret"));
        assertThat(rotated.authorDisplayName(USER)).isNotEqualTo(hmac.authorDisplayName(USER));
    }

    @Test
    void subjectKeyIs32BytesAndVersioned() {
        SubjectKey key = hmac.subjectKey(USER);

        assertThat(key.bytes()).hasSize(32);
        assertThat(key.version()).isEqualTo((short) 1);
        assertThat(key).isEqualTo(hmac.subjectKey(USER));
    }

    /** The guard key must never be readable from a log line. */
    @Test
    void subjectKeyDoesNotPrintItsMaterial() {
        assertThat(hmac.subjectKey(USER).toString()).isEqualTo("SubjectKey[v1, redacted]");
    }

    @Test
    void theTwoDerivationsAreDomainSeparated() {
        IdentityHmac sameSecretForBoth = new IdentityHmac(TestProperties.withSecrets("shared", "shared"));
        String aliasSuffix = sameSecretForBoth.authorDisplayName(USER).substring("Khách hàng #".length());
        String guardPrefix = com.ecommerce.review.common.Base32
                .encode(sameSecretForBoth.subjectKey(USER).bytes()).substring(0, 12);

        // Same secret, same user, different prefixes: the alias can never be reversed
        // into the guard key or the other way round.
        assertThat(aliasSuffix).isNotEqualTo(guardPrefix);
    }

    @Test
    void missingSecretsFailFast() {
        assertThatThrownBy(() -> new IdentityHmac(TestProperties.withSecrets("", "guard-secret")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("required");
    }

}
