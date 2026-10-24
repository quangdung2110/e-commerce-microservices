/**
 * FILE SINH TỰ ĐỘNG — KHÔNG SỬA TAY.
 * Nguồn: contracts/http/cart/v1/openapi.yaml
 * Sinh lại bằng `scripts/node.sh npm run gen:api`.
 */
export interface paths {
    "/api/v1/carts/mine": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Lấy cart của user hiện tại
         * @description Redis miss trả cart rỗng. Cart enrich giá và availability từ Product Service; khi Product không khả dụng, response vẫn thành công nhưng catalog_freshness là DEGRADED và current_price có thể null.
         */
        get: operations["getMyCart"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/carts/mine/items": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Thêm SKU hoặc điều chỉnh quantity theo delta
         * @description delta dương thêm/tăng, delta âm giảm. Delta xử lý nguyên tử trong Redis, nên request đến lệch thứ tự không ghi đè quantity tuyệt đối. Khi kết quả không còn quantity dương, client phải gọi DELETE thay vì gửi delta âm thêm. SKU mới hoặc validation catalog stale mới cần Product Service.
         */
        post: operations["adjustCartItem"];
        /**
         * Xóa tối đa 100 SKU khỏi cart
         * @description SKU không tồn tại bị bỏ qua. Nếu không còn item, Cart xóa state và abandonment schedule Redis.
         */
        delete: operations["deleteCartItems"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/internal/v1/carts/{user_id}/checkout-snapshot": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Resolve the authoritative Cart quantity snapshot for Checkout
         * @description CHECKOUT only. Reads the same Redis source of truth as the public Cart API but returns only identifiers and quantities. It does not call Product, expose Redis keys, refresh TTL, or mutate cart lifecycle state. Redis miss returns an empty items array. Items are ordered by sku_id. The internal route is not rate limited.
         *     The mesh authenticates the workload principal; Cart additionally requires `X-Service-Identity` to be on its allowlist (`cart.internal_callers`, default `checkout-service`) as defence in depth: a missing header is 401, any other service is 403, a non-UUID `user_id` is 400.
         */
        get: operations["getCheckoutCartSnapshot"];
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
        Money: components["schemas"]["money.schema"];
        Problem: components["schemas"]["problem.schema"];
        AdjustItemRequest: {
            sku_id: string;
            /** @description Lượng thay đổi tương đối; không phải quantity tuyệt đối. */
            delta: number;
        };
        DeleteItemsRequest: {
            sku_ids: string[];
        };
        CartMutationResult: {
            sku_id: string;
            quantity: number;
            created: boolean;
            /** Format: date-time */
            updated_at: string;
        };
        DeleteItemsResult: {
            deleted_count: number;
        };
        /** @enum {string} */
        CatalogFreshness: "CURRENT" | "DEGRADED";
        Cart: {
            items: components["schemas"]["CartItem"][];
            total_unique_skus: number;
            total_units: number;
            catalog_freshness: components["schemas"]["CatalogFreshness"];
        };
        CartItem: {
            sku_id: string;
            product_id: string;
            product_name: string;
            variant_attributes?: {
                [key: string]: string;
            };
            /** @description Product-resolved Vietnamese display text (attribute names and display values). CURRENT uses the current Product read; DEGRADED or unavailable SKU falls back to the stored display snapshot. GET never persists that refresh. Optional for legacy carts/providers. Consumers without a label display raw values only, not attribute codes. Display only; variant_attributes remains unchanged. */
            variant_name?: string;
            /**
             * Format: uri
             * @description Absolute CDN URL built by Cart from the Product image storage key, or the configured placeholder when the SKU has no image. Never a storage key.
             */
            image_url: string;
            quantity: number;
            unit_price_at_add: components["schemas"]["money.schema"];
            /** @description Null khi Product Service không trả được giá hiện tại. */
            current_price: components["schemas"]["money.schema"] | null;
            /** @description Catalog saleability (Product và SKU ACTIVE), không phải stock Inventory. Giá trị CURRENT khi catalog_freshness=CURRENT, snapshot gần nhất khi DEGRADED. */
            is_available: boolean;
            /** Format: date-time */
            added_at: string;
            /** Format: date-time */
            updated_at: string;
        };
        CheckoutCartSnapshot: {
            items: components["schemas"]["CheckoutCartItem"][];
        };
        CheckoutCartItem: {
            product_id: string;
            sku_id: string;
            quantity: number;
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
        /** @description Request không hợp lệ (`code` = `VALIDATION_FAILED`, kèm `errors[]` theo field): thiếu hoặc sai `Idempotency-Key`, body sai schema, `delta` bằng 0 hoặc ngoài -100..100, `sku_ids` rỗng/trùng/quá 100, hoặc `user_id` của route nội bộ không phải UUID. */
        BadRequest: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Thiếu trusted identity (`X-User-Id` ở route public, `X-Service-Identity` ở route nội bộ); `code` = `UNAUTHENTICATED`. */
        Unauthorized: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description `X-Service-Identity` không nằm trong allowlist `cart.internal_callers` (`code` = `FORBIDDEN`). */
        Forbidden: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description `IDEMPOTENCY_CONFLICT`: cùng `Idempotency-Key` đã được dùng với payload khác. Ghi của Cart chạy nguyên tử trong Redis Lua nên không có trạng thái "đang xử lý": cùng key và cùng payload luôn trả lại kết quả đã lưu, không bao giờ trả 409. */
        IdempotencyConflict: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Rule Cart hoặc Product validation không thỏa. `code` là một trong `CART_FULL` (SKU mới vượt 50 SKU), `PRODUCT_NOT_AVAILABLE` (Product/SKU không active hoặc không tồn tại), `QUANTITY_INVALID` (delta làm quantity không còn dương; dùng DELETE), `ITEM_NOT_FOUND` (delta âm cho SKU không có trong cart). */
        Unprocessable: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Vượt rate limit theo user (`code` = `RATE_LIMITED`, `retryable` = true). */
        RateLimited: {
            headers: {
                "Retry-After"?: number;
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Redis Cart source of truth không khả dụng (`code` = `CART_STORE_UNAVAILABLE`, `retryable` = true). */
        CartStoreUnavailable: {
            headers: {
                "Retry-After": components["headers"]["RetryAfter"];
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Redis hoặc Product dependency cần cho write không khả dụng (`code` = `CART_STORE_UNAVAILABLE` hoặc `PRODUCT_UNAVAILABLE`, `retryable` = true). */
        WriteDependencyUnavailable: {
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
        IdempotencyKey: string;
        /** @description Calling service identity. Must be in the Cart allowlist (`cart.internal_callers`, default `checkout-service`); a missing header is 401 and any other value is 403. */
        ServiceIdentity: string;
        UserId: string;
    };
    requestBodies: never;
    headers: {
        /** @description Seconds to wait before repeating the request. Present on every 429 and on every 503 with retryable=true (contracts ADR-005); a 503 with retryable=false does not carry it. */
        RetryAfter: number;
    };
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    getMyCart: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Cart snapshot; dữ liệu cá nhân, không được shared-cache. */
            200: {
                headers: {
                    "Cache-Control"?: "no-store";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Cart"];
                };
            };
            401: components["responses"]["Unauthorized"];
            429: components["responses"]["RateLimited"];
            503: components["responses"]["CartStoreUnavailable"];
        };
    };
    adjustCartItem: {
        parameters: {
            query?: never;
            header: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
                "Idempotency-Key": components["parameters"]["IdempotencyKey"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AdjustItemRequest"];
            };
        };
        responses: {
            /** @description SKU đã tồn tại và quantity đã thay đổi. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CartMutationResult"];
                };
            };
            /** @description SKU mới được thêm vào cart. */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CartMutationResult"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            409: components["responses"]["IdempotencyConflict"];
            422: components["responses"]["Unprocessable"];
            429: components["responses"]["RateLimited"];
            503: components["responses"]["WriteDependencyUnavailable"];
        };
    };
    deleteCartItems: {
        parameters: {
            query?: never;
            header: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
                "Idempotency-Key": components["parameters"]["IdempotencyKey"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["DeleteItemsRequest"];
            };
        };
        responses: {
            /** @description Kết quả xóa logic. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["DeleteItemsResult"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            409: components["responses"]["IdempotencyConflict"];
            429: components["responses"]["RateLimited"];
            503: components["responses"]["CartStoreUnavailable"];
        };
    };
    getCheckoutCartSnapshot: {
        parameters: {
            query?: never;
            header: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
                /** @description Calling service identity. Must be in the Cart allowlist (`cart.internal_callers`, default `checkout-service`); a missing header is 401 and any other value is 403. */
                "X-Service-Identity": components["parameters"]["ServiceIdentity"];
            };
            path: {
                user_id: components["parameters"]["UserId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Minimal server-to-server Cart snapshot. */
            200: {
                headers: {
                    "Cache-Control"?: "private, no-store";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CheckoutCartSnapshot"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            503: components["responses"]["CartStoreUnavailable"];
        };
    };
}
