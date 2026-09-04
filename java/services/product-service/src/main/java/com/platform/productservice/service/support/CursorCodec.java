package com.platform.productservice.service.support;

import com.platform.productservice.common.DomainException;
import com.platform.productservice.common.ErrorCode;
import com.platform.productservice.config.ProductProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Cursor pagination.
 *
 * <p>Cursor admin chỉ mang ID cuối cùng. Cursor export được ký HMAC và có TTL,
 * mang thêm upper bound đã chụp ở trang đầu để export luôn kết thúc dù Product
 * mới được tạo trong lúc Search đang đọc
 * (contracts/http/product/v1/openapi.yaml — ExportCursor).
 */
@Component
@RequiredArgsConstructor
public class CursorCodec {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final ProductProperties properties;

    public String encodeId(UUID lastId) {
        return ENCODER.encodeToString(lastId.toString().getBytes(StandardCharsets.UTF_8));
    }

    public UUID decodeId(String cursor) {
        try {
            return UUID.fromString(new String(DECODER.decode(cursor), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw DomainException.of(ErrorCode.INVALID_CURSOR, "Cursor is not a valid pagination token");
        }
    }

    public record ExportCursor(UUID lastId, UUID bound) {
    }

    public String encodeExport(UUID lastId, UUID bound) {
        String payload = lastId + "|" + bound + "|" + Instant.now().getEpochSecond();
        String encodedPayload = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encodedPayload + "." + sign(encodedPayload);
    }

    public ExportCursor decodeExport(String cursor) {
        int separator = cursor.lastIndexOf('.');
        if (separator <= 0) {
            throw DomainException.of(ErrorCode.INVALID_CURSOR, "Export cursor is malformed");
        }
        String encodedPayload = cursor.substring(0, separator);
        String signature = cursor.substring(separator + 1);
        if (!MessageDigest.isEqual(sign(encodedPayload).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            throw DomainException.of(ErrorCode.INVALID_CURSOR, "Export cursor signature does not match");
        }

        String[] parts;
        try {
            parts = new String(DECODER.decode(encodedPayload), StandardCharsets.UTF_8).split("\\|");
        } catch (IllegalArgumentException e) {
            throw DomainException.of(ErrorCode.INVALID_CURSOR, "Export cursor is malformed");
        }
        if (parts.length != 3) {
            throw DomainException.of(ErrorCode.INVALID_CURSOR, "Export cursor is malformed");
        }

        Instant issuedAt = Instant.ofEpochSecond(Long.parseLong(parts[2]));
        if (issuedAt.plus(properties.getExport().getCursorTtl()).isBefore(Instant.now())) {
            throw DomainException.of(ErrorCode.INVALID_CURSOR,
                    "Export cursor expired; restart the export from the first page");
        }
        try {
            return new ExportCursor(UUID.fromString(parts[0]), UUID.fromString(parts[1]));
        } catch (IllegalArgumentException e) {
            throw DomainException.of(ErrorCode.INVALID_CURSOR, "Export cursor is malformed");
        }
    }

    private String sign(String encodedPayload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(
                    properties.getExport().getCursorSecret().getBytes(StandardCharsets.UTF_8),
                    HMAC_ALGORITHM));
            return ENCODER.encodeToString(mac.doFinal(encodedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot sign export cursor", e);
        }
    }
}
