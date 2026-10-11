/**
 * FILE SINH TỰ ĐỘNG — KHÔNG SỬA TAY.
 * Nguồn: contracts/http/order/v1/openapi.yaml
 * Sinh lại bằng `scripts/node.sh npm run gen:api`.
 */
export interface paths {
    "/api/v1/orders": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Search orders owned by the current Buyer
         * @description Results are ordered by created_at DESC, order_id DESC. The cursor is opaque. When created_from and created_to are both absent, the service searches the most recent 90 days. Text search is an accent-insensitive substring match on the immutable product-name snapshot only.
         */
        get: operations["listMyOrders"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/orders/{order_id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Get one order owned by the current Buyer
         * @description A missing order and an order owned by another Buyer both return 404.
         */
        get: operations["getMyOrder"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/orders/{order_id}/cancellation-requests": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Request cancellation of a pre-payment order
         * @description Accepts only an owned PENDING_PAYMENT order. Acceptance first persists the CANCELLING transition and durable workflow dispatch. Final cancellation waits for a matching Payment non-capture fact and proven resource cleanup.
         */
        post: operations["requestMyOrderCancellation"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/orders/{order_id}/return-requests": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Submit the one lifetime Buyer return request
         * @description Every order line must already be DELIVERED (Admin delivery confirmation, Order ADR-021) and the order must be COMPLETED. Release 1 has no return time window after completion. The selected set may be a proper subset of the order, but each selected line must use its complete ordered quantity. A new idempotency key never bypasses the permanent Buyer-return guard or lifetime refund-case uniqueness.
         */
        post: operations["requestMyOrderReturn"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/orders/{order_id}/refund-case": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Read the single lifetime refund case for an owned order */
        get: operations["getMyOrderRefundCase"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/orders/{order_id}/payment-attempts": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Request or replay creation of the next numbered payment attempt
         * @description The immediately previous attempt must have a durable FAILED fact, the immutable order payment deadline (capped by the earliest resource-hold expiry minus clock skew safety) must not have passed, and both Inventory and Promotion holds must remain accepted. EXPIRED or CANCELED attempts, cleanup started, and terminal orders are not eligible. Acceptance atomically records an operation and durable workflow message; read the operation and then the numbered attempt for Payment client data. Retry a timeout with the same Idempotency-Key.
         */
        post: operations["createMyOrderPaymentAttempt"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/orders/{order_id}/payment-attempts/{attempt_number}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Read one numbered payment attempt
         * @description Client secrets are returned only for the current PENDING attempt.
         */
        get: operations["getMyOrderPaymentAttempt"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/order-operations/{operation_id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Read one asynchronous operation owned by the current Buyer */
        get: operations["getMyOrderOperation"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/orders": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Search all orders as an Admin
         * @description Uses the same PostgreSQL query model and stable sort as Buyer search.
         */
        get: operations["listOrdersAsAdmin"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/orders/{order_id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Read one order as an Admin */
        get: operations["getOrderAsAdmin"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/orders/{order_id}/cancellation-requests": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Request Admin cancellation of a pre-payment order
         * @description Paid orders are rejected; Admin must use the pre-dispatch refund endpoint.
         */
        post: operations["requestOrderCancellationAsAdmin"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/orders/{order_id}/pre-dispatch-refund-requests": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Attempt automatic full refund before any physical dispatch
         * @description The accepted operation creates a durable Admin attempt, not a lifetime refund case. Only Inventory's atomic all-line release success may create that case and initiate Payment refund. An Inventory rejection changes the operation to REJECTED without consuming the Buyer return guard.
         */
        post: operations["requestPreDispatchRefundAsAdmin"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/orders/{order_id}/delivery-confirmations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Confirm that dispatched lines reached the customer
         * @description The only way a line becomes DELIVERED (Order ADR-021). Inventory physical dispatch only moves a line to DISPATCHED. Each requested line must be DISPATCHED; the request is all-or-nothing and runs synchronously under the order lock. The confirmation that delivers the last line completes the order and emits order.completed.v1 in the same transaction. The command is idempotent by line state: repeating a line with the same delivery_reference and delivered_at is a 200 no-op, a different reference or time is 409 DELIVERY_ALREADY_CONFIRMED. delivered_at must not precede the line's physical dispatch nor lie in the future beyond the configured clock tolerance. The Admin's user ID is stored with the confirmation. No Idempotency-Key is used.
         */
        post: operations["confirmDeliveryAsAdmin"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/order-operations/{operation_id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Read one Order operation as an Admin */
        get: operations["getOrderOperationAsAdmin"];
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
        /** @description Canonical unpadded base-10 representation of the Order-owned BIGINT ID. */
        OrderId: string;
        EntityId: string;
        OpaqueId: string;
        Money: {
            /** Format: int64 */
            amount_minor: number;
            /** @constant */
            currency: "VND";
        };
        Problem: components["schemas"]["problem.schema"];
        /**
         * @description CREATED is transaction-local/transient and is never exposed by the public API.
         * @enum {string}
         */
        OrderStatus: "PENDING_PAYMENT" | "CANCELLING" | "PAID" | "PROCESSING" | "COMPLETED" | "CANCELLED" | "RETURN_REQUESTED" | "REFUNDED" | "FINANCIAL_RECONCILIATION_REQUIRED";
        /** @enum {string} */
        PaymentStatus: "PENDING" | "PAID" | "FAILED" | "REFUNDING" | "REFUNDED" | "REFUND_FAILED";
        /**
         * @description Derived with one-tab precedence: PENDING_PAYMENT/CANCELLING => TO_PAY; PAID/PROCESSING with no DISPATCHED/DELIVERED line => TO_SHIP; PAID/PROCESSING with any such line => TO_RECEIVE; return/refund/reconciliation states take the RETURN_REFUND lane. It is not a direct mapping from one master or line state.
         * @enum {string}
         */
        BuyerTab: "TO_PAY" | "TO_SHIP" | "TO_RECEIVE" | "RETURN_REFUND" | "COMPLETED" | "CANCELLED";
        /**
         * @description Display status code derived from order_status and buyer_tab (Order ADR-020): PENDING_PAYMENT=AWAITING_PAYMENT, CANCELLING=CANCELLING, PAID/PROCESSING in TO_SHIP=PREPARING, PAID/PROCESSING in TO_RECEIVE=IN_DELIVERY, COMPLETED=COMPLETED, CANCELLED=CANCELLED, RETURN_REQUESTED=RETURN_IN_PROGRESS, REFUNDED=REFUNDED, FINANCIAL_RECONCILIATION_REQUIRED=UNDER_REVIEW.
         * @enum {string}
         */
        UiPrimaryStatus: "AWAITING_PAYMENT" | "CANCELLING" | "PREPARING" | "IN_DELIVERY" | "COMPLETED" | "CANCELLED" | "RETURN_IN_PROGRESS" | "REFUNDED" | "UNDER_REVIEW";
        /** @enum {string} */
        FulfillmentStatus: "PROCESSING" | "DISPATCHED" | "DELIVERED";
        /** @enum {string} */
        OrderItemStatus: "PROCESSING" | "PARTIALLY_DISPATCHED" | "DISPATCHED" | "PARTIALLY_DELIVERED" | "DELIVERED";
        /** @enum {string} */
        OperationStatus: "ACCEPTED" | "RUNNING" | "SUCCEEDED" | "REJECTED" | "FAILED" | "MANUAL_REVIEW_REQUIRED";
        /** @enum {string} */
        OperationType: "BUYER_CANCELLATION" | "ADMIN_CANCELLATION" | "BUYER_RETURN" | "ADMIN_PRE_DISPATCH_REFUND" | "PAYMENT_ATTEMPT";
        /**
         * @description PENDING means a bound provider transaction is awaiting a terminal result. Initiation progress/unknown create outcome is exposed by OrderOperation, not by inventing an attempt state before transaction identity is bound.
         * @enum {string}
         */
        PaymentAttemptStatus: "PENDING" | "SUCCESS" | "FAILED" | "EXPIRED" | "CANCELED";
        /**
         * @description CLOSED_INCOMPLETE is an approved Buyer return ended without payout, only after Inventory confirms incomplete closure or zero-receipt cancellation. It never means master REFUNDED. EXTERNALLY_REMEDIATED means immutable Accounting/settlement evidence proves the customer received the full locked amount outside the original Payment refund transaction; Payment status remains provider-truth REFUND_FAILED. SUCCEEDED/FAILED are provider refund outcomes; consult optional scope_finalization for remaining physical finalization work.
         * @enum {string}
         */
        RefundCaseStatus: "INITIATED" | "WAITING_INVENTORY_SCOPE_INSPECTION" | "WAITING_INVENTORY_RELEASE" | "WAITING_PAYMENT_REFUND_RESULT" | "MANUAL_VERIFICATION_REQUIRED" | "SUCCEEDED" | "FAILED" | "EXTERNALLY_REMEDIATED" | "CLOSED_INCOMPLETE";
        /** @enum {string} */
        RefundMode: "BUYER_RETURN_SELECTED_LINES" | "ADMIN_PRE_DISPATCH_FULL_ORDER";
        AddressSnapshot: {
            recipient_name: string;
            recipient_phone: string;
            address_line: string;
            province_code: string;
            district_code: string;
            ward_code: string;
            province_name?: string | null;
            district_name?: string | null;
            ward_name?: string | null;
        };
        OrderLine: {
            order_line_id: components["schemas"]["EntityId"];
            product_id: components["schemas"]["OpaqueId"];
            sku_id: components["schemas"]["OpaqueId"];
            quantity: number;
            unit_price: components["schemas"]["Money"];
            fulfillment_status: components["schemas"]["FulfillmentStatus"];
            snapshot_name: string;
            /** Format: uri */
            snapshot_image_url: string | null;
            snapshot_attributes: {
                [key: string]: string;
            };
            /** Format: date-time */
            delivered_at?: string | null;
        };
        OrderItem: {
            order_item_id: components["schemas"]["EntityId"];
            warehouse_id: components["schemas"]["OpaqueId"];
            status: components["schemas"]["OrderItemStatus"];
            delivery_address: components["schemas"]["AddressSnapshot"];
            lines: components["schemas"]["OrderLine"][];
        };
        PromotionSnapshot: {
            promotion_id: components["schemas"]["OpaqueId"];
            discount_amount: components["schemas"]["Money"];
        };
        PaymentSummary: {
            status: components["schemas"]["PaymentStatus"];
            /**
             * Format: date-time
             * @description Immutable order deadline capped by the earliest resource-hold expiry minus clock-skew safety; a new attempt never extends it.
             */
            payment_deadline_at: string;
            active_attempt_number?: number | null;
            active_payment_transaction_id?: components["schemas"]["OpaqueId"] | null;
            captured_payment_transaction_id?: components["schemas"]["OpaqueId"] | null;
        };
        RefundCaseSummary: {
            refund_request_id: components["schemas"]["OpaqueId"];
            mode: components["schemas"]["RefundMode"];
            status: components["schemas"]["RefundCaseStatus"];
            amount: components["schemas"]["Money"];
            scope_finalization?: components["schemas"]["RefundScopeFinalization"];
            refund_transaction_id?: components["schemas"]["OpaqueId"] | null;
        };
        AvailableActions: {
            can_cancel: boolean;
            can_return: boolean;
            /** @description True only when the previous attempt is FAILED, the immutable order deadline is future, and Inventory/Promotion holds remain accepted. */
            can_retry_payment: boolean;
        };
        OrderDetail: {
            order_id: components["schemas"]["OrderId"];
            /** Format: uuid */
            buyer_id: string;
            order_status: components["schemas"]["OrderStatus"];
            payment_status: components["schemas"]["PaymentStatus"];
            buyer_tab: components["schemas"]["BuyerTab"];
            total_amount: components["schemas"]["Money"];
            total_discount: components["schemas"]["Money"];
            final_amount: components["schemas"]["Money"];
            cancel_reason?: string | null;
            return_reason?: string | null;
            items: components["schemas"]["OrderItem"][];
            promotions: components["schemas"]["PromotionSnapshot"][];
            payment: components["schemas"]["PaymentSummary"];
            refund_case: components["schemas"]["RefundCaseSummary"] | null;
            available_actions: components["schemas"]["AvailableActions"];
            /** Format: date-time */
            created_at: string;
            /** Format: date-time */
            updated_at: string;
            /** Format: date-time */
            paid_at?: string | null;
            /** Format: date-time */
            completed_at?: string | null;
            /** Format: date-time */
            cancelled_at?: string | null;
            /**
             * Format: date-time
             * @description Economic-resolution time for master REFUNDED; inspect refund_case.status to distinguish provider success from external remediation.
             */
            refunded_at?: string | null;
        };
        OrderCard: {
            order_id: components["schemas"]["OrderId"];
            /** Format: uuid */
            buyer_id: string;
            buyer_tab: components["schemas"]["BuyerTab"];
            ui_primary_status: components["schemas"]["UiPrimaryStatus"];
            /** @description Sort rank of buyer_tab (TO_PAY=10, TO_SHIP=20, TO_RECEIVE=30, RETURN_REFUND=40, COMPLETED=50, CANCELLED=60, fallback 99). */
            ui_status_rank: number;
            /** Format: date-time */
            created_at: string;
            /** @description Name from the first line ordered by (order_item_id, order_line_id). */
            primary_item_name: string;
            /**
             * Format: uri
             * @description Image from the same line as primary_item_name.
             */
            primary_item_image_url: string | null;
            /** @constant */
            primary_store_name: "PLATFORM";
            /** @description Number of distinct order lines, not warehouse groups. */
            item_count: number;
            /** @description Exactly max(item_count - 1, 0). */
            remaining_item_count: number;
            final_amount: components["schemas"]["Money"];
        };
        OrderSearchPage: {
            items: components["schemas"]["OrderCard"][];
            /** Format: int64 */
            total_orders: number;
            next_cursor: string | null;
        };
        BuyerCancellationRequest: {
            /** @constant */
            reason_code: "USER_CANCEL";
        };
        AdminCancellationRequest: {
            /** @constant */
            reason_code: "ADMIN_FORCE_CANCEL";
        };
        ReturnLineRequest: {
            order_line_id: components["schemas"]["EntityId"];
            /** @description Must equal the immutable complete ordered quantity of this line. */
            quantity: number;
        };
        BuyerReturnRequest: {
            /** @description order_line_id values must be unique; enforced by the provider. */
            lines: components["schemas"]["ReturnLineRequest"][];
            reason_code: string;
            reason_details?: string | null;
        };
        DeliveryLineConfirmation: {
            order_line_id: components["schemas"]["EntityId"];
            /**
             * Format: date-time
             * @description When the goods reached the customer; not before the line's physical dispatch.
             */
            delivered_at: string;
            /** @description Carrier receipt or tracking reference; several lines of one shipment may share it. */
            delivery_reference: string;
        };
        DeliveryConfirmationRequest: {
            /** @description order_line_id values must be unique; enforced by the provider. */
            lines: components["schemas"]["DeliveryLineConfirmation"][];
        };
        DeliveryConfirmationResult: {
            order_id: components["schemas"]["OrderId"];
            order_status: components["schemas"]["OrderStatus"];
            /** @description True when the order is COMPLETED after this call. */
            order_completed: boolean;
            lines: {
                order_line_id: components["schemas"]["EntityId"];
                /** @constant */
                fulfillment_status: "DELIVERED";
                /** Format: date-time */
                delivered_at: string;
                delivery_reference: string;
            }[];
        };
        AdminPreDispatchRefundRequest: {
            reason_code: string;
            reason_details?: string | null;
        };
        CreatePaymentAttemptRequest: {
            /** Format: uri */
            return_url?: string | null;
        };
        PaymentAttempt: {
            order_id: components["schemas"]["OrderId"];
            attempt_number: number;
            payment_transaction_id: components["schemas"]["OpaqueId"];
            amount: components["schemas"]["Money"];
            status: components["schemas"]["PaymentAttemptStatus"];
            /** @description Present only while this is the current usable PENDING attempt. */
            client_secret: string | null;
            /** @description Present only while client_secret is present. */
            publishable_key: string | null;
            /** Format: date-time */
            expires_at: string | null;
            /** Format: date-time */
            created_at: string;
            /** Format: date-time */
            resolved_at?: string | null;
        };
        OperationAccepted: {
            /** Format: uuid */
            operation_id: string;
            operation_type: components["schemas"]["OperationType"];
            order_id: components["schemas"]["OrderId"];
            /** @constant */
            status: "ACCEPTED";
            status_url: string;
            /** Format: date-time */
            created_at: string;
        };
        ReturnOperationAccepted: {
            /** Format: uuid */
            operation_id: string;
            /** @constant */
            operation_type: "BUYER_RETURN";
            order_id: components["schemas"]["OrderId"];
            /** @constant */
            status: "ACCEPTED";
            status_url: string;
            /** Format: date-time */
            created_at: string;
            refund_request_id: components["schemas"]["OpaqueId"];
        };
        OrderOperation: {
            /** Format: uuid */
            operation_id: string;
            operation_type: components["schemas"]["OperationType"];
            order_id: components["schemas"]["OrderId"];
            status: components["schemas"]["OperationStatus"];
            result_reference: string | null;
            failure: components["schemas"]["problem.schema"] | null;
            /** Format: date-time */
            created_at: string;
            /** Format: date-time */
            updated_at: string;
            /** Format: date-time */
            completed_at?: string | null;
        };
        RefundCaseLine: {
            order_line_id: components["schemas"]["EntityId"];
            expected_quantity: number;
            unit_price_snapshot: components["schemas"]["Money"];
            /** @description Immutable proportional largest-remainder share of the order discount for this line. */
            discount_share_snapshot: components["schemas"]["Money"];
            /** @description expected_quantity * unit price minus discount share; refund amount sums this field. */
            net_amount_snapshot: components["schemas"]["Money"];
        };
        RefundCase: {
            order_id: components["schemas"]["OrderId"];
            refund_request_id: components["schemas"]["OpaqueId"];
            /** @enum {string} */
            trigger_source: "API_RETURN" | "ADMIN_FORCE_REFUND";
            mode: components["schemas"]["RefundMode"];
            status: components["schemas"]["RefundCaseStatus"];
            scope_finalization?: components["schemas"]["RefundScopeFinalization"];
            amount: components["schemas"]["Money"];
            lines: components["schemas"]["RefundCaseLine"][];
            refund_transaction_id: components["schemas"]["OpaqueId"] | null;
            /** Format: date-time */
            started_at: string;
            /** Format: date-time */
            updated_at: string;
            /** Format: date-time */
            resolved_at: string | null;
        } & unknown;
        /** @description Sanitized Inventory finalization progress, separate from financial case status. Admin pre-dispatch uses NOT_REQUIRED. Buyer operation remains RUNNING while PENDING, or MANUAL_REVIEW_REQUIRED during RECOVERY_REQUIRED/CONFLICT. Only a matching accepted Inventory command receipt establishes COMPLETED; Payment success alone does not. Reversal never reopens a completed Inventory scope. */
        RefundScopeFinalization: {
            /** @enum {string} */
            status: "NOT_REQUIRED" | "NOT_REQUESTED" | "PENDING" | "RECOVERY_REQUIRED" | "CONFLICT" | "COMPLETED";
            /** @enum {string|null} */
            terminal_scope_state: "CLOSED" | "CLOSED_INCOMPLETE" | "CANCELLED" | null;
            /** Format: date-time */
            finalized_at: string | null;
        } & unknown;
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
        /** @description Malformed input, invalid cursor, range, or header. */
        BadRequest: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Trusted Gateway identity is missing or invalid. */
        Unauthorized: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Authenticated caller lacks the required Admin capability. */
        Forbidden: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Resource does not exist or is not visible to the caller. */
        NotFound: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description FSM state, permanent one-shot guard, lifetime refund uniqueness, or Idempotency-Key payload does not permit this command. */
        Conflict: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Request syntax is valid but selected lines, complete quantities, or another domain value do not match the immutable order snapshot. */
        Unprocessable: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description A required durable dependency is unavailable or an outcome is still unknown. */
        Unavailable: {
            headers: {
                "Retry-After": components["headers"]["RetryAfter"];
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
    };
    parameters: {
        RequestId: string;
        /** @description Same key and canonical payload replay; different payload returns 409. */
        IdempotencyKey: string;
        OrderId: components["schemas"]["OrderId"];
        OperationId: string;
        AttemptNumber: number;
        BuyerIdFilter: string;
        BuyerTab: components["schemas"]["BuyerTab"];
        SearchText: string;
        CreatedFrom: string;
        CreatedTo: string;
        OrderStatuses: components["schemas"]["OrderStatus"][];
        PaymentStatuses: components["schemas"]["PaymentStatus"][];
        MinAmountMinor: number;
        MaxAmountMinor: number;
        /** @description Opaque base64url keyset cursor returned by the previous page. */
        Cursor: string;
        Limit: number;
    };
    requestBodies: never;
    headers: {
        /** @description Order responses contain private transaction data and must not be stored. */
        PrivateNoStore: "private, no-store";
        /** @description Suggested seconds before polling the operation resource. */
        RetryAfter: number;
        /** @description Buyer-scoped operation resource. */
        OperationLocation: string;
        /** @description Admin-scoped operation resource. */
        AdminOperationLocation: string;
    };
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    listMyOrders: {
        parameters: {
            query?: {
                tab?: components["parameters"]["BuyerTab"];
                q?: components["parameters"]["SearchText"];
                created_from?: components["parameters"]["CreatedFrom"];
                created_to?: components["parameters"]["CreatedTo"];
                order_status?: components["parameters"]["OrderStatuses"];
                payment_status?: components["parameters"]["PaymentStatuses"];
                min_amount_minor?: components["parameters"]["MinAmountMinor"];
                max_amount_minor?: components["parameters"]["MaxAmountMinor"];
                /** @description Opaque base64url keyset cursor returned by the previous page. */
                cursor?: components["parameters"]["Cursor"];
                limit?: components["parameters"]["Limit"];
            };
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description One order-level card per matching order. */
            200: {
                headers: {
                    "Cache-Control": components["headers"]["PrivateNoStore"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OrderSearchPage"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            503: components["responses"]["Unavailable"];
        };
    };
    getMyOrder: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                order_id: components["parameters"]["OrderId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Denormalized immutable order snapshot and current lifecycle state. */
            200: {
                headers: {
                    "Cache-Control": components["headers"]["PrivateNoStore"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OrderDetail"];
                };
            };
            401: components["responses"]["Unauthorized"];
            404: components["responses"]["NotFound"];
            503: components["responses"]["Unavailable"];
        };
    };
    requestMyOrderCancellation: {
        parameters: {
            query?: never;
            header: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
                /** @description Same key and canonical payload replay; different payload returns 409. */
                "Idempotency-Key": components["parameters"]["IdempotencyKey"];
            };
            path: {
                order_id: components["parameters"]["OrderId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["BuyerCancellationRequest"];
            };
        };
        responses: {
            /** @description Command durably accepted for asynchronous processing. */
            202: {
                headers: {
                    Location: components["headers"]["OperationLocation"];
                    "Retry-After": components["headers"]["RetryAfter"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OperationAccepted"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            404: components["responses"]["NotFound"];
            409: components["responses"]["Conflict"];
            503: components["responses"]["Unavailable"];
        };
    };
    requestMyOrderReturn: {
        parameters: {
            query?: never;
            header: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
                /** @description Same key and canonical payload replay; different payload returns 409. */
                "Idempotency-Key": components["parameters"]["IdempotencyKey"];
            };
            path: {
                order_id: components["parameters"]["OrderId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["BuyerReturnRequest"];
            };
        };
        responses: {
            /** @description Return scope and lifetime refund case were durably accepted. */
            202: {
                headers: {
                    Location: components["headers"]["OperationLocation"];
                    "Retry-After": components["headers"]["RetryAfter"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ReturnOperationAccepted"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            404: components["responses"]["NotFound"];
            409: components["responses"]["Conflict"];
            422: components["responses"]["Unprocessable"];
            503: components["responses"]["Unavailable"];
        };
    };
    getMyOrderRefundCase: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                order_id: components["parameters"]["OrderId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Current refund case and immutable selected-line scope. */
            200: {
                headers: {
                    "Cache-Control": components["headers"]["PrivateNoStore"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RefundCase"];
                };
            };
            401: components["responses"]["Unauthorized"];
            404: components["responses"]["NotFound"];
            503: components["responses"]["Unavailable"];
        };
    };
    createMyOrderPaymentAttempt: {
        parameters: {
            query?: never;
            header: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
                /** @description Same key and canonical payload replay; different payload returns 409. */
                "Idempotency-Key": components["parameters"]["IdempotencyKey"];
            };
            path: {
                order_id: components["parameters"]["OrderId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreatePaymentAttemptRequest"];
            };
        };
        responses: {
            /** @description Durable request accepted, or idempotent replay of the same operation. */
            202: {
                headers: {
                    Location: components["headers"]["OperationLocation"];
                    "Retry-After": components["headers"]["RetryAfter"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OperationAccepted"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            404: components["responses"]["NotFound"];
            409: components["responses"]["Conflict"];
            503: components["responses"]["Unavailable"];
        };
    };
    getMyOrderPaymentAttempt: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                order_id: components["parameters"]["OrderId"];
                attempt_number: components["parameters"]["AttemptNumber"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Payment attempt state and client data when still usable. */
            200: {
                headers: {
                    "Cache-Control": components["headers"]["PrivateNoStore"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PaymentAttempt"];
                };
            };
            401: components["responses"]["Unauthorized"];
            404: components["responses"]["NotFound"];
            503: components["responses"]["Unavailable"];
        };
    };
    getMyOrderOperation: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                operation_id: components["parameters"]["OperationId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Current durable command-processing state. */
            200: {
                headers: {
                    "Cache-Control": components["headers"]["PrivateNoStore"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OrderOperation"];
                };
            };
            401: components["responses"]["Unauthorized"];
            404: components["responses"]["NotFound"];
            503: components["responses"]["Unavailable"];
        };
    };
    listOrdersAsAdmin: {
        parameters: {
            query?: {
                buyer_id?: components["parameters"]["BuyerIdFilter"];
                tab?: components["parameters"]["BuyerTab"];
                q?: components["parameters"]["SearchText"];
                created_from?: components["parameters"]["CreatedFrom"];
                created_to?: components["parameters"]["CreatedTo"];
                order_status?: components["parameters"]["OrderStatuses"];
                payment_status?: components["parameters"]["PaymentStatuses"];
                min_amount_minor?: components["parameters"]["MinAmountMinor"];
                max_amount_minor?: components["parameters"]["MaxAmountMinor"];
                /** @description Opaque base64url keyset cursor returned by the previous page. */
                cursor?: components["parameters"]["Cursor"];
                limit?: components["parameters"]["Limit"];
            };
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Admin-visible order cards. */
            200: {
                headers: {
                    "Cache-Control": components["headers"]["PrivateNoStore"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OrderSearchPage"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            503: components["responses"]["Unavailable"];
        };
    };
    getOrderAsAdmin: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                order_id: components["parameters"]["OrderId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Full Order-owned snapshot and current lifecycle state. */
            200: {
                headers: {
                    "Cache-Control": components["headers"]["PrivateNoStore"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OrderDetail"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            503: components["responses"]["Unavailable"];
        };
    };
    requestOrderCancellationAsAdmin: {
        parameters: {
            query?: never;
            header: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
                /** @description Same key and canonical payload replay; different payload returns 409. */
                "Idempotency-Key": components["parameters"]["IdempotencyKey"];
            };
            path: {
                order_id: components["parameters"]["OrderId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AdminCancellationRequest"];
            };
        };
        responses: {
            /** @description Pre-payment cancellation durably accepted. */
            202: {
                headers: {
                    Location: components["headers"]["AdminOperationLocation"];
                    "Retry-After": components["headers"]["RetryAfter"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OperationAccepted"];
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
    requestPreDispatchRefundAsAdmin: {
        parameters: {
            query?: never;
            header: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
                /** @description Same key and canonical payload replay; different payload returns 409. */
                "Idempotency-Key": components["parameters"]["IdempotencyKey"];
            };
            path: {
                order_id: components["parameters"]["OrderId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AdminPreDispatchRefundRequest"];
            };
        };
        responses: {
            /** @description Admin pre-dispatch release attempt durably accepted. */
            202: {
                headers: {
                    Location: components["headers"]["AdminOperationLocation"];
                    "Retry-After": components["headers"]["RetryAfter"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OperationAccepted"];
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
    confirmDeliveryAsAdmin: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                order_id: components["parameters"]["OrderId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["DeliveryConfirmationRequest"];
            };
        };
        responses: {
            /** @description Every requested line is DELIVERED (newly or already, with the same record). */
            200: {
                headers: {
                    "Cache-Control": components["headers"]["PrivateNoStore"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["DeliveryConfirmationResult"];
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
    getOrderOperationAsAdmin: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                operation_id: components["parameters"]["OperationId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Current durable command-processing state. */
            200: {
                headers: {
                    "Cache-Control": components["headers"]["PrivateNoStore"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OrderOperation"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            503: components["responses"]["Unavailable"];
        };
    };
}
