/**
 * FILE SINH TỰ ĐỘNG — KHÔNG SỬA TAY.
 * Nguồn: contracts/http/checkout/v1/openapi.yaml
 * Sinh lại bằng `scripts/node.sh npm run gen:api`.
 */
export interface paths {
    "/api/v1/checkout/preview": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Create the user's current immutable checkout quotation
         * @description Idempotency-Key scopes one logical Preview attempt for 5 minutes. Without previous_checkout_token the request starts a fresh quotation and atomically supersedes the user's current PREVIEWED quotation, so a reload never blocks the buyer. With previous_checkout_token the token must equal the current PREVIEWED quotation, otherwise 409 ACTIVE_CHECKOUT_CHANGED. While the user's latest quotation is being submitted (state SUBMITTING or RETRYABLE, shown to the client as PREPARING or RESULT_UNKNOWN) Preview returns 409 SUBMISSION_IN_PROGRESS with status_url and creates nothing (Checkout ADR-016). Items and quantities come from Cart; prices and category facts from Product; address snapshot from User; allocation plan from Inventory; discount from Promotion. Any required dependency failure returns 503 and no quotation.
         */
        post: operations["previewCheckout"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/checkout/previews/{checkout_token}/voucher-options": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * List wallet and public vouchers evaluated against one quotation
         * @description Read-only composition (Checkout ADR-017). Checkout builds the trusted Promotion context from the token-owned snapshot (Product prices, Product category_ids, Cart quantities, subtotal, channel CHECKOUT) and never from the browser. Results are advisory: applying a voucher always means a new Preview with promotion_id. Saving a PUBLIC_SAVE voucher or claiming a LIMITED_CLAIM voucher is done directly against Promotion as required_action indicates. Only a PREVIEWED quotation that has not expired is accepted. Promotion failure returns 503 VOUCHER_OPTIONS_UNAVAILABLE and never affects Preview or Submit.
         */
        get: operations["listCheckoutVoucherOptions"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/checkout/submit": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Submit one immutable quotation to Order
         * @description checkout_token is the public idempotency identity. Checkout atomically claims SUBMITTING and persists the exact Order payload, then revalidates Cart quantities, Product price, saleability and category facts, and the selected Promotion calculation. Order revalidates the advisory Inventory plan while reserving it. Once claimed, the Order call is detached from client cancellation. A replay never creates a second logical order. Conflicts and failures are terminal for the token; the client starts a new Preview as the problem action says.
         */
        post: operations["submitCheckout"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/checkout/submissions/{checkout_token}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Recover the current durable result of a Checkout submission
         * @description Reads the token-owned Checkout result and reconciles with Order's durable checkout-order-submission resource. Payment client data is relayed fresh and is never read from a Checkout cache. When Order confirms the submission is absent, Checkout re-sends the identical payload and Idempotency-Key only inside the bounded submit retry window; afterwards the token ends as SUBMISSION_NOT_ACCEPTED (Checkout ADR-014).
         */
        get: operations["getCheckoutSubmission"];
        put?: never;
        post?: never;
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
        Problem: components["schemas"]["problem.schema"];
        /**
         * @description Stable Checkout problem catalog. HTTP status, retryable and action per code are defined in docs/checkout/interfaces.md (Checkout ADR-015). RATE_LIMITED is produced by Gateway, not Checkout.
         * @enum {string}
         */
        CheckoutProblemCode: "INVALID_REQUEST" | "UNAUTHENTICATED" | "IDEMPOTENCY_KEY_REUSED" | "ACTIVE_CHECKOUT_CHANGED" | "SUBMISSION_IN_PROGRESS" | "CHECKOUT_ALREADY_SUBMITTED" | "CART_EMPTY" | "SKU_UNAVAILABLE" | "ADDRESS_NOT_FOUND" | "INSUFFICIENT_STOCK" | "PROMOTION_INVALID" | "FINAL_AMOUNT_NOT_PAYABLE" | "CHECKOUT_NOT_FOUND" | "SUBMISSION_NOT_FOUND" | "CHECKOUT_EXPIRED" | "CHECKOUT_SUPERSEDED" | "CART_CHANGED" | "PRICE_CHANGED" | "PROMOTION_CHANGED" | "PROMOTION_RECALCULATION_NEEDED" | "INVENTORY_RESERVATION_REJECTED" | "PAYMENT_INITIATION_REJECTED" | "CHECKOUT_PAYMENT_WINDOW_EXHAUSTED" | "ORDER_CANCELLED_DURING_PREPARATION" | "SUBMISSION_NOT_ACCEPTED" | "SUBMISSION_REJECTED" | "CHECKOUT_UNAVAILABLE" | "VOUCHER_OPTIONS_UNAVAILABLE";
        /**
         * @description What the client should do next; never a reason to retry the same request unless RETRY.
         * @enum {string}
         */
        ProblemAction: "RETRY" | "START_NEW_PREVIEW" | "EDIT_CART" | "CHANGE_ADDRESS" | "CHANGE_VOUCHER" | "VIEW_SUBMISSION" | "NONE";
        /** @enum {string} */
        ChangedField: "CART" | "AVAILABILITY" | "PRICE" | "PROMOTION";
        /**
         * @description Promotion eligibility reason relayed for display; unknown future values are mapped to UNKNOWN.
         * @enum {string}
         */
        PromotionReason: "UNKNOWN" | "ELIGIBLE" | "CONDITIONS_NOT_MET" | "MIN_ORDER_NOT_MET" | "VOUCHER_NOT_OWNED" | "VOUCHER_EXPIRED" | "ALREADY_USED_VOUCHER" | "CAMPAIGN_NOT_ACTIVE" | "PROMOTION_DISABLED" | "BUDGET_EXHAUSTED";
        RejectedLine: {
            sku_id: string;
            quantity: number;
        };
        DroppedPromotion: {
            promotion_id: components["schemas"]["OpaqueId"];
            reason: string;
        };
        CheckoutProblem: components["schemas"]["problem.schema"] & {
            code: components["schemas"]["CheckoutProblemCode"];
            retryable: boolean;
            action: components["schemas"]["ProblemAction"];
            /** @description Present for SUBMISSION_IN_PROGRESS and CHECKOUT_ALREADY_SUBMITTED. */
            status_url?: string;
            /** @description Present for Submit revalidation conflicts; lists every detected change. */
            changed_fields?: components["schemas"]["ChangedField"][];
            /** @description Present for SKU_UNAVAILABLE. */
            sku_ids?: string[];
            /** @description Present for INSUFFICIENT_STOCK; lines Inventory could not allocate. */
            rejected_lines?: components["schemas"]["RejectedLine"][];
            promotion_reason?: components["schemas"]["PromotionReason"];
            dropped_promotion?: components["schemas"]["DroppedPromotion"];
        };
        Money: {
            /** Format: int64 */
            amount_minor: number;
            /** @constant */
            currency: "VND";
        };
        OpaqueId: string;
        CheckoutPreviewRequest: {
            /**
             * Format: uuid
             * @description Current PREVIEWED token when the buyer changes address or voucher on the same page. Omit or null to start a fresh quotation (page load, new tab, after a terminal Submit result).
             */
            previous_checkout_token?: string | null;
            /** Format: uuid */
            delivery_address_id: string;
            promotion_id?: components["schemas"]["OpaqueId"] | null;
        };
        CheckoutSubmitRequest: {
            /** Format: uuid */
            checkout_token: string;
        };
        AddressSnapshot: {
            /** Format: uuid */
            address_id: string;
            recipient_name: string;
            recipient_phone: string;
            address_line: string;
            province_code: string;
            district_code: string;
            ward_code: string;
        };
        PreviewItem: {
            /** @description Equal to sku_id in Checkout v1. */
            line_reference: string;
            /** Format: uuid */
            product_id: string;
            sku_id: string;
            product_name: string;
            /** @description Product attribute code to value, copied unchanged from Product GetBatchSkus. */
            variant_attributes: {
                [key: string]: string;
            };
            /**
             * Format: uri
             * @description CDN URL built by Checkout from the Product storage key; a configured placeholder when the SKU has no image.
             */
            image_url: string;
            quantity: number;
            /** @description Product base_price before markdown; display only, never charged. */
            list_unit_price: components["schemas"]["Money"];
            /** @description Product published selling price used for every total. */
            unit_price: components["schemas"]["Money"];
            line_total: components["schemas"]["Money"];
        };
        PromotionPreview: {
            promotion_id: components["schemas"]["OpaqueId"] | null;
            /**
             * Format: int64
             * @description Zero iff promotion_id is null.
             */
            campaign_revision: number;
            discount: components["schemas"]["Money"];
        };
        /** @description total_amount is the sum of line_total; final_amount equals total_amount minus total_discount and is at least 1 VND. There is no shipping fee in v1. */
        Pricing: {
            total_amount: components["schemas"]["Money"];
            total_discount: components["schemas"]["Money"];
            final_amount: components["schemas"]["Money"];
        };
        CheckoutPreview: {
            /** Format: uuid */
            checkout_token: string;
            /**
             * Format: date-time
             * @description Quotation validity end (20 minutes after creation); Submit after it returns 410 CHECKOUT_EXPIRED.
             */
            expires_at: string;
            address: components["schemas"]["AddressSnapshot"];
            items: components["schemas"]["PreviewItem"][];
            promotion: components["schemas"]["PromotionPreview"];
            pricing: components["schemas"]["Pricing"];
        };
        PercentageDiscountRule: {
            /**
             * @description discriminator enum property added by openapi-typescript
             * @enum {string}
             */
            type: "PERCENTAGE";
            value_basis_points: number;
            max_discount: components["schemas"]["Money"] | null;
        };
        FixedDiscountRule: {
            /**
             * @description discriminator enum property added by openapi-typescript
             * @enum {string}
             */
            type: "FIXED_VND";
            value: components["schemas"]["Money"];
        };
        DiscountRule: components["schemas"]["PercentageDiscountRule"] | components["schemas"]["FixedDiscountRule"];
        VoucherOption: {
            promotion_id: components["schemas"]["OpaqueId"];
            /**
             * @description WALLET is already in the buyer's wallet (saved, claimed or granted); PUBLIC is not yet owned.
             * @enum {string}
             */
            source: "WALLET" | "PUBLIC";
            /**
             * @description NONE: Preview with this promotion_id. SAVE: PUT /api/v1/me/promotion-vouchers/{promotion_id} first. CLAIM: POST /api/v1/me/promotion-voucher-claims with expected_campaign_revision first.
             * @enum {string}
             */
            required_action: "NONE" | "SAVE" | "CLAIM";
            /** Format: int64 */
            expected_campaign_revision: number;
            code: string;
            name: string;
            description: string | null;
            discount_rule: components["schemas"]["DiscountRule"];
            min_order_amount: components["schemas"]["Money"];
            /**
             * Format: date-time
             * @description Wallet expiry for WALLET, campaign end for PUBLIC; null when Promotion reports none.
             */
            valid_until: string | null;
            eligible: boolean;
            reason: components["schemas"]["PromotionReason"];
            /** @description Advisory; the applied discount always comes from a new Preview. */
            estimated_discount: components["schemas"]["Money"];
            estimated_final_amount: components["schemas"]["Money"];
            /** @description True when this promotion_id is the one applied in the quotation. */
            is_applied: boolean;
        };
        VoucherOptions: {
            /** Format: uuid */
            checkout_token: string;
            applied_promotion_id: components["schemas"]["OpaqueId"] | null;
            items: components["schemas"]["VoucherOption"][];
            /** Format: date-time */
            evaluated_at: string;
        };
        PaymentClientData: {
            payment_attempt_number: number;
            payment_transaction_id: components["schemas"]["OpaqueId"];
            usable: boolean;
            client_secret: string | null;
            publishable_key: string | null;
            /** Format: date-time */
            expires_at: string | null;
        };
        CheckoutSubmissionReady: {
            /** Format: uuid */
            checkout_token: string;
            order_id: string;
            /** @constant */
            order_status: "PENDING_PAYMENT";
            payment: components["schemas"]["PaymentClientData"];
        };
        CheckoutSubmissionPending: {
            /** Format: uuid */
            checkout_token: string;
            /**
             * @description PREPARING: Order is working towards PAYMENT_READY. RESULT_UNKNOWN: the Order outcome is not yet known; never start a new checkout, keep polling status_url.
             * @enum {string}
             */
            state: "PREPARING" | "RESULT_UNKNOWN";
            /** Format: uri-reference */
            status_url: string;
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
    };
    responses: {
        /** @description INVALID_REQUEST — malformed JSON, unsupported field, invalid identifier or missing Idempotency-Key. */
        BadRequest: {
            headers: {
                "Cache-Control": components["headers"]["PrivateNoStore"];
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["CheckoutProblem"];
            };
        };
        /** @description UNAUTHENTICATED — missing or untrusted Gateway identity. */
        Unauthorized: {
            headers: {
                "Cache-Control": components["headers"]["PrivateNoStore"];
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["CheckoutProblem"];
            };
        };
        /** @description CHECKOUT_NOT_FOUND (token unknown to this user or physically evicted) or SUBMISSION_NOT_FOUND (token was never submitted). */
        NotFound: {
            headers: {
                "Cache-Control": components["headers"]["PrivateNoStore"];
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["CheckoutProblem"];
            };
        };
        /** @description IDEMPOTENCY_KEY_REUSED, ACTIVE_CHECKOUT_CHANGED or SUBMISSION_IN_PROGRESS (with status_url). */
        PreviewConflict: {
            headers: {
                "Cache-Control": components["headers"]["PrivateNoStore"];
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["CheckoutProblem"];
            };
        };
        /** @description CHECKOUT_ALREADY_SUBMITTED (with status_url); voucher options exist only for a PREVIEWED quotation. */
        AlreadySubmitted: {
            headers: {
                "Cache-Control": components["headers"]["PrivateNoStore"];
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["CheckoutProblem"];
            };
        };
        /** @description Terminal for the token. Submit revalidation: CART_CHANGED, SKU_UNAVAILABLE, PRICE_CHANGED or PROMOTION_CHANGED with changed_fields. Order outcome: PROMOTION_RECALCULATION_NEEDED (with dropped_promotion), INVENTORY_RESERVATION_REJECTED, PAYMENT_INITIATION_REJECTED, CHECKOUT_PAYMENT_WINDOW_EXHAUSTED, ORDER_CANCELLED_DURING_PREPARATION, SUBMISSION_NOT_ACCEPTED or SUBMISSION_REJECTED. */
        SubmissionConflict: {
            headers: {
                "Cache-Control": components["headers"]["PrivateNoStore"];
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["CheckoutProblem"];
            };
        };
        /** @description CHECKOUT_EXPIRED or CHECKOUT_SUPERSEDED while the owner binding is retained. */
        Gone: {
            headers: {
                "Cache-Control": components["headers"]["PrivateNoStore"];
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["CheckoutProblem"];
            };
        };
        /** @description CART_EMPTY, SKU_UNAVAILABLE (with sku_ids), ADDRESS_NOT_FOUND, INSUFFICIENT_STOCK (with rejected_lines), PROMOTION_INVALID (with promotion_reason) or FINAL_AMOUNT_NOT_PAYABLE. */
        Unprocessable: {
            headers: {
                "Cache-Control": components["headers"]["PrivateNoStore"];
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["CheckoutProblem"];
            };
        };
        /** @description Produced by Gateway, never by Checkout, which keeps no rate-limit state: profiles checkout-preview, checkout-submit and checkout-voucher-options, and api-authenticated for the status read. */
        RateLimited: {
            headers: {
                "Retry-After": components["headers"]["RetryAfter"];
                "Cache-Control": components["headers"]["PrivateNoStore"];
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description CHECKOUT_UNAVAILABLE — a required dependency failed, timed out, has an open circuit or the request deadline was exhausted; no quotation or claim is committed. VOUCHER_OPTIONS_UNAVAILABLE — Promotion could not list voucher options. */
        Unavailable: {
            headers: {
                "Retry-After": components["headers"]["RetryAfter"];
                "Cache-Control": components["headers"]["PrivateNoStore"];
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["CheckoutProblem"];
            };
        };
    };
    parameters: {
        /** @description Optional correlation hint. Checkout forwards it only when it matches ^[A-Za-z0-9._:-]{8,128}$; otherwise the current trace ID is used. An invalid value never fails the request. */
        RequestId: string;
        /** @description UUID identifying one logical Preview attempt; reuse with a different body is 409 IDEMPOTENCY_KEY_REUSED. */
        PreviewIdempotencyKey: string;
        /** @description Cryptographically random UUIDv4 issued by Checkout. Logs and Gateway access logs redact this segment. */
        CheckoutToken: string;
    };
    requestBodies: never;
    headers: {
        PrivateNoStore: "private, no-store";
        SubmissionLocation: string;
        RetryAfter: number;
    };
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    previewCheckout: {
        parameters: {
            query?: never;
            header: {
                /** @description Optional correlation hint. Checkout forwards it only when it matches ^[A-Za-z0-9._:-]{8,128}$; otherwise the current trace ID is used. An invalid value never fails the request. */
                "X-Request-Id"?: components["parameters"]["RequestId"];
                /** @description UUID identifying one logical Preview attempt; reuse with a different body is 409 IDEMPOTENCY_KEY_REUSED. */
                "Idempotency-Key": components["parameters"]["PreviewIdempotencyKey"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CheckoutPreviewRequest"];
            };
        };
        responses: {
            /** @description Immutable quotation ready for review, voucher options and Submit. */
            200: {
                headers: {
                    "Cache-Control": components["headers"]["PrivateNoStore"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CheckoutPreview"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            409: components["responses"]["PreviewConflict"];
            422: components["responses"]["Unprocessable"];
            429: components["responses"]["RateLimited"];
            503: components["responses"]["Unavailable"];
        };
    };
    listCheckoutVoucherOptions: {
        parameters: {
            query?: never;
            header?: {
                /** @description Optional correlation hint. Checkout forwards it only when it matches ^[A-Za-z0-9._:-]{8,128}$; otherwise the current trace ID is used. An invalid value never fails the request. */
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                /** @description Cryptographically random UUIDv4 issued by Checkout. Logs and Gateway access logs redact this segment. */
                checkout_token: components["parameters"]["CheckoutToken"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Ranked voucher options; eligible first, then larger discount, then promotion_id. */
            200: {
                headers: {
                    "Cache-Control": components["headers"]["PrivateNoStore"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["VoucherOptions"];
                };
            };
            401: components["responses"]["Unauthorized"];
            404: components["responses"]["NotFound"];
            409: components["responses"]["AlreadySubmitted"];
            410: components["responses"]["Gone"];
            429: components["responses"]["RateLimited"];
            503: components["responses"]["Unavailable"];
        };
    };
    submitCheckout: {
        parameters: {
            query?: never;
            header?: {
                /** @description Optional correlation hint. Checkout forwards it only when it matches ^[A-Za-z0-9._:-]{8,128}$; otherwise the current trace ID is used. An invalid value never fails the request. */
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CheckoutSubmitRequest"];
            };
        };
        responses: {
            /** @description Idempotent replay of a PAYMENT_READY submission with freshly relayed Payment client data. */
            200: {
                headers: {
                    Location: components["headers"]["SubmissionLocation"];
                    "Cache-Control": components["headers"]["PrivateNoStore"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CheckoutSubmissionReady"];
                };
            };
            /** @description First completion at the durable PAYMENT_READY checkpoint. */
            201: {
                headers: {
                    Location: components["headers"]["SubmissionLocation"];
                    "Cache-Control": components["headers"]["PrivateNoStore"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CheckoutSubmissionReady"];
                };
            };
            /** @description Order is preparing, or may have accepted the submission; poll Location. */
            202: {
                headers: {
                    Location: components["headers"]["SubmissionLocation"];
                    "Retry-After": components["headers"]["RetryAfter"];
                    "Cache-Control": components["headers"]["PrivateNoStore"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CheckoutSubmissionPending"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            404: components["responses"]["NotFound"];
            409: components["responses"]["SubmissionConflict"];
            410: components["responses"]["Gone"];
            429: components["responses"]["RateLimited"];
            503: components["responses"]["Unavailable"];
        };
    };
    getCheckoutSubmission: {
        parameters: {
            query?: never;
            header?: {
                /** @description Optional correlation hint. Checkout forwards it only when it matches ^[A-Za-z0-9._:-]{8,128}$; otherwise the current trace ID is used. An invalid value never fails the request. */
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                /** @description Cryptographically random UUIDv4 issued by Checkout. Logs and Gateway access logs redact this segment. */
                checkout_token: components["parameters"]["CheckoutToken"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description PAYMENT_READY terminal result. */
            200: {
                headers: {
                    "Cache-Control": components["headers"]["PrivateNoStore"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CheckoutSubmissionReady"];
                };
            };
            /** @description Submission is PREPARING or its durable Order result is temporarily unknown. */
            202: {
                headers: {
                    "Retry-After": components["headers"]["RetryAfter"];
                    "Cache-Control": components["headers"]["PrivateNoStore"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CheckoutSubmissionPending"];
                };
            };
            401: components["responses"]["Unauthorized"];
            404: components["responses"]["NotFound"];
            409: components["responses"]["SubmissionConflict"];
            429: components["responses"]["RateLimited"];
            503: components["responses"]["Unavailable"];
        };
    };
}
