/**
 * FILE SINH TỰ ĐỘNG — KHÔNG SỬA TAY.
 * Nguồn: contracts/http/product-detail-bff/v1/openapi.yaml
 * Sinh lại bằng `scripts/node.sh npm run gen:api`.
 */
export interface paths {
    "/api/v1/storefront/products/{slug}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Lấy Product Detail cho buyer */
        get: operations["getProductDetail"];
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
        Problem: components["schemas"]["problem.schema"] & {
            /** @enum {string} */
            code?: "PRODUCT_DETAIL_NOT_FOUND" | "PRODUCT_DETAIL_UNAVAILABLE";
        };
        ProductDetailResponse: {
            product_id: string;
            name: string;
            slug: string;
            description: string | null;
            /** @description Revision của Product aggregate. Không bao gồm category và nhãn thuộc tính, nên không đủ để làm cache key hay version của toàn response. */
            catalog_revision: number;
            category: components["schemas"]["ProductDetailCategory"];
            /** @description Bảng thông số (thuộc tính không biến thể), giữ nguyên thứ tự Product trả: theo tên bằng collation tiếng Việt, tie-break code. */
            attributes: components["schemas"]["ProductDetailAttribute"][];
            /** @description Trục của bộ chọn biến thể, giữ nguyên thứ tự Product trả. Mọi giá trị trong skus[].variant_attributes có đúng một option tương ứng. */
            variant_axes: components["schemas"]["ProductDetailVariantAxis"][];
            /** @description Gallery phẳng: tối đa 20 media cấp Product (sku_id null) và 10 media mỗi SKU. Gallery của SKU đang chọn là media có sku_id đó, fallback media cấp Product. */
            media: components["schemas"]["ProductDetailMedia"][];
            skus: components["schemas"]["ProductDetailSku"][];
            review_section: components["schemas"]["ProductDetailReviewSection"];
        };
        AttributeScalar: string | number | boolean;
        /** @enum {string} */
        AttributeValueType: "TEXT" | "NUMBER" | "BOOLEAN" | "SELECT";
        ProductDetailCategoryRef: {
            category_id: string;
            name: string;
            slug: string;
        };
        ProductDetailCategory: {
            category_id: string;
            name: string;
            slug: string;
            /** @description Breadcrumb từ category gốc tới category của Product (phần tử cuối). */
            path: components["schemas"]["ProductDetailCategoryRef"][];
        };
        ProductDetailAttribute: {
            code: string;
            name: string;
            value_type: components["schemas"]["AttributeValueType"];
            value: components["schemas"]["AttributeScalar"];
            /** @description Label option khi SELECT; null với kiểu khác, khi đó client hiển thị value. Chưa có đơn vị đo. */
            value_label: string | null;
        };
        ProductDetailVariantAxis: {
            code: string;
            name: string;
            value_type: components["schemas"]["AttributeValueType"];
            options: components["schemas"]["ProductDetailVariantOption"][];
        };
        ProductDetailVariantOption: {
            value: components["schemas"]["AttributeScalar"];
            /** @description Label option khi trục là SELECT; null với kiểu khác. */
            label: string | null;
        };
        ProductDetailMedia: {
            media_id: string;
            /** @description Null là media cấp Product; khác null luôn là một SKU có trong skus[]. */
            sku_id: string | null;
            /** @enum {string} */
            media_type: "IMAGE" | "VIDEO";
            /**
             * Format: uri
             * @description Public CDN URL do BFF dựng từ storage key; không trả internal storage key.
             */
            url: string;
            alt_text: string | null;
            sort_order: number;
        };
        ProductDetailSku: {
            sku_id: string;
            sku_code: string;
            /** @description Map attribute code → value; nhãn hiển thị lấy từ variant_axes. */
            variant_attributes: {
                [key: string]: components["schemas"]["AttributeScalar"];
            };
            /**
             * Format: uri
             * @description Public CDN URL dựng từ thumbnail hiệu lực của Product, hoặc URL placeholder cấu hình khi SKU không có ảnh; không trả internal storage key.
             */
            thumbnail_url: string;
            base_price_vnd: number;
            selling_price_vnd: number;
            availability_status: components["schemas"]["AvailabilityStatus"];
            /**
             * Format: date-time
             * @description Thời điểm Inventory xác nhận UI state; null khi UNKNOWN và không phải thời điểm reserve.
             */
            availability_observed_at?: string | null;
        };
        /**
         * @description Advisory UI state, không phải reservation. Storefront cho thêm vào giỏ khi IN_STOCK hoặc UNKNOWN, tắt khi OUT_OF_STOCK (PDP-BR-009). UNKNOWN nghĩa là Inventory không cho trạng thái tin cậy, không có nghĩa là hết hàng.
         * @enum {string}
         */
        AvailabilityStatus: "IN_STOCK" | "OUT_OF_STOCK" | "UNKNOWN";
        ProductDetailReviewSection: {
            /**
             * @description UNAVAILABLE là lỗi/timeout dependency, không có nghĩa chưa có review.
             * @enum {string}
             */
            status: "AVAILABLE" | "UNAVAILABLE";
            /** @description Bắt buộc có khi status AVAILABLE; null khi UNAVAILABLE. */
            summary: components["schemas"]["ProductReviewSummary"] | null;
            /** @description Rỗng khi chưa có review hoặc khi section UNAVAILABLE. */
            preview: components["schemas"]["ProductReviewPreviewItem"][];
        };
        ProductReviewSummary: {
            average_rating: number;
            total_reviews: number;
            total_with_media: number;
        };
        ProductReviewPreviewItem: {
            /** Format: uuid */
            review_id: string;
            rating: number;
            content?: string | null;
            author_name: string;
            variant_name: string | null;
            has_media: boolean;
            /** Format: date-time */
            created_at: string;
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
    responses: never;
    parameters: never;
    requestBodies: never;
    headers: {
        /** @description no-store khi có ít nhất một SKU UNKNOWN hoặc review_section.status là UNAVAILABLE. Response đầy đủ theo storefront cache policy nếu cache được bật, mặc định no-store. */
        ProductDetailCacheControl: string;
    };
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    getProductDetail: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description Cùng quy tắc slug của Product (PRODUCT-BR-013). Slug không khớp pattern hoặc dài hơn 180 trả 404 mà BFF không gọi Product. */
                slug: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Catalog snapshot kèm availability và Review enrichment. Response degrade (có SKU UNKNOWN hoặc review_section UNAVAILABLE) vẫn là 200. */
            200: {
                headers: {
                    "Cache-Control": components["headers"]["ProductDetailCacheControl"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ProductDetailResponse"];
                };
            };
            /** @description PRODUCT_DETAIL_NOT_FOUND; Product không tồn tại, không catalog-active hoặc slug không khớp pattern. retryable=false. Luôn no-store để CDN không giữ 404 sau khi Product được activate (404 có thể bị cache heuristic theo RFC 9111). */
            404: {
                headers: {
                    "Cache-Control"?: "no-store";
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description PRODUCT_DETAIL_UNAVAILABLE; Product buyer-read dependency không cho kết quả hợp lệ (timeout, lỗi kết nối, 5xx, 4xx, sai contract), circuit breaker của Product đang mở, bulkhead Product đầy hoặc deadline PDP còn dưới 100 ms trước khi gọi Product. retryable=true. Inventory/Review lỗi không tạo 503. */
            503: {
                headers: {
                    "Cache-Control"?: "no-store";
                    /** @description Chỉ có khi circuit breaker của Product đang mở; số giây mở còn lại, làm tròn lên. */
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
}
