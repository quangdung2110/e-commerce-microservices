package com.ecommerce.review.privacy;

import com.ecommerce.review.common.Base32;
import com.ecommerce.review.common.Uuids;
import com.ecommerce.review.config.ReviewProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.UUID;

/**
 * The two keyed derivations Review makes from a trusted user identifier (ADR-007).
 *
 * <p>Neither ever leaves this class in raw form. The author alias is display-only and
 * is never authorization input; the subject key is a match key and is never logged.
 * Review accepts no profile name or email, and calls neither User nor Auth to obtain
 * one.
 */
@Component
public class IdentityHmac {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String AUTHOR_ALIAS_DOMAIN = "review-author-alias:v1:";
    private static final String PRIVACY_GUARD_DOMAIN = "review-privacy-guard:v1:";
    private static final String ANONYMOUS_DISPLAY_NAME = "Khách hàng ẩn danh";
    private static final String ALIAS_PREFIX = "Khách hàng #";
    private static final int ALIAS_SUFFIX_LENGTH = 12;
    private static final short SUBJECT_KEY_VERSION = 1;

    private final byte[] aliasSecret;
    private final byte[] guardSecret;

    public IdentityHmac(ReviewProperties properties) {
        if (!properties.secretsConfigured()) {
            throw new IllegalStateException("REVIEW_AUTHOR_ALIAS_HMAC_SECRET and "
                    + "REVIEW_PRIVACY_GUARD_HMAC_SECRET are both required: a generated key would "
                    + "start a new pseudonym namespace and orphan every committed deletion guard");
        }
        this.aliasSecret = properties.secrets().authorAliasHmac().getBytes(StandardCharsets.UTF_8);
        this.guardSecret = properties.secrets().privacyGuardHmac().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The author pseudonym stored on a new review: {@code Khách hàng #} plus the first
     * twelve uppercase base32 characters of the alias HMAC. The same account keeps the
     * same pseudonym while the secret is stable, and no profile field or email is
     * involved (REVIEW-BR-016).
     */
    public String authorDisplayName(UUID ownerUserId) {
        byte[] mac = hmac(aliasSecret, AUTHOR_ALIAS_DOMAIN + Uuids.canonical(ownerUserId));
        return ALIAS_PREFIX + Base32.encode(mac).substring(0, ALIAS_SUFFIX_LENGTH);
    }

    /** What {@code USER_DELETED} writes over every pseudonym of the subject. */
    public static String anonymizedDisplayName() {
        return ANONYMOUS_DISPLAY_NAME;
    }

    /** The deletion-guard match key of a subject. */
    public SubjectKey subjectKey(UUID userId) {
        return new SubjectKey(hmac(guardSecret, PRIVACY_GUARD_DOMAIN + Uuids.canonical(userId)),
                SUBJECT_KEY_VERSION);
    }

    private static byte[] hmac(byte[] secret, String message) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA-256 is not available", e);
        }
    }
}
