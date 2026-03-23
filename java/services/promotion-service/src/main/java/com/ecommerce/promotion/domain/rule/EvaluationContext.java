package com.ecommerce.promotion.domain.rule;

import java.util.List;
import java.util.Map;

/**
 * Context chứa dữ liệu thực tế từ Request (Giỏ hàng, User).
 * Theo thiết kế rule_engine_design.md - Section 2.3
 *
 * EvaluationContext làm một bộ Flat Map: khi ConditionNode hỏi xin
 * "cart.total_amount" hoặc "user.tier", Context trả về đúng giá trị.
 *
 * Trong production, có thể mở rộng bằng JsonPath / Spring BeanWrapper.
 */
public class EvaluationContext {

    private final Map<String, Object> data;

    public EvaluationContext(Map<String, Object> data) {
        this.data = data;
    }

    /**
     * Lấy giá trị thực tế theo field path.
     * Theo thiết kế rule_engine_design.md - Section 2.3
     *
     * @param field Đường dẫn field (VD: "cart.total_amount", "user.tier")
     * @return Giá trị thực tế, có thể là Number, String, Boolean, hoặc List
     */
    public Object getValue(String field) {
        return data.get(field);
    }

    /**
     * Kiểm tra field có tồn tại trong context không.
     */
    public boolean hasField(String field) {
        return data.containsKey(field);
    }

    /**
     * Trả về tất cả các field có trong context.
     */
    public java.util.Set<String> getFields() {
        return data.keySet();
    }

    /**
     * Tạo EvaluationContext từ dữ liệu API request.
     * Flatten nested object thành flat map để Rule Engine truy xuất nhanh.
     */
    public static EvaluationContext fromApiRequest(Map<String, Object> cartData, Map<String, Object> userData) {
        Map<String, Object> flatMap = new java.util.HashMap<>();

        // Flatten cart data với prefix "cart."
        if (cartData != null) {
            for (Map.Entry<String, Object> entry : cartData.entrySet()) {
                flatMap.put("cart." + entry.getKey(), entry.getValue());
            }
        }

        // Flatten user data với prefix "user."
        if (userData != null) {
            for (Map.Entry<String, Object> entry : userData.entrySet()) {
                flatMap.put("user." + entry.getKey(), entry.getValue());
            }
        }

        return new EvaluationContext(flatMap);
    }
}
