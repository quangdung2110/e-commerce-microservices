package com.ecommerce.promotion.domain.rule;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;

/**
 * Validator cho cây AST Rule Engine.
 * Theo thiết kế rule_engine_design.md - Section 4 (Bảo mật & Validation)
 *
 * Bảo vệ trước các đầu vào bất thường từ Admin UI hoặc API:
 * 4.1: Giới hạn Độ sâu Cây (Max Depth = 10)
 * 4.2: Whitelist các Field hợp lệ
 * 4.3: Giới hạn Kích thước Payload (64 KB)
 */
public class RuleValidator {

    // Theo thiết kế rule_engine_design.md - Section 4.1
    private static final int DEFAULT_MAX_DEPTH = 10;

    // Theo thiết kế rule_engine_design.md - Section 4.3
    private static final int DEFAULT_MAX_PAYLOAD_SIZE_KB = 64;

    // Theo thiết kế rule_engine_design.md - Section 4.2: Whitelist Field hợp lệ
    private static final Set<String> ALLOWED_FIELDS = Set.of(
        "cart.total_amount",
        "cart.items.category_id",
        "cart.items.product_id",
        "cart.items.brand_id",
        "cart.payment_method",
        "cart.checkout_channel",
        "user.tier",
        "user.is_new_user",
        "user.registration_date"
    );

    private final int maxDepth;
    private final int maxPayloadSizeKb;

    public RuleValidator() {
        this(DEFAULT_MAX_DEPTH, DEFAULT_MAX_PAYLOAD_SIZE_KB);
    }

    public RuleValidator(int maxDepth, int maxPayloadSizeKb) {
        this.maxDepth = maxDepth;
        this.maxPayloadSizeKb = maxPayloadSizeKb;
    }

    /**
     * Validate toàn bộ JSON payload trước khi lưu DB.
     * Theo thiết kế rule_engine_design.md - Section 4
     *
     * @param jsonPayload Chuỗi JSON conditions_rule
     * @throws RuleValidationException nếu vi phạm bất kỳ quy tắc nào
     */
    public RuleNode validateAndParse(String jsonPayload) {
        if (jsonPayload == null || jsonPayload.isBlank()) {
            return null; // cho phép promotions không có conditions_rule
        }

        // Theo thiết kế Section 4.3: Giới hạn Kích thước Payload
        if (jsonPayload.getBytes().length > maxPayloadSizeKb * 1024) {
            throw new RuleValidationException(
                "Rule JSON payload exceeds maximum size of " + maxPayloadSizeKb + " KB");
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            RuleNode root = mapper.readValue(jsonPayload, RuleNode.class);

            // Theo thiết kế Section 4.1: Validate Depth bằng DFS
            validateDepth(root, 1);

            // Theo thiết kế Section 4.2: Validate Whitelist Fields
            validateFields(root);

            return root;
        } catch (RuleValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new RuleValidationException("Invalid rule JSON: " + e.getMessage(), e);
        }
    }

    /**
     * DFS validate độ sâu cây.
     * Theo thiết kế rule_engine_design.md - Section 4.1
     */
    private void validateDepth(RuleNode node, int currentDepth) {
        if (currentDepth > maxDepth) {
            throw new RuleValidationException(
                "Rule tree exceeds maximum depth of " + maxDepth + " levels");
        }

        if (node instanceof LogicalNode logicalNode) {
            if (logicalNode.getChildren() != null) {
                for (RuleNode child : logicalNode.getChildren()) {
                    validateDepth(child, currentDepth + 1);
                }
            }
        }
    }

    /**
     * Validate tất cả field trong cây nằm trong whitelist.
     * Theo thiết kế rule_engine_design.md - Section 4.2
     */
    private void validateFields(RuleNode node) {
        if (node instanceof ConditionNode conditionNode) {
            if (!ALLOWED_FIELDS.contains(conditionNode.getField())) {
                throw new RuleValidationException(
                    "Field '" + conditionNode.getField() + "' is not in the allowed whitelist");
            }
        } else if (node instanceof LogicalNode logicalNode) {
            if (logicalNode.getChildren() != null) {
                for (RuleNode child : logicalNode.getChildren()) {
                    validateFields(child);
                }
            }
        }
    }

    // ---- Getter cho allowed fields (immutable) ----
    public static Set<String> getAllowedFields() {
        return ALLOWED_FIELDS;
    }
}
