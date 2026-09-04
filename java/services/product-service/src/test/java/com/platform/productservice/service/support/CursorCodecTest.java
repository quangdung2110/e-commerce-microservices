package com.platform.productservice.service.support;

import com.platform.productservice.common.DomainException;
import com.platform.productservice.common.ErrorCode;
import com.platform.productservice.config.ProductProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cursor pagination; cursor export được ký HMAC và có TTL. */
class CursorCodecTest {

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

        CursorCodec.ExportCursor decoded = codec.decodeExport(codec.encodeExport(last, bound));

        assertThat(decoded.lastId()).isEqualTo(last);
        assertThat(decoded.bound()).isEqualTo(bound);
    }

    @Test
    @DisplayName("Sửa payload làm chữ ký không khớp")
    void rejectsTamperedExportCursor() {
        String cursor = codec.encodeExport(UUID.randomUUID(), UUID.randomUUID());
        String tampered = "A" + cursor.substring(1);

        assertThatThrownBy(() -> codec.decodeExport(tampered))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("signature");
    }

    @Test
    @DisplayName("Ký bằng secret khác không dùng lại được cursor")
    void rejectsCursorSignedWithAnotherSecret() {
        String cursor = codec.encodeExport(UUID.randomUUID(), UUID.randomUUID());

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
        String cursor = codec.encodeExport(UUID.randomUUID(), UUID.randomUUID());
        properties.getExport().setCursorTtl(Duration.ofSeconds(-1));

        assertThatThrownBy(() -> codec.decodeExport(cursor))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("expired");
    }
}
