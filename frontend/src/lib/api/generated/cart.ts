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
         * @description CHECKOUT only. Reads the same Redis source of truth as the public Cart API but returns only identifiers and quantities. It does not call Product, expose Redis keys, refresh TTL, or mutate cart lifecycle state. Redis miss returns an empty items array.
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
            /**
             * Format: uri
             * @description Absolute CDN URL built by Cart from the Product image storage key, or the configured placeholder when the SKU has no image. Never a storage key.
             */
            image_url?: string;
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
        /** @description Request không hợp lệ. */
        BadRequest: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Thiếu trusted identity. */
        Unauthorized: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Authenticated workload is not Checkout Service. */
        Forbidden: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Key dùng với payload khác hoặc request cùng key đang xử lý. */
        IdempotencyConflict: {
            headers: {
                /** @description Có khi request cùng key đang xử lý. */
                "Retry-After"?: number;
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Rule Cart hoặc Product validation không thỏa. */
        Unprocessable: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Vượt rate limit theo user. */
        RateLimited: {
            headers: {
                "Retry-After"?: number;
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Redis Cart source of truth không khả dụng. */
        CartStoreUnavailable: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Redis hoặc Product dependency cần cho write không khả dụng. */
        WriteDependencyUnavailable: {
            headers: {
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
        UserId: string;
    };
    requestBodies: never;
    headers: never;
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
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
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
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            503: components["responses"]["CartStoreUnavailable"];
        };
    };
}
