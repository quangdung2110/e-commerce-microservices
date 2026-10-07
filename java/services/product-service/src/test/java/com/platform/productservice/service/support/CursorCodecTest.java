package com.platform.productservice.service.support;

import com.platform.productservice.common.DomainException;
import com.platform.productservice.common.ErrorCode;
import com.platform.productservice.common.ReplayOffset;
import com.platform.productservice.config.ProductProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cursor pagination; cursor export được ký HMAC, có TTL và mang luôn replay
 * offsets của export session (ADR-014).
 */
class CursorCodecTest {

    private static final String TOPIC = "t.product.events";

    private static final List<ReplayOffset> OFFSETS = List.of(
            new ReplayOffset(TOPIC, 0, 41L),
            new ReplayOffset(TOPIC, 1, 0L),
            new ReplayOffset(TOPIC, 2, 9_007_199_254_740_993L));

    private ProductProperties properties;
    private CursorCodec codec;

    @BeforeEach
    void setUp() {
        properties = new ProductProperties();
        properties.getExport().setCursorSecret("unit-test-secret");
        codec = new CursorCodec(properties);
    }

    @Test
    @DisplayName("Cursor admin round-trip đúng ID")
    void encodesAndDecodesPlainCursor() {
        UUID id = UUID.randomUUID();

        assertThat(codec.decodeId(codec.encodeId(id))).isEqualTo(id);
    }

    @Test
    @DisplayName("Cursor admin hỏng trả INVALID_CURSOR")
    void rejectsMalformedPlainCursor() {
        assertThatThrownBy(() -> codec.decodeId("not-a-cursor"))
                .isInstanceOf(DomainException.class)
                .extracting(exception -> ((DomainException) exception).errorCode())
                .isEqualTo(ErrorCode.INVALID_CURSOR);
    }

    @Test
    @DisplayName("Cursor export giữ nguyên last ID và upper bound")
    void exportCursorKeepsPositionAndBound() {
        UUID last = UUID.randomUUID();
        UUID bound = UUID.randomUUID();

        CursorCodec.ExportCursor decoded = codec.decodeExport(codec.encodeExport(last, bound, OFFSETS));

        assertThat(decoded.lastId()).isEqualTo(last);
        assertThat(decoded.bound()).isEqualTo(bound);
    }

    @Test
    @DisplayName("Cursor export giữ nguyên replay offsets của mọi partition")
    void exportCursorKeepsReplayOffsets() {
        String cursor = codec.encodeExport(UUID.randomUUID(), UUID.randomUUID(), OFFSETS);

        CursorCodec.ExportCursor decoded = codec.decodeExport(cursor);

        assertThat(decoded.offsets()).containsExactlyElementsOf(OFFSETS);
        assertThat(decoded.topic()).isEqualTo(TOPIC);
    }

    @Test
    @DisplayName("Session không có offset nào là lỗi lập trình, không phải cursor hỏng")
    void refusesToEncodeSessionWithoutOffsets() {
        assertThatThrownBy(() -> codec.encodeExport(UUID.randomUUID(), UUID.randomUUID(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Cursor phát trước ADR-014 không mang offsets nên bị từ chối")
    void rejectsCursorWithoutOffsetSection() {
        String legacyPayload = UUID.randomUUID() + "|" + UUID.randomUUID()
                + "|" + Instant.now().getEpochSecond();
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(legacyPayload.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> codec.decodeExport(signedBy(encoded)))
                .isInstanceOf(DomainException.class)
                .extracting(exception -> ((DomainException) exception).errorCode())
                .isEqualTo(ErrorCode.INVALID_CURSOR);
    }

    @Test
    @DisplayName("Offsets sai định dạng trả INVALID_CURSOR chứ không phải 500")
    void rejectsMalformedOffsetSection() {
        String payload = UUID.randomUUID() + "|" + UUID.randomUUID()
                + "|" + Instant.now().getEpochSecond() + "|" + TOPIC + "|0:notanoffset";
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> codec.decodeExport(signedBy(encoded)))
                .isInstanceOf(DomainException.class)
                .extracting(exception -> ((DomainException) exception).errorCode())
                .isEqualTo(ErrorCode.INVALID_CURSOR);
    }

    /**
     * Ký một payload tự dựng bằng đúng thuật toán và secret của codec, để test
     * nhánh decode chứ không phải nhánh kiểm tra chữ ký.
     */
    private String signedBy(String encodedPayload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    properties.getExport().getCursorSecret().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            String signature = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(encodedPayload.getBytes(StandardCharsets.UTF_8)));
            return encodedPayload + "." + signature;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("Sửa payload làm chữ ký không khớp")
    void rejectsTamperedExportCursor() {
        String cursor = codec.encodeExport(UUID.randomUUID(), UUID.randomUUID(), OFFSETS);
        String tampered = "A" + cursor.substring(1);

        assertThatThrownBy(() -> codec.decodeExport(tampered))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("signature");
    }

    @Test
    @DisplayName("Ký bằng secret khác không dùng lại được cursor")
    void rejectsCursorSignedWithAnotherSecret() {
        String cursor = codec.encodeExport(UUID.randomUUID(), UUID.randomUUID(), OFFSETS);

        ProductProperties otherProperties = new ProductProperties();
        otherProperties.getExport().setCursorSecret("another-secret");
        CursorCodec otherCodec = new CursorCodec(otherProperties);

        assertThatThrownBy(() -> otherCodec.decodeExport(cursor))
                .isInstanceOf(DomainException.class)
                .extracting(exception -> ((DomainException) exception).errorCode())
                .isEqualTo(ErrorCode.INVALID_CURSOR);
    }

    @Test
    @DisplayName("Cursor quá TTL bị từ chối")
    void rejectsExpiredExportCursor() {
        String cursor = codec.encodeExport(UUID.randomUUID(), UUID.randomUUID(), OFFSETS);
        properties.getExport().setCursorTtl(Duration.ofSeconds(-1));

        assertThatThrownBy(() -> codec.decodeExport(cursor))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("expired");
    }
}
