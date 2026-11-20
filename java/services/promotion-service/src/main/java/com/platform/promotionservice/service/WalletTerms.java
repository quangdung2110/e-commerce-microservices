package com.platform.promotionservice.service;

import com.platform.promotionservice.domain.rule.ConditionNode;
import com.platform.promotionservice.domain.rule.LogicalNode;
import com.platform.promotionservice.domain.rule.RuleNode;
import java.text.NumberFormat;
import java.util.Collection;
import java.util.Locale;
import java.util.stream.Collectors;

/** Customer-safe description: never disclose audience UUID lists or internal rule fields. */
public final class WalletTerms {
    private WalletTerms() { }

    public static String describe(RuleNode rule) {
        if (rule instanceof LogicalNode group) {
            if (group.children().isEmpty()) return "Điều kiện không đáp ứng được.";
            if (group.children().size() == 1) return describe(group.children().getFirst());
            return ("AND".equals(group.operator()) ? "Tất cả điều kiện: " : "Một trong các điều kiện: ")
                    + group.children().stream().map(child -> "(" + describe(child) + ")")
                    .collect(Collectors.joining("; "));
        }
        var condition = (ConditionNode) rule;
        String operator = switch (condition.operator()) {
            case "EQUAL" -> "bằng";
            case "NOT_EQUAL" -> "khác";
            case "GREATER_THAN" -> "lớn hơn";
            case "GREATER_THAN_OR_EQUAL" -> "từ";
            case "LESS_THAN" -> "nhỏ hơn";
            case "LESS_THAN_OR_EQUAL" -> "tối đa";
            default -> "theo điều kiện của chương trình";
        };
        return switch (condition.field()) {
            case "cart.total_amount" -> condition.value() instanceof Number number
                    ? "Tổng tiền hàng " + operator + " " + NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi-VN")).format(number) + " ₫"
                    : "Có điều kiện về tổng tiền hàng.";
            case "cart.items.category_id" -> ("NOT_IN".equals(condition.operator()) ? "Không có sản phẩm thuộc " : "Có sản phẩm thuộc ")
                    + (condition.value() instanceof Collection<?> values ? values.size() : "các") + " danh mục được chỉ định.";
            case "cart.payment_method" -> "Phương thức thanh toán " + operator + " "
                    + ("STRIPE".equals(condition.value()) ? "Stripe" : "phương thức được chỉ định");
            case "cart.checkout_channel" -> "Áp dụng theo điều kiện kênh đặt hàng của chương trình.";
            case "user.id" -> "Áp dụng theo danh sách tài khoản của chương trình.";
            case "user.tier" -> "Áp dụng theo hạng thành viên của chương trình.";
            default -> "Có điều kiện bổ sung; hệ thống kiểm tra khi thanh toán.";
        };
    }
}
