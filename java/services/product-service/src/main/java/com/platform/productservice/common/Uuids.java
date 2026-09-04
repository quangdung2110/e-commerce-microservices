package com.platform.productservice.common;

import com.github.f4b6a3.uuid.UuidCreator;

import java.util.UUID;

/**
 * ID catalog là UUIDv7 do application sinh (docs/product/data.md §2).
 *
 * <p>Thứ tự thời gian của UUIDv7 là cơ sở cho keyset export: mọi row được tạo
 * sau khi export bắt đầu đều có ID lớn hơn upper bound đã chụp.
 */
public final class Uuids {

    private Uuids() {
    }

    public static UUID next() {
        return UuidCreator.getTimeOrderedEpoch();
    }
}
