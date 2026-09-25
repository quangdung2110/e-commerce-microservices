package com.ecommerce.pdp.client;

import com.fasterxml.jackson.databind.JsonNode;

/** Giá trị thuộc tính là scalar JSON: string, number hoặc boolean. */
final class ScalarValues {

    private ScalarValues() {
    }

    static boolean isScalar(JsonNode value) {
        return value != null && (value.isTextual() || value.isNumber() || value.isBoolean());
    }

    /**
     * Khóa so sánh giữa giá trị SKU và option: {@code 128} và {@code 128.0} là cùng một số,
     * còn chuỗi {@code "128"} khác số {@code 128}.
     */
    static String key(JsonNode value) {
        if (value.isNumber()) {
            return "n:" + value.decimalValue().stripTrailingZeros().toPlainString();
        }
        if (value.isBoolean()) {
            return "b:" + value.booleanValue();
        }
        return "s:" + value.asText();
    }
}
