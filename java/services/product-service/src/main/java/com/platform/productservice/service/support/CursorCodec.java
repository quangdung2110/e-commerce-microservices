package com.platform.productservice.service.support;

import com.platform.productservice.common.DomainException;
import com.platform.productservice.common.ErrorCode;
import com.platform.productservice.common.ReplayOffset;
import com.platform.productservice.config.ProductProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Cursor pagination.
 *
 * <p>Cursor admin chỉ mang ID cuối cùng. Cursor export được ký HMAC và có TTL,
 * mang thêm upper bound đã chụp ở trang đầu để export luôn kết thúc dù Product
 * mới được tạo trong lúc Search đang đọc
 * (contracts/http/product/v1/openapi.yaml — ExportCursor).
 *
 * <p>Cursor export còn là nơi lưu chính export session: nó mang luôn
 * {@code replay_from_offsets} đã chụp trước khi scan trang đầu. Nhờ vậy mọi trang
 * của một session — kể cả khi Search retry đúng một trang — trả về cùng một tập
 * offset mà Product không cần session store (ADR-014). Product không có Redis nên
 * cursor đã ký là cơ chế duy nhất giữ session nhất quán.
 */
@Component
@RequiredArgsConstructor
public class CursorCodec {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String FIELD_SEPARATOR = "|";
    private static final String OFFSET_SEPARATOR = ",";
    private static final String OFFSET_PAIR_SEPARATOR = ":";
    private static final int FIELD_COUNT = 5;

    /** Bằng maxLength của parameter ExportCursor trong OpenAPI. */
    private static final int MAX_CURSOR_LENGTH = 1024;

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

    /**
     * Vị trí và ranh giới của một export session.
     *
     * @param offsets replay boundary chụp trước khi scan trang đầu; không đổi
     *                trong suốt session
     */
    public record ExportCursor(UUID lastId, UUID bound, List<ReplayOffset> offsets) {

        public ExportCursor {
            offsets = List.copyOf(offsets);
        }

        public String topic() {
            return offsets.get(0).topic();
        }
    }

    public String encodeExport(UUID lastId, UUID bound, List<ReplayOffset> offsets) {
        if (offsets.isEmpty()) {
            throw new IllegalArgumentException("Export session must carry at least one replay offset");
        }
        String encodedOffsets = offsets.stream()
                .map(offset -> offset.partition() + OFFSET_PAIR_SEPARATOR + offset.offset())
                .collect(Collectors.joining(OFFSET_SEPARATOR));
        String payload = String.join(FIELD_SEPARATOR,
                lastId.toString(),
                bound.toString(),
                Long.toString(Instant.now().getEpochSecond()),
                offsets.get(0).topic(),
                encodedOffsets);
        String encodedPayload = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String cursor = encodedPayload + "." + sign(encodedPayload);
        if (cursor.length() > MAX_CURSOR_LENGTH) {
            // ExportCursor của OpenAPI giới hạn 1024 ký tự. Vượt ngưỡng nghĩa là
            // session không biểu diễn được trọn vẹn, tức không giữ được tính nhất
            // quán mà ADR-014 yêu cầu, nên là 503 chứ không phải trả cursor cụt.
            throw DomainException.of(ErrorCode.CATALOG_UNAVAILABLE,
                    "Export session does not fit in a cursor; reduce partition count or page size");
        }
        return cursor;
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
            parts = new String(DECODER.decode(encodedPayload), StandardCharsets.UTF_8)
                    .split("\\" + FIELD_SEPARATOR, -1);
        } catch (IllegalArgumentException e) {
            throw DomainException.of(ErrorCode.INVALID_CURSOR, "Export cursor is malformed");
        }
        // Cursor phát trước ADR-014 chỉ có 3 phần và không mang offsets: từ chối để
        // Search mở lại session thay vì trả về một boundary không tồn tại.
        if (parts.length != FIELD_COUNT) {
            throw DomainException.of(ErrorCode.INVALID_CURSOR, "Export cursor is malformed");
        }

        Instant issuedAt;
        try {
            issuedAt = Instant.ofEpochSecond(Long.parseLong(parts[2]));
        } catch (NumberFormatException e) {
            throw DomainException.of(ErrorCode.INVALID_CURSOR, "Export cursor is malformed");
        }
        if (issuedAt.plus(properties.getExport().getCursorTtl()).isBefore(Instant.now())) {
            throw DomainException.of(ErrorCode.INVALID_CURSOR,
                    "Export cursor expired; restart the export from the first page");
        }
        try {
            return new ExportCursor(UUID.fromString(parts[0]), UUID.fromString(parts[1]),
                    decodeOffsets(parts[3], parts[4]));
        } catch (IllegalArgumentException e) {
            throw DomainException.of(ErrorCode.INVALID_CURSOR, "Export cursor is malformed");
        }
    }

    private List<ReplayOffset> decodeOffsets(String topic, String encodedOffsets) {
        if (topic.isBlank() || encodedOffsets.isBlank()) {
            throw new IllegalArgumentException("Export cursor carries no replay offset");
        }
        List<ReplayOffset> offsets = new ArrayList<>();
        for (String pair : encodedOffsets.split(OFFSET_SEPARATOR, -1)) {
            int at = pair.indexOf(OFFSET_PAIR_SEPARATOR);
            if (at <= 0) {
                throw new IllegalArgumentException("Replay offset pair is malformed");
            }
            try {
                offsets.add(new ReplayOffset(topic,
                        Integer.parseInt(pair.substring(0, at)),
                        Long.parseLong(pair.substring(at + 1))));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Replay offset pair is not numeric", e);
            }
        }
        return List.copyOf(offsets);
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
