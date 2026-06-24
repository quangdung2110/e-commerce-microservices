package com.platform.inventoryservice.domain.enums;

import java.util.List;

/**
 * Mã trả về từ Lua scripts (Reserve / Release / Deduct).
 * Mỗi script trả về array 2 phần tử {@code {code, reason}}.
 */
public enum LuaReturnCode {

    APPLIED(1, ""),
    IDEMPOTENT_HIT(2, ""),
    OUT_OF_STOCK(0, "OUT_OF_STOCK"),
    INSUFFICIENT_RESERVED(0, "INSUFFICIENT_RESERVED"),
    RELEASE_UNDERFLOW(0, "RELEASE_UNDERFLOW"),
    SKU_SUSPENDED(0, "SKU_SUSPENDED"),
    INVARIANT_DRIFT(-1, "INVARIANT_DRIFT");

    private final int code;
    private final String reason;

    LuaReturnCode(int code, String reason) {
        this.code = code;
        this.reason = reason;
    }

    public int code() {
        return code;
    }

    public String reason() {
        return reason;
    }

    /**
     * Parse kết quả Lua trả về ({@code List<Object>}) thành enum.
     *
     * @throws IllegalArgumentException nếu result không phải array 2 phần tử
     * @throws IllegalStateException nếu cặp (code, reason) không khớp enum nào
     */
    public static LuaReturnCode fromArray(List<Object> result) {
        if (result == null || result.size() != 2) {
            throw new IllegalArgumentException("Invalid Lua return: " + result);
        }
        int c = ((Number) result.get(0)).intValue();
        String r = String.valueOf(result.get(1));
        for (LuaReturnCode v : values()) {
            if (v.code == c && v.reason.equals(r)) {
                return v;
            }
        }
        throw new IllegalStateException("Unknown Lua return: {" + c + ", " + r + "}");
    }

    public boolean isSuccess() {
        return this == APPLIED || this == IDEMPOTENT_HIT;
    }

    public boolean isIdempotent() {
        return this == IDEMPOTENT_HIT;
    }

    public boolean isBusinessReject() {
        return code == 0;
    }

    public boolean isInvariantBreach() {
        return this == INVARIANT_DRIFT;
    }
}