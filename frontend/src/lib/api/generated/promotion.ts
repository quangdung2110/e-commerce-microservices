/**
 * FILE SINH TỰ ĐỘNG — KHÔNG SỬA TAY.
 * Nguồn: contracts/http/promotion/v1/openapi.yaml
 * Sinh lại bằng `scripts/node.sh npm run gen:api`.
 */
export interface paths {
    "/api/v1/admin/promotion-campaigns": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Liệt kê campaign cho quản trị */
        get: operations["listPromotionCampaigns"];
        put?: never;
        /**
         * Tạo campaign DRAFT
         * @description Tạo campaign mới với interval nửa mở [start_time,end_time).
         */
        post: operations["createPromotionCampaign"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/promotion-campaigns/{campaign_id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Lấy campaign quản trị */
        get: operations["getPromotionCampaign"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        /**
         * Cập nhật metadata hoặc lịch của campaign DRAFT
         * @description Chỉ DRAFT được sửa. If-Match chứa revision hiện hành; mutation thành công tăng campaign revision đúng một lần. Published definition phải được clone, không sửa.
         */
        patch: operations["updateDraftPromotionCampaign"];
        trace?: never;
    };
    "/api/v1/admin/promotion-campaigns/{campaign_id}/copies": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Clone một campaign thành aggregate DRAFT mới
         * @description Source không bị mutate. Server tạo campaign, promotion và markdown ID mới; request phải cung cấp code mới cho campaign và mọi promotion được clone.
         */
        post: operations["clonePromotionCampaign"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/promotion-campaigns/{campaign_id}/transitions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Publish, pause hoặc cancel campaign
         * @description Transition hợp lệ: DRAFT->PUBLISHED, PUBLISHED->PAUSED|CANCELLED và PAUSED->CANCELLED. PUBLISHED business definition là immutable. Transition tăng revision; markdown effective-state event được ghi transactional outbox khi cần.
         */
        post: operations["transitionPromotionCampaign"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/promotion-campaigns/{campaign_id}/promotions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Liệt kê voucher definition trong campaign */
        get: operations["listCampaignPromotions"];
        put?: never;
        /** Tạo voucher definition và budget trong campaign DRAFT */
        post: operations["createCampaignPromotion"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/promotions/{promotion_id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Lấy một voucher definition và budget */
        get: operations["getAdminPromotion"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        /**
         * Cập nhật voucher definition trong campaign DRAFT
         * @description If-Match là revision của parent campaign, không phải promotion revision.
         */
        patch: operations["updateDraftPromotion"];
        trace?: never;
    };
    "/api/v1/admin/promotions/{promotion_id}/grants": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Cấp voucher DIRECT_GRANT cho một user
         * @description Unique (user_id,promotion_id). Replay cùng key/payload trả cùng wallet; promotion khác DIRECT_GRANT hoặc wallet đã USED trả conflict.
         */
        post: operations["grantPromotionVoucher"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/promotion-campaigns/{campaign_id}/markdown-rules": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Liệt kê markdown rule trong campaign */
        get: operations["listCampaignMarkdownRules"];
        put?: never;
        /**
         * Tạo markdown rule product-scope trong campaign DRAFT
         * @description Product được validate đồng bộ và tombstone watermark được kiểm trong transaction.
         */
        post: operations["createCampaignMarkdownRule"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/markdown-rules/{markdown_rule_id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Lấy một markdown rule */
        get: operations["getAdminMarkdownRule"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        /**
         * Cập nhật markdown rule trong campaign DRAFT
         * @description If-Match là revision của parent campaign.
         */
        patch: operations["updateDraftMarkdownRule"];
        trace?: never;
    };
    "/api/v1/me/promotion-voucher-suggestions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Đánh giá voucher SAVED trong wallet theo checkout context
         * @description Dùng POST vì context có danh sách item/category và không phù hợp query string. Đây là safe read operation; ngoài lazy-normalize SAVED đã hết hạn thành EXPIRED, không reserve budget. Response cá nhân không được cache dùng chung.
         */
        post: operations["suggestMyPromotionVouchers"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/promotion-discovery": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Tìm voucher wallet và public voucher phù hợp checkout context
         * @description Read-only ngoài lazy expiry; không auto-save và không reserve. Gateway áp profile promotion-discovery: 20 request/phút/authenticated user. Budget timeout được phép fail-open nhưng từng item phải đánh dấu budget_guard=DEGRADED.
         */
        post: operations["discoverPlatformPromotions"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/me/promotion-vouchers/{promotion_id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Lưu public voucher, evaluate khi có checkout context
         * @description Chỉ PUBLIC_SAVE thuộc campaign ACTIVE. Existing SAVED còn hạn trả 200 và không kéo dài expired_at; tạo mới trả 201; USED hoặc expired ownership trả 409. Identity user lấy từ token, không nhận user_id trong payload. context là tùy chọn: thiếu context thì chỉ lưu wallet và evaluation là null. Trang checkout gửi body rỗng vì giá và category tin cậy chỉ có ở Checkout; Checkout Preview tính discount chính thức (Promotion ADR-018). Body là tùy chọn: không có body và `{}` đều lưu wallet và trả evaluation null.
         */
        put: operations["saveAndEvaluatePromotionVoucher"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/me/promotion-voucher-claims": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Claim một limited voucher
         * @description Lua dùng Redis TIME, enabled gate và expected campaign revision trước SPOP. API chỉ trả 201 sau khi claim + wallet commit PostgreSQL. Nếu đã acquire nhưng chưa xác nhận DB trước deadline, trả 202 CLAIM_PENDING để client retry cùng user/promotion/key.
         */
        post: operations["claimLimitedPromotionVoucher"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/internal/v1/promotion-discount-calculations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Tính discount cho 0 hoặc 1 platform voucher
         * @description Pure/replay-safe, không save và không reserve. Discount là một amount toàn đơn, không trả allocation theo SKU. Nếu promotion_id null, kết quả discount là 0. Context là TrustedCheckoutContext: `checkout_channel` khác `CHECKOUT` là `400 INVALID_REQUEST` (`errors[].field = context.checkout_channel`).
         */
        post: operations["calculatePromotionDiscount"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/internal/v1/promotion-voucher-options": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Xếp hạng voucher wallet và public cho trusted checkout context
         * @description Chỉ checkout-service gọi để dựng khay voucher của trang checkout (Checkout ADR-017, Promotion ADR-018). Cùng logic với suggestion/discovery nhưng context do Checkout dựng từ snapshot server-owned, không từ browser. Safe query ngoài lazy-normalize SAVED hết hạn thành EXPIRED; không save, không claim, không reserve. Ranking: eligible trước, discount giảm dần, promotion_id tăng dần. Mỗi item kèm thông tin hiển thị của voucher. Rate limit thuộc Gateway profile checkout-voucher-options ở route Checkout; Promotion không giữ local limiter. Context là TrustedCheckoutContext: `checkout_channel` khác `CHECKOUT` là `400 INVALID_REQUEST`.
         */
        post: operations["listPromotionVoucherOptions"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/internal/v1/promotion-allocation/reservations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * TRY giữ budget cho voucher sau Submit
         * @description Chỉ Order/Temporal gọi sau khi đã có order_id. Revalidate wallet SAVED, expiry, rules và expected amount; Redis Lua giữ quota/amount trong 15 phút. Cùng stable order/promotion và payload là replay; payload khác trả IDEMPOTENCY_PAYLOAD_MISMATCH. Context là TrustedCheckoutContext: `checkout_channel` khác `CHECKOUT` là `400 INVALID_REQUEST` (`errors[].field = context.checkout_channel`). Promotion không tồn tại trả `404 PROMOTION_NOT_FOUND`.
         */
        post: operations["reservePromotionAllocation"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/internal/v1/promotion-allocation/confirmations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * CONFIRM voucher reservation
         * @description Idempotency-Key chuẩn là REDEEM_FINAL:{order_id}:{promotion_id}. Server lookup durable receipt trước Redis, fence RESERVED->CONFIRMING, rồi commit receipt, wallet và durable budget trong một PostgreSQL transaction; final command không ghi outbox (ADR-016). Redis finalize chỉ chạy sau commit và receipt redis_finalized_at IS NULL là durable trigger để chạy lại sau crash. expected_discount không phải amount authority. Hai request cùng key đến đồng thời đều nhận cùng stored response (một transition duy nhất). Reservation tạo lúc voucher còn hạn vẫn được redeem dù wallet đã bị lazy-normalize thành EXPIRED trong lúc Payment chạy (Promotion ADR-019).
         */
        post: operations["confirmPromotionAllocation"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/internal/v1/promotion-allocation/cancellations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * CANCEL reservation trước payment confirmation
         * @description Chỉ release reservation ở state RESERVED; wallet vẫn SAVED. CONFIRMING không được release mù. Missing payload trả outcome NO_RESERVATION hoặc REPAIR_REQUIRED rõ ràng.
         */
        post: operations["cancelPromotionReservation"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/internal/v1/promotion-allocation/technical-cancellations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Legacy-only technical cancellation of a confirmed allocation
         * @deprecated
         * @description Không phải customer refund và không phải post-payment Saga compensation. Only the `promotion-legacy-migrator` mTLS principal may invoke it, and every request must carry an immutable migration-record proof that the historical flow never crossed the Payment financial watermark. `order-service`, active checkout, late-payment recovery and every order with PAYMENT_SUCCESS/ALREADY_SUCCEEDED are denied. New failures after payment use forward repair/financial reconciliation, never make wallet/budget look unredeemed. Promotion enforces this itself: any caller outside the migrator is `403 FORBIDDEN`; a missing `X-Legacy-Migration-Proof`, a value that is not a UUID or one different from the body's `migration_record_id` is also `403 FORBIDDEN` and nothing is read or written. A body that breaks the schema (missing field, digest that is not 64 hex characters, unknown field) is `400`. Both proof fields are part of the request hash and stored on the receipt: replaying the key with another record or digest is `409 IDEMPOTENCY_PAYLOAD_MISMATCH`. Idempotency-Key chuẩn là CANCEL_FINAL:{order_id}:{promotion_id}. PostgreSQL commit receipt, wallet và durable budget trước (không ghi outbox, ADR-016); Redis chỉ được repair sau commit. Wallet trở lại SAVED nếu chưa hết hạn, ngược lại EXPIRED. Hai request cùng key đến đồng thời đều nhận cùng stored response.
         */
        post: operations["cancelConfirmedPromotionAllocation"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        Money: components["schemas"]["money.schema"];
        VndMoney: components["schemas"]["money.schema"] & {
            /** Format: int64 */
            amount_minor?: number;
            /** @constant */
            currency?: "VND";
        };
        PositiveVndMoney: components["schemas"]["money.schema"] & {
            /** Format: int64 */
            amount_minor?: number;
            /** @constant */
            currency?: "VND";
        };
        NullableVndMoney: components["schemas"]["VndMoney"] | null;
        NullablePositiveVndMoney: components["schemas"]["PositiveVndMoney"] | null;
        Problem: components["schemas"]["problem.schema"];
        /** @description Opaque identifier trên wire; consumer không được suy luận kiểu storage. */
        OpaqueId: string;
        NullableOpaqueId: string | null;
        /** Format: uuid */
        Uuid: string;
        /** @enum {string} */
        CampaignPublicationStatus: "UNKNOWN" | "DRAFT" | "PUBLISHED" | "PAUSED" | "CANCELLED";
        /** @enum {string} */
        CampaignPhase: "UNKNOWN" | "DRAFT" | "UPCOMING" | "ACTIVE" | "ENDED" | "PAUSED" | "CANCELLED";
        /**
         * @description UNKNOWN dành cho forward-compatible reader; mutation gửi UNKNOWN bị 422.
         * @enum {string}
         */
        DistributionMode: "UNKNOWN" | "PUBLIC_SAVE" | "LIMITED_CLAIM" | "DIRECT_GRANT";
        /** @enum {string} */
        WalletStatus: "UNKNOWN" | "SAVED" | "USED" | "EXPIRED";
        /** @enum {string} */
        EligibilityReason: "UNKNOWN" | "ELIGIBLE" | "NO_VOUCHER_SELECTED" | "CONDITIONS_NOT_MET" | "MIN_ORDER_NOT_MET" | "VOUCHER_NOT_OWNED" | "VOUCHER_EXPIRED" | "ALREADY_USED_VOUCHER" | "CAMPAIGN_NOT_ACTIVE" | "PROMOTION_DISABLED" | "BUDGET_EXHAUSTED";
        /** @enum {string} */
        BudgetGuard: "UNKNOWN" | "AVAILABLE" | "EXHAUSTED" | "DEGRADED" | "NOT_CHECKED";
        Discount: components["schemas"]["PercentageDiscount"] | components["schemas"]["FixedDiscount"];
        PercentageDiscount: {
            /**
             * @description discriminator enum property added by openapi-typescript
             * @enum {string}
             */
            type: "PERCENTAGE";
            value_basis_points: number;
            max_discount?: components["schemas"]["NullableVndMoney"];
        };
        FixedDiscount: {
            /**
             * @description discriminator enum property added by openapi-typescript
             * @enum {string}
             */
            type: "FIXED_VND";
            value: components["schemas"]["PositiveVndMoney"];
        };
        ValidityPolicy: components["schemas"]["CampaignEndValidity"] | components["schemas"]["DurationValidity"] | components["schemas"]["FixedDateValidity"];
        CampaignEndValidity: {
            /**
             * @description discriminator enum property added by openapi-typescript
             * @enum {string}
             */
            mode: "CAMPAIGN_END";
        };
        DurationValidity: {
            /**
             * @description discriminator enum property added by openapi-typescript
             * @enum {string}
             */
            mode: "DURATION_AFTER_SAVE";
            /** Format: int64 */
            valid_for_seconds: number;
        };
        FixedDateValidity: {
            /**
             * @description discriminator enum property added by openapi-typescript
             * @enum {string}
             */
            mode: "FIXED_DATE";
            /** Format: date-time */
            redeem_until: string;
        };
        RuleValue: string | number | boolean | (string | number | boolean)[];
        RuleNode: components["schemas"]["LogicalRuleNode"] | components["schemas"]["ConditionRuleNode"];
        LogicalRuleNode: {
            /**
             * @description discriminator enum property added by openapi-typescript
             * @enum {string}
             */
            node_type: "LOGICAL";
            /** @enum {string} */
            operator: "AND" | "OR";
            children: components["schemas"]["RuleNode"][];
        };
        ConditionRuleNode: {
            /**
             * @description discriminator enum property added by openapi-typescript
             * @enum {string}
             */
            node_type: "CONDITION";
            /** @enum {string} */
            operator: "EQUAL" | "NOT_EQUAL" | "IN" | "NOT_IN" | "GREATER_THAN" | "GREATER_THAN_OR_EQUAL" | "LESS_THAN" | "LESS_THAN_OR_EQUAL";
            /** @description Phải thuộc allowlist field của rule_schema_version. */
            field: string;
            value: components["schemas"]["RuleValue"];
        };
        BudgetDefinition: {
            /** Format: int64 */
            total_claim_quantity: number | null;
            /** Format: int64 */
            total_redemption_quantity: number | null;
            total_budget: components["schemas"]["NullablePositiveVndMoney"];
        };
        Budget: {
            /** Format: int64 */
            total_claim_quantity: number | null;
            /** Format: int64 */
            claimed_quantity: number;
            /** Format: int64 */
            total_redemption_quantity: number | null;
            /** Format: int64 */
            used_quantity: number;
            total_budget: components["schemas"]["NullablePositiveVndMoney"];
            used_budget: components["schemas"]["VndMoney"];
            /** Format: date-time */
            updated_at: string;
        };
        CreateCampaignRequest: {
            code: string;
            name: string;
            description?: string | null;
            /** Format: date-time */
            start_time: string;
            /** Format: date-time */
            end_time: string;
        } & {
            [key: string]: unknown;
        };
        UpdateCampaignRequest: {
            name?: string;
            description?: string | null;
            /** Format: date-time */
            start_time?: string;
            /** Format: date-time */
            end_time?: string;
        } & {
            [key: string]: unknown;
        };
        Campaign: {
            id: components["schemas"]["OpaqueId"];
            code: string;
            name: string;
            description?: string | null;
            publication_status: components["schemas"]["CampaignPublicationStatus"];
            phase: components["schemas"]["CampaignPhase"];
            /** Format: date-time */
            start_time: string;
            /** Format: date-time */
            end_time: string;
            /** Format: int64 */
            revision: number;
            created_by: string;
            updated_by: string;
            /** Format: date-time */
            created_at: string;
            /** Format: date-time */
            updated_at: string;
        };
        CampaignPage: {
            items: components["schemas"]["Campaign"][];
            next_cursor?: string | null;
        };
        PromotionCodeOverride: {
            source_promotion_id: components["schemas"]["OpaqueId"];
            new_code: string;
        };
        CloneCampaignRequest: components["schemas"]["CreateCampaignRequest"] & ({
            /** @description Phải cover đúng mọi promotion thuộc source campaign. */
            promotion_code_overrides: components["schemas"]["PromotionCodeOverride"][];
        } & {
            [key: string]: unknown;
        });
        CampaignTransitionRequest: {
            /** @enum {string} */
            target_status: "PUBLISHED" | "PAUSED" | "CANCELLED";
            reason: string;
        } & {
            [key: string]: unknown;
        };
        CreatePromotionRequest: ({
            code: string;
            name: string;
            description?: string | null;
            distribution_mode: components["schemas"]["DistributionMode"];
            discount: components["schemas"]["Discount"];
            min_order_amount: components["schemas"]["VndMoney"];
            conditions_rule: components["schemas"]["RuleNode"];
            /** @constant */
            rule_schema_version: 1;
            validity: components["schemas"]["ValidityPolicy"];
            is_enabled: boolean;
            budget: components["schemas"]["BudgetDefinition"];
        } & {
            [key: string]: unknown;
        }) & unknown;
        UpdatePromotionRequest: {
            name?: string;
            description?: string | null;
            distribution_mode?: components["schemas"]["DistributionMode"];
            discount?: components["schemas"]["Discount"];
            min_order_amount?: components["schemas"]["VndMoney"];
            conditions_rule?: components["schemas"]["RuleNode"];
            /** @constant */
            rule_schema_version?: 1;
            validity?: components["schemas"]["ValidityPolicy"];
            is_enabled?: boolean;
            budget?: components["schemas"]["BudgetDefinition"];
        } & {
            [key: string]: unknown;
        };
        Promotion: {
            id: components["schemas"]["OpaqueId"];
            campaign_id: components["schemas"]["OpaqueId"];
            /** Format: int64 */
            campaign_revision: number;
            code: string;
            name: string;
            description?: string | null;
            distribution_mode: components["schemas"]["DistributionMode"];
            discount: components["schemas"]["Discount"];
            min_order_amount: components["schemas"]["VndMoney"];
            conditions_rule: components["schemas"]["RuleNode"];
            rule_schema_version: number;
            validity: components["schemas"]["ValidityPolicy"];
            is_enabled: boolean;
            budget: components["schemas"]["Budget"];
            /** Format: date-time */
            created_at: string;
            /** Format: date-time */
            updated_at: string;
        };
        PromotionPage: {
            items: components["schemas"]["Promotion"][];
            next_cursor?: string | null;
        };
        CreateMarkdownRuleRequest: {
            product_id: components["schemas"]["Uuid"];
            discount: components["schemas"]["Discount"];
            priority: number;
            is_enabled: boolean;
        } & {
            [key: string]: unknown;
        };
        UpdateMarkdownRuleRequest: {
            discount?: components["schemas"]["Discount"];
            priority?: number;
            is_enabled?: boolean;
        } & {
            [key: string]: unknown;
        };
        MarkdownRule: {
            id: components["schemas"]["OpaqueId"];
            campaign_id: components["schemas"]["OpaqueId"];
            /** Format: int64 */
            campaign_revision: number;
            product_id: components["schemas"]["Uuid"];
            discount: components["schemas"]["Discount"];
            priority: number;
            is_enabled: boolean;
            /** Format: date-time */
            invalidated_at?: string | null;
            /** @enum {string|null} */
            invalidation_reason?: "UNKNOWN" | "PRODUCT_DELETED" | null;
            /** Format: int64 */
            source_product_revision?: number | null;
            /** Format: date-time */
            created_at: string;
            /** Format: date-time */
            updated_at: string;
        };
        MarkdownRulePage: {
            items: components["schemas"]["MarkdownRule"][];
            next_cursor?: string | null;
        };
        GrantVoucherRequest: {
            user_id: components["schemas"]["Uuid"];
            reason: string;
        } & {
            [key: string]: unknown;
        };
        CheckoutItem: {
            product_id: components["schemas"]["Uuid"];
            sku_id: string;
            quantity: number;
            unit_price: components["schemas"]["VndMoney"];
            category_ids: string[];
        } & {
            [key: string]: unknown;
        };
        /** @description Map sang các rule fact cart.total_amount, cart.payment_method, cart.checkout_channel và cart.items.category_id. Internal Checkout requests use TrustedCheckoutContext to constrain checkout_channel=CHECKOUT. user.id lấy từ trusted identity; user.tier chỉ được dùng khi caller có trusted service snapshot. Thiếu tier làm rule tier evaluate false, không được lấy từ context.facts. */
        CheckoutContext: {
            subtotal: components["schemas"]["VndMoney"];
            /**
             * @description Phase 18 chỉ hỗ trợ Stripe; server mặc định STRIPE và không tin payment method tùy ý từ client.
             * @default STRIPE
             * @constant
             */
            payment_method: "STRIPE";
            checkout_channel: string;
            items: components["schemas"]["CheckoutItem"][];
            /** @description Optional versioned facts; server chỉ expose allowlisted field cho Rule Engine. */
            facts?: {
                [key: string]: components["schemas"]["RuleValue"];
            };
        } & {
            [key: string]: unknown;
        };
        TrustedCheckoutContext: components["schemas"]["CheckoutContext"] & {
            /** @constant */
            checkout_channel?: "CHECKOUT";
        };
        SuggestionRequest: {
            context: components["schemas"]["CheckoutContext"];
            /** @default 20 */
            limit: number;
        } & {
            [key: string]: unknown;
        };
        DiscoveryRequest: components["schemas"]["SuggestionRequest"] & ({
            /** @default false */
            include_ineligible: boolean;
        } & {
            [key: string]: unknown;
        });
        SaveAndEvaluateRequest: {
            /** @description Optional (Promotion ADR-018); omitted means save only and evaluation=null. */
            context?: components["schemas"]["CheckoutContext"];
        } & {
            [key: string]: unknown;
        };
        WalletVoucher: {
            wallet_id: components["schemas"]["OpaqueId"];
            user_id: components["schemas"]["Uuid"];
            promotion_id: components["schemas"]["OpaqueId"];
            claim_id?: components["schemas"]["NullableOpaqueId"];
            status: components["schemas"]["WalletStatus"];
            /** Format: date-time */
            saved_at: string;
            /** Format: date-time */
            expired_at: string;
            used_order_id?: components["schemas"]["NullableOpaqueId"];
            discount_amount?: components["schemas"]["NullableVndMoney"];
            /** Format: date-time */
            used_at?: string | null;
            /** Format: date-time */
            updated_at: string;
        };
        VoucherEvaluation: {
            promotion_id: components["schemas"]["OpaqueId"];
            wallet_id?: components["schemas"]["NullableOpaqueId"];
            /** @enum {string} */
            source: "UNKNOWN" | "WALLET" | "PUBLIC";
            /** @description Cho client biết PUBLIC item cần PUT /me/promotion-vouchers (PUBLIC_SAVE) hay POST /me/promotion-voucher-claims với campaign_revision làm expected revision (LIMITED_CLAIM). */
            distribution_mode: components["schemas"]["DistributionMode"];
            eligible: boolean;
            reason: components["schemas"]["EligibilityReason"];
            discount: components["schemas"]["VndMoney"];
            final_amount: components["schemas"]["VndMoney"];
            rule_schema_version: number;
            /** Format: int64 */
            campaign_revision: number;
            budget_guard: components["schemas"]["BudgetGuard"];
        };
        VoucherSuggestionResponse: {
            items: components["schemas"]["VoucherEvaluation"][];
            /** Format: date-time */
            evaluated_at: string;
        };
        PromotionDiscoveryResponse: components["schemas"]["VoucherSuggestionResponse"];
        VoucherOptionRequest: {
            user_id: components["schemas"]["Uuid"];
            /** @description Optional trusted snapshot; Checkout v1 omits it so user.tier rules evaluate false. */
            user_tier?: string | null;
            context: components["schemas"]["TrustedCheckoutContext"];
            /** @default true */
            include_public: boolean;
            /** @default true */
            include_ineligible: boolean;
            /** @default 50 */
            limit: number;
        } & {
            [key: string]: unknown;
        };
        VoucherDisplay: {
            code: string;
            name: string;
            description: string | null;
            discount: components["schemas"]["Discount"];
            min_order_amount: components["schemas"]["VndMoney"];
            /**
             * Format: date-time
             * @description wallet.expired_at cho WALLET; thời điểm hết hạn theo validity policy hoặc campaign end_time cho PUBLIC.
             */
            valid_until: string | null;
        };
        VoucherOption: {
            evaluation: components["schemas"]["VoucherEvaluation"];
            display: components["schemas"]["VoucherDisplay"];
        };
        VoucherOptionList: {
            items: components["schemas"]["VoucherOption"][];
            /** Format: date-time */
            evaluated_at: string;
        };
        SaveAndEvaluateResponse: {
            wallet: components["schemas"]["WalletVoucher"];
            /** @description Null when the request carried no context (Promotion ADR-018). */
            evaluation: components["schemas"]["VoucherEvaluation"] | null;
        };
        ClaimVoucherRequest: {
            promotion_id: components["schemas"]["OpaqueId"];
            /** Format: int64 */
            expected_campaign_revision: number;
        } & {
            [key: string]: unknown;
        };
        ClaimVoucherResponse: {
            claim_id: components["schemas"]["OpaqueId"];
            /** Format: date-time */
            acquired_at: string;
            /** Format: int64 */
            campaign_revision_at_acquisition: number;
            wallet: components["schemas"]["WalletVoucher"];
        };
        ClaimPendingResponse: {
            /** @constant */
            code: "CLAIM_PENDING";
            /** @constant */
            status: "PENDING";
            promotion_id: components["schemas"]["OpaqueId"];
            claim_id?: components["schemas"]["NullableOpaqueId"];
            /** @constant */
            retryable: true;
        };
        DiscountCalculationRequest: {
            user_id: components["schemas"]["Uuid"];
            /** @description Optional trusted snapshot; omit/null makes user.tier rules evaluate false. */
            user_tier?: string | null;
            promotion_id: components["schemas"]["NullableOpaqueId"];
            context: components["schemas"]["TrustedCheckoutContext"];
        } & {
            [key: string]: unknown;
        };
        DiscountCalculationResult: {
            promotion_id?: components["schemas"]["NullableOpaqueId"];
            eligible: boolean;
            reason: components["schemas"]["EligibilityReason"];
            subtotal: components["schemas"]["VndMoney"];
            discount: components["schemas"]["VndMoney"];
            final_amount: components["schemas"]["VndMoney"];
            rule_schema_version?: number | null;
            /** Format: int64 */
            campaign_revision?: number | null;
            budget_guard: components["schemas"]["BudgetGuard"];
            /** Format: date-time */
            evaluated_at: string;
        };
        ReserveRequest: {
            order_id: components["schemas"]["OpaqueId"];
            user_id: components["schemas"]["Uuid"];
            /** @description Must match the optional trusted tier used at Preview; omit/null when no tier authority exists. */
            user_tier?: string | null;
            promotion_id: components["schemas"]["OpaqueId"];
            expected_discount: components["schemas"]["VndMoney"];
            context: components["schemas"]["TrustedCheckoutContext"];
        } & {
            [key: string]: unknown;
        };
        ReservationResponse: {
            order_id: components["schemas"]["OpaqueId"];
            promotion_id: components["schemas"]["OpaqueId"];
            /** @constant */
            state: "RESERVED";
            discount: components["schemas"]["VndMoney"];
            /** Format: date-time */
            reserved_at: string;
            /** Format: date-time */
            expires_at: string;
        };
        FinalAllocationRequest: {
            order_id: components["schemas"]["OpaqueId"];
            promotion_id: components["schemas"]["OpaqueId"];
            expected_discount: components["schemas"]["VndMoney"];
        } & {
            [key: string]: unknown;
        };
        LegacyTechnicalCancellationRequest: {
            order_id: components["schemas"]["OpaqueId"];
            promotion_id: components["schemas"]["OpaqueId"];
            expected_discount: components["schemas"]["VndMoney"];
            /** Format: uuid */
            migration_record_id: string;
            pre_financial_watermark_evidence_sha256: string;
        };
        ConfirmationResponse: {
            /** @constant */
            operation: "CONFIRM";
            order_id: components["schemas"]["OpaqueId"];
            promotion_id: components["schemas"]["OpaqueId"];
            discount: components["schemas"]["VndMoney"];
            /** @constant */
            wallet_status: "USED";
            /** Format: date-time */
            completed_at: string;
        };
        TechnicalCancellationResponse: {
            /** @constant */
            operation: "CANCEL_CONFIRMED";
            order_id: components["schemas"]["OpaqueId"];
            promotion_id: components["schemas"]["OpaqueId"];
            discount: components["schemas"]["VndMoney"];
            /** @enum {string} */
            wallet_status: "UNKNOWN" | "SAVED" | "EXPIRED";
            /** Format: date-time */
            completed_at: string;
        };
        CancelReservationRequest: {
            order_id: components["schemas"]["OpaqueId"];
            promotion_id: components["schemas"]["OpaqueId"];
        } & {
            [key: string]: unknown;
        };
        CancelReservationResponse: {
            order_id: components["schemas"]["OpaqueId"];
            promotion_id: components["schemas"]["OpaqueId"];
            /** @enum {string} */
            outcome: "UNKNOWN" | "RELEASED" | "NO_RESERVATION" | "REPAIR_REQUIRED";
        };
        /**
         * Problem Details
         * @description RFC 9457 Problem Details với extension chung của dự án.
         */
        "problem.schema": {
            /** Format: uri-reference */
            type: string;
            title: string;
            status: number;
            detail: string;
            /** Format: uri-reference */
            instance: string;
            code: string;
            trace_id?: string;
            /** @default false */
            retryable: boolean;
            errors?: {
                field: string;
                message: string;
            }[];
        } & {
            [key: string]: unknown;
        };
        /**
         * Money
         * @description Số tiền theo đơn vị nhỏ nhất; không dùng floating point.
         */
        "money.schema": {
            /** Format: int64 */
            amount_minor: number;
            /**
             * @example VND
             * @example USD
             */
            currency: string;
        };
    };
    responses: {
        /** @description Request syntax/header/schema không hợp lệ. */
        BadRequest: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Thiếu hoặc sai identity. */
        Unauthorized: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Caller không có role/capability cho operation (code FORBIDDEN), gồm technical-cancellations thiếu hoặc sai X-Legacy-Migration-Proof. */
        Forbidden: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Resource không tồn tại hoặc không visible với caller. */
        NotFound: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Domain/idempotency conflict. code có thể là CAMPAIGN_NOT_ACTIVE, CLAIM_GATE_STALE, ALREADY_CLAIMED, OUT_OF_STOCK, ALREADY_USED_VOUCHER, VOUCHER_EXPIRED, VOUCHER_NOT_OWNED, BUDGET_EXHAUSTED, IDEMPOTENCY_PAYLOAD_MISMATCH, EXPECTED_DISCOUNT_MISMATCH, RESERVATION_NOT_FOUND, FINAL_COMMAND_STATE_INCONSISTENT, INVALID_STATE_TRANSITION, CAMPAIGN_NOT_DRAFT, DISTRIBUTION_MODE_MISMATCH, CAMPAIGN_CODE_ALREADY_EXISTS, PROMOTION_CODE_ALREADY_EXISTS, MARKDOWN_RULE_ALREADY_EXISTS hoặc PROMOTION_CONSTRAINT_VIOLATION (ràng buộc PostgreSQL bị vi phạm bởi race không có mã riêng; tải lại rồi thử lại). */
        Conflict: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description If-Match không bằng campaign revision hiện hành; code CAMPAIGN_REVISION_MISMATCH. */
        RevisionMismatch: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Semantics không hợp lệ; code CONDITIONS_NOT_MET, INVALID_RULE hoặc PRODUCT_TOMBSTONED. */
        Unprocessable: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Gateway promotion-discovery quota exceeded. */
        RateLimited: {
            headers: {
                "Retry-After"?: number;
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Dependency/write path unavailable; retryable=true. */
        Unavailable: {
            headers: {
                "Retry-After"?: number;
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
    };
    parameters: {
        RequestId: string;
        /** @description REDEEM_FINAL:{order_id}:{promotion_id}. */
        ConfirmIdempotencyKey: string;
        /** @description CANCEL_FINAL:{order_id}:{promotion_id}. */
        TechnicalCancellationIdempotencyKey: string;
        /** @description Immutable approved migration record ID; it must equal `migration_record_id` of the body (the evidence digest travels in the body). Missing or different is `403 FORBIDDEN`. */
        LegacyMigrationProof: string;
        /** @description Strong ETag mang parent campaign revision, ví dụ "7". */
        IfMatchCampaign: string;
        CampaignId: components["schemas"]["OpaqueId"];
        PromotionId: components["schemas"]["OpaqueId"];
        MarkdownRuleId: components["schemas"]["OpaqueId"];
        Cursor: string;
        Limit: number;
    };
    requestBodies: never;
    headers: {
        /** @description Canonical URI của resource đã tạo. */
        ResourceLocation: string;
        /** @description Strong ETag được tạo từ parent campaign revision. */
        CampaignETag: string;
        NoStore: "no-store";
    };
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    listPromotionCampaigns: {
        parameters: {
            query?: {
                cursor?: components["parameters"]["Cursor"];
                limit?: components["parameters"]["Limit"];
                publication_status?: components["schemas"]["CampaignPublicationStatus"];
                /** @description Phase được suy ra tại thời điểm query, không phải cột persisted. */
                phase?: components["schemas"]["CampaignPhase"];
            };
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Trang campaign theo cursor. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CampaignPage"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            503: components["responses"]["Unavailable"];
        };
    };
    createPromotionCampaign: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateCampaignRequest"];
            };
        };
        responses: {
            /** @description Campaign DRAFT đã tạo. */
            201: {
                headers: {
                    Location: components["headers"]["ResourceLocation"];
                    ETag: components["headers"]["CampaignETag"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Campaign"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            409: components["responses"]["Conflict"];
            503: components["responses"]["Unavailable"];
        };
    };
    getPromotionCampaign: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                campaign_id: components["parameters"]["CampaignId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Campaign hiện tại; phase được tính theo clock của PostgreSQL. */
            200: {
                headers: {
                    ETag: components["headers"]["CampaignETag"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Campaign"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            503: components["responses"]["Unavailable"];
        };
    };
    updateDraftPromotionCampaign: {
        parameters: {
            query?: never;
            header: {
                /** @description Strong ETag mang parent campaign revision, ví dụ "7". */
                "If-Match": components["parameters"]["IfMatchCampaign"];
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                campaign_id: components["parameters"]["CampaignId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/merge-patch+json": components["schemas"]["UpdateCampaignRequest"];
            };
        };
        responses: {
            /** @description Campaign DRAFT đã cập nhật. */
            200: {
                headers: {
                    ETag: components["headers"]["CampaignETag"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Campaign"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            409: components["responses"]["Conflict"];
            412: components["responses"]["RevisionMismatch"];
            503: components["responses"]["Unavailable"];
        };
    };
    clonePromotionCampaign: {
        parameters: {
            query?: never;
            header: {
                /** @description Strong ETag mang parent campaign revision, ví dụ "7". */
                "If-Match": components["parameters"]["IfMatchCampaign"];
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                campaign_id: components["parameters"]["CampaignId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CloneCampaignRequest"];
            };
        };
        responses: {
            /** @description Aggregate DRAFT mới đã tạo. */
            201: {
                headers: {
                    Location: components["headers"]["ResourceLocation"];
                    ETag: components["headers"]["CampaignETag"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Campaign"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            409: components["responses"]["Conflict"];
            412: components["responses"]["RevisionMismatch"];
            503: components["responses"]["Unavailable"];
        };
    };
    transitionPromotionCampaign: {
        parameters: {
            query?: never;
            header: {
                /** @description Strong ETag mang parent campaign revision, ví dụ "7". */
                "If-Match": components["parameters"]["IfMatchCampaign"];
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                campaign_id: components["parameters"]["CampaignId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CampaignTransitionRequest"];
            };
        };
        responses: {
            /** @description Transition đã commit trong PostgreSQL; Redis schedule/cache hội tụ sau commit. */
            200: {
                headers: {
                    ETag: components["headers"]["CampaignETag"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Campaign"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            409: components["responses"]["Conflict"];
            412: components["responses"]["RevisionMismatch"];
            422: components["responses"]["Unprocessable"];
            503: components["responses"]["Unavailable"];
        };
    };
    listCampaignPromotions: {
        parameters: {
            query?: {
                cursor?: components["parameters"]["Cursor"];
                limit?: components["parameters"]["Limit"];
            };
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                campaign_id: components["parameters"]["CampaignId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Trang promotion definition. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PromotionPage"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            503: components["responses"]["Unavailable"];
        };
    };
    createCampaignPromotion: {
        parameters: {
            query?: never;
            header: {
                /** @description Strong ETag mang parent campaign revision, ví dụ "7". */
                "If-Match": components["parameters"]["IfMatchCampaign"];
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                campaign_id: components["parameters"]["CampaignId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreatePromotionRequest"];
            };
        };
        responses: {
            /** @description Promotion đã tạo và campaign revision tăng. */
            201: {
                headers: {
                    Location: components["headers"]["ResourceLocation"];
                    ETag: components["headers"]["CampaignETag"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Promotion"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            409: components["responses"]["Conflict"];
            412: components["responses"]["RevisionMismatch"];
            422: components["responses"]["Unprocessable"];
            503: components["responses"]["Unavailable"];
        };
    };
    getAdminPromotion: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                promotion_id: components["parameters"]["PromotionId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Promotion hiện tại. */
            200: {
                headers: {
                    ETag: components["headers"]["CampaignETag"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Promotion"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            503: components["responses"]["Unavailable"];
        };
    };
    updateDraftPromotion: {
        parameters: {
            query?: never;
            header: {
                /** @description Strong ETag mang parent campaign revision, ví dụ "7". */
                "If-Match": components["parameters"]["IfMatchCampaign"];
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                promotion_id: components["parameters"]["PromotionId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/merge-patch+json": components["schemas"]["UpdatePromotionRequest"];
            };
        };
        responses: {
            /** @description Promotion đã cập nhật và parent campaign revision tăng. */
            200: {
                headers: {
                    ETag: components["headers"]["CampaignETag"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Promotion"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            409: components["responses"]["Conflict"];
            412: components["responses"]["RevisionMismatch"];
            422: components["responses"]["Unprocessable"];
            503: components["responses"]["Unavailable"];
        };
    };
    grantPromotionVoucher: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                promotion_id: components["parameters"]["PromotionId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["GrantVoucherRequest"];
            };
        };
        responses: {
            /** @description Existing SAVED wallet được replay, không kéo dài expiry. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["WalletVoucher"];
                };
            };
            /** @description Wallet voucher đã được cấp. */
            201: {
                headers: {
                    Location: components["headers"]["ResourceLocation"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["WalletVoucher"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            409: components["responses"]["Conflict"];
            503: components["responses"]["Unavailable"];
        };
    };
    listCampaignMarkdownRules: {
        parameters: {
            query?: {
                cursor?: components["parameters"]["Cursor"];
                limit?: components["parameters"]["Limit"];
            };
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                campaign_id: components["parameters"]["CampaignId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Trang markdown rule. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MarkdownRulePage"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            503: components["responses"]["Unavailable"];
        };
    };
    createCampaignMarkdownRule: {
        parameters: {
            query?: never;
            header: {
                /** @description Strong ETag mang parent campaign revision, ví dụ "7". */
                "If-Match": components["parameters"]["IfMatchCampaign"];
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                campaign_id: components["parameters"]["CampaignId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateMarkdownRuleRequest"];
            };
        };
        responses: {
            /** @description Markdown rule đã tạo và campaign revision tăng. */
            201: {
                headers: {
                    Location: components["headers"]["ResourceLocation"];
                    ETag: components["headers"]["CampaignETag"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MarkdownRule"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            409: components["responses"]["Conflict"];
            412: components["responses"]["RevisionMismatch"];
            422: components["responses"]["Unprocessable"];
            503: components["responses"]["Unavailable"];
        };
    };
    getAdminMarkdownRule: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                markdown_rule_id: components["parameters"]["MarkdownRuleId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Markdown rule hiện tại, gồm operational invalidation nếu có. */
            200: {
                headers: {
                    ETag: components["headers"]["CampaignETag"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MarkdownRule"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            503: components["responses"]["Unavailable"];
        };
    };
    updateDraftMarkdownRule: {
        parameters: {
            query?: never;
            header: {
                /** @description Strong ETag mang parent campaign revision, ví dụ "7". */
                "If-Match": components["parameters"]["IfMatchCampaign"];
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                markdown_rule_id: components["parameters"]["MarkdownRuleId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/merge-patch+json": components["schemas"]["UpdateMarkdownRuleRequest"];
            };
        };
        responses: {
            /** @description Markdown rule đã cập nhật và parent campaign revision tăng. */
            200: {
                headers: {
                    ETag: components["headers"]["CampaignETag"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MarkdownRule"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            409: components["responses"]["Conflict"];
            412: components["responses"]["RevisionMismatch"];
            422: components["responses"]["Unprocessable"];
            503: components["responses"]["Unavailable"];
        };
    };
    suggestMyPromotionVouchers: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SuggestionRequest"];
            };
        };
        responses: {
            /** @description Voucher wallet được xếp hạng cùng eligibility hiện tại. */
            200: {
                headers: {
                    "Cache-Control": components["headers"]["NoStore"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["VoucherSuggestionResponse"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            503: components["responses"]["Unavailable"];
        };
    };
    discoverPlatformPromotions: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["DiscoveryRequest"];
            };
        };
        responses: {
            /** @description Danh sách wallet + public promotion đã evaluate và xếp hạng. */
            200: {
                headers: {
                    "Cache-Control": components["headers"]["NoStore"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PromotionDiscoveryResponse"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            429: components["responses"]["RateLimited"];
            503: components["responses"]["Unavailable"];
        };
    };
    saveAndEvaluatePromotionVoucher: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                promotion_id: components["parameters"]["PromotionId"];
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": components["schemas"]["SaveAndEvaluateRequest"];
            };
        };
        responses: {
            /** @description Existing SAVED wallet được replay và evaluate. */
            200: {
                headers: {
                    "Cache-Control": components["headers"]["NoStore"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SaveAndEvaluateResponse"];
                };
            };
            /** @description Voucher đã được lưu và evaluate. */
            201: {
                headers: {
                    Location: components["headers"]["ResourceLocation"];
                    "Cache-Control": components["headers"]["NoStore"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SaveAndEvaluateResponse"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            404: components["responses"]["NotFound"];
            409: components["responses"]["Conflict"];
            503: components["responses"]["Unavailable"];
        };
    };
    claimLimitedPromotionVoucher: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ClaimVoucherRequest"];
            };
        };
        responses: {
            /** @description Durable claim đã tồn tại và được replay. */
            200: {
                headers: {
                    "Cache-Control": components["headers"]["NoStore"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ClaimVoucherResponse"];
                };
            };
            /** @description Claim và wallet đã commit PostgreSQL. */
            201: {
                headers: {
                    Location: components["headers"]["ResourceLocation"];
                    "Cache-Control": components["headers"]["NoStore"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ClaimVoucherResponse"];
                };
            };
            /** @description Redis acquisition có evidence nhưng durable persistence chưa xác nhận. */
            202: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ClaimPendingResponse"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            404: components["responses"]["NotFound"];
            409: components["responses"]["Conflict"];
            503: components["responses"]["Unavailable"];
        };
    };
    calculatePromotionDiscount: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["DiscountCalculationRequest"];
            };
        };
        responses: {
            /** @description Kết quả deterministic cho snapshot/rule/context đã dùng. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["DiscountCalculationResult"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            503: components["responses"]["Unavailable"];
        };
    };
    listPromotionVoucherOptions: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["VoucherOptionRequest"];
            };
        };
        responses: {
            /** @description Voucher đã evaluate, xếp hạng và kèm thông tin hiển thị. */
            200: {
                headers: {
                    "Cache-Control": components["headers"]["NoStore"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["VoucherOptionList"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            503: components["responses"]["Unavailable"];
        };
    };
    reservePromotionAllocation: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ReserveRequest"];
            };
        };
        responses: {
            /** @description Existing matching reservation được replay. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ReservationResponse"];
                };
            };
            /** @description Reservation runtime đã tạo. */
            201: {
                headers: {
                    Location: components["headers"]["ResourceLocation"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ReservationResponse"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            409: components["responses"]["Conflict"];
            422: components["responses"]["Unprocessable"];
            503: components["responses"]["Unavailable"];
        };
    };
    confirmPromotionAllocation: {
        parameters: {
            query?: never;
            header: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
                /** @description REDEEM_FINAL:{order_id}:{promotion_id}. */
                "Idempotency-Key": components["parameters"]["ConfirmIdempotencyKey"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["FinalAllocationRequest"];
            };
        };
        responses: {
            /** @description Durable confirmation mới hoặc stored response replay. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfirmationResponse"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            409: components["responses"]["Conflict"];
            503: components["responses"]["Unavailable"];
        };
    };
    cancelPromotionReservation: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CancelReservationRequest"];
            };
        };
        responses: {
            /** @description Kết quả cancel/no-op/repair rõ ràng. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CancelReservationResponse"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            409: components["responses"]["Conflict"];
            503: components["responses"]["Unavailable"];
        };
    };
    cancelConfirmedPromotionAllocation: {
        parameters: {
            query?: never;
            header: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
                /** @description CANCEL_FINAL:{order_id}:{promotion_id}. */
                "Idempotency-Key": components["parameters"]["TechnicalCancellationIdempotencyKey"];
                /** @description Immutable approved migration record ID; it must equal `migration_record_id` of the body (the evidence digest travels in the body). Missing or different is `403 FORBIDDEN`. */
                "X-Legacy-Migration-Proof": components["parameters"]["LegacyMigrationProof"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["LegacyTechnicalCancellationRequest"];
            };
        };
        responses: {
            /** @description Durable technical cancellation mới hoặc stored response replay. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TechnicalCancellationResponse"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            409: components["responses"]["Conflict"];
            503: components["responses"]["Unavailable"];
        };
    };
}
