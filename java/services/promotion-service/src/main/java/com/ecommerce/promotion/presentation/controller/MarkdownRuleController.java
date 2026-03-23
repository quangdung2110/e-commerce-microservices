package com.ecommerce.promotion.presentation.controller;

import com.ecommerce.promotion.application.service.PromotionCommandService;
import com.ecommerce.promotion.application.service.PromotionQueryService;
import com.ecommerce.promotion.domain.entity.MarkdownRule;
import com.ecommerce.promotion.domain.enums.DiscountType;
import com.ecommerce.promotion.domain.enums.OwnerType;
import com.ecommerce.promotion.domain.enums.ScopeType;
import com.ecommerce.promotion.presentation.dto.MarkdownRuleRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller cho Markdown Rule CRUD.
 * Theo thiết kế promotion_workflow.md - Luồng 1 Bước 6 (Zero-Latency CQRS).
 * Theo thiết kế promotion_db_design.md - Section 2.1
 *
 * Markdown (Gạch chéo giá) tác động trực tiếp vào giá hiển thị sản phẩm,
 * khác với Voucher (promotions) áp ở giỏ hàng.
 *
 * Endpoints:
 * - POST   /v1/markdown-rules           → Tạo markdown rule
 * - PUT    /v1/markdown-rules/{id}       → Cập nhật markdown rule
 * - GET    /v1/markdown-rules/{id}       → Lấy markdown rule
 * - GET    /v1/markdown-rules/active     → Danh sách rules đang active
 * - DELETE /v1/markdown-rules/{id}       → Deactivate markdown rule
 */
@RestController
@RequestMapping("/v1/markdown-rules")
public class MarkdownRuleController {

    private static final Logger log = LoggerFactory.getLogger(MarkdownRuleController.class);

    private final PromotionCommandService commandService;
    private final PromotionQueryService queryService;

    public MarkdownRuleController(
            PromotionCommandService commandService,
            PromotionQueryService queryService) {
        this.commandService = commandService;
        this.queryService = queryService;
    }

    /**
     * Tạo Markdown Rule mới.
     * Theo thiết kế promotion_workflow.md - Luồng 1 Bước 6:
     *   Outbox Relay Worker phát Event MARKDOWN_CREATED → Product Service.
     *   Product Service join giá → Search Service → Elasticsearch.
     */
    @PostMapping
    public ResponseEntity<MarkdownRule> createMarkdownRule(
            @Valid @RequestBody MarkdownRuleRequest request) {
        MarkdownRule rule = mapToEntity(request);
        MarkdownRule created = commandService.createMarkdownRule(rule);
        log.info("API: Created markdown rule id={}, scope={}", created.getId(), created.getScopeType());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Cập nhật Markdown Rule.
     * Theo thiết kế promotion_events.md - Section 2.2: MARKDOWN_UPDATED.
     */
    @PutMapping("/{id}")
    public ResponseEntity<MarkdownRule> updateMarkdownRule(
            @PathVariable Long id,
            @Valid @RequestBody MarkdownRuleRequest request) {
        MarkdownRule update = mapToEntity(request);
        MarkdownRule updated = commandService.updateMarkdownRule(id, update);
        log.info("API: Updated markdown rule id={}", id);
        return ResponseEntity.ok(updated);
    }

    @GetMapping("/{id}")
    public ResponseEntity<MarkdownRule> getMarkdownRule(@PathVariable Long id) {
        return ResponseEntity.ok(queryService.getMarkdownRule(id));
    }

    /**
     * Danh sách active markdown rules.
     * Theo thiết kế redis_caching_design.md - Section 2.6: phục vụ Redis Cache.
     */
    @GetMapping("/active")
    public ResponseEntity<List<MarkdownRule>> getActiveMarkdownRules() {
        return ResponseEntity.ok(queryService.getActiveMarkdownRules());
    }

    /**
     * Deactivate markdown rule (Admin tắt thủ công).
     * Theo thiết kế promotion_events.md - Section 2.3: MARKDOWN_ENDED.
     * Sử dụng DELETE method nhưng thực tế là soft-delete (status → INACTIVE).
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivateMarkdownRule(@PathVariable Long id) {
        commandService.deactivateMarkdownRule(id);
        log.info("API: Deactivated markdown rule id={}", id);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // Mapper helper
    // =========================================================================

    private MarkdownRule mapToEntity(MarkdownRuleRequest request) {
        MarkdownRule rule = new MarkdownRule();
        rule.setOwnerType(OwnerType.valueOf(request.getOwnerType()));
        rule.setOwnerId(request.getOwnerId());
        rule.setName(request.getName());
        rule.setScopeType(ScopeType.valueOf(request.getScopeType()));
        rule.setRefId(request.getRefId());
        rule.setDiscountType(DiscountType.valueOf(request.getDiscountType()));
        rule.setDiscountValue(request.getDiscountValue());
        rule.setMaxDiscountAmount(request.getMaxDiscountAmount());
        rule.setStartTime(request.getStartTime());
        rule.setEndTime(request.getEndTime());
        return rule;
    }
}
