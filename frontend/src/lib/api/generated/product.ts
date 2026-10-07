/**
 * FILE SINH TỰ ĐỘNG — KHÔNG SỬA TAY.
 * Nguồn: contracts/http/product/v1/openapi.yaml
 * Sinh lại bằng `scripts/node.sh npm run gen:api`.
 */
export interface paths {
    "/api/v1/products": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Liệt kê Product cho màn hình quản trị */
        get: operations["listProducts"];
        put?: never;
        /**
         * Tạo Product DRAFT cùng ít nhất một SKU
         * @description Command tạo Product, SKU và media trong một transaction. Server sinh UUIDv7. Operation này không idempotent vì target schema không có idempotency store; client gặp timeout phải GET/tra cứu theo slug trước khi gửi command mới.
         */
        post: operations["createProduct"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/products/{product_id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Lấy Product aggregate cho quản trị */
        get: operations["getProduct"];
        /**
         * Thay toàn bộ phần Product-level của aggregate
         * @description Chỉ thay category, nội dung và thuộc tính Product-level; SKU và media có resource riêng. If-Match là ETag từ lần GET/command thành công gần nhất, để tránh admin ghi đè thay đổi đồng thời, bao gồm repricing markdown.
         */
        put: operations["replaceProduct"];
        post?: never;
        /**
         * Hard delete Product
         * @description Server chụp Product/SKU ID và ghi PRODUCT_DELETED tombstone vào outbox trước khi xóa. Đây không phải soft delete và không thể khôi phục qua API.
         */
        delete: operations["deleteProduct"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/products/{product_id}/status": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        /**
         * Activate, hide hoặc show Product
         * @description Chỉ ADMIN quyết định lifecycle. ACTIVE yêu cầu tối thiểu một SKU ACTIVE; không có guard liên quan tồn kho. Gửi target status trùng trạng thái hiện tại là INVALID_STATE_TRANSITION (409), không phải no-op.
         */
        patch: operations["changeProductStatus"];
        trace?: never;
    };
    "/api/v1/products/{product_id}/skus": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Thêm SKU vào Product
         * @description Server tính selling_price_vnd từ base_price_vnd và markdown projection hiện hành. Client không được gửi selling price hoặc dữ liệu tồn kho.
         */
        post: operations["createSku"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/products/{product_id}/skus/{sku_id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Thay toàn bộ SKU
         * @description Mọi thay đổi base_price_vnd được repricing trong cùng transaction. SKU INACTIVE không làm Product HIDDEN tự động.
         */
        put: operations["replaceSku"];
        post?: never;
        /** Hard delete một SKU không phải SKU cuối cùng */
        delete: operations["deleteSku"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/products/{product_id}/media": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Thêm media cấp Product hoặc gắn với một SKU thuộc Product */
        post: operations["createProductMedia"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/products/{product_id}/media/{media_id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /** Thay toàn bộ metadata media */
        put: operations["replaceProductMedia"];
        post?: never;
        /** Hard delete media */
        delete: operations["deleteProductMedia"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/categories": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Liệt kê category */
        get: operations["listCategories"];
        put?: never;
        /** Tạo category */
        post: operations["createCategory"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/categories/{category_id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Lấy category cùng mapping attribute */
        get: operations["getCategory"];
        /**
         * Thay toàn bộ category
         * @description Parent mới không được tạo cycle; moving category không tự thay đổi Product, nhưng ghi CATALOG_REINDEX_REQUESTED để Search rebuild path dẫn xuất.
         */
        put: operations["replaceCategory"];
        post?: never;
        /**
         * Hard delete category rỗng
         * @description Bị từ chối khi category còn category con hoặc Product.
         */
        delete: operations["deleteCategory"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/categories/{category_id}/attributes": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Liệt kê attribute được gán vào category */
        get: operations["listCategoryAttributes"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/categories/{category_id}/attributes/{attribute_id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Tạo hoặc thay mapping category–attribute
         * @description PUT idempotent ở cấp resource mapping. Implementation phải từ chối thay đổi mapping làm Product hiện hữu trong category không còn hợp lệ. Mutation thành công tăng category_revision và phát category snapshot.
         */
        put: operations["putCategoryAttribute"];
        post?: never;
        /** Bỏ mapping category–attribute */
        delete: operations["deleteCategoryAttribute"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/attributes": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Liệt kê attribute cấu hình */
        get: operations["listAttributes"];
        put?: never;
        /** Tạo attribute cấu hình */
        post: operations["createAttribute"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/attributes/{attribute_id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Lấy attribute cùng option */
        get: operations["getAttribute"];
        /**
         * Thay code, name và value type của attribute
         * @description Không được đổi value_type khi option/mapping/Product hiện hữu khiến dữ liệu cũ không hợp lệ; server trả ATTRIBUTE_IN_USE (409).
         */
        put: operations["replaceAttribute"];
        post?: never;
        /** Hard delete attribute không còn được tham chiếu */
        delete: operations["deleteAttribute"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/attributes/{attribute_id}/options": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Thêm option cho attribute SELECT */
        post: operations["createAttributeOption"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/attributes/{attribute_id}/options/{option_id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /** Thay toàn bộ option SELECT */
        put: operations["replaceAttributeOption"];
        post?: never;
        /** Hard delete option không còn được Product tham chiếu */
        delete: operations["deleteAttributeOption"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/internal/v1/products/export": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Export keyset hữu hạn cho Search full reindex
         * @description Chỉ Search Service gọi qua mTLS. Khi mở export session, Product chụp offset hiện tại của mọi partition t.product.events trước khi scan; mọi trang trong session trả cùng replay_from_offsets. Cursor đầu tiên chụp upper bound UUIDv7, sau đó các trang đi theo product_id tăng dần đến bound này; không có duplicate hoặc product mới vô hạn làm export không kết thúc. Search import xong toàn bộ snapshot rồi seek inclusive từ replay_from_offsets để bắt update/delete phát sinh trong lúc export.
         */
        get: operations["exportProductsForReindex"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/internal/v1/products/{product_id}/snapshot": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Lấy snapshot mới nhất của một Product cho Search rebuild */
        get: operations["getProductSnapshotForReindex"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/internal/v1/product-details/{slug}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Lấy catalog Product Detail cho Product Detail BFF
         * @description Chỉ Product Detail BFF gọi qua mTLS. Response là projection buyer đã resolve (ADR-018): Product ACTIVE, chỉ SKU ACTIVE, category kèm breadcrumb path, thuộc tính không biến thể kèm tên/nhãn, trục biến thể kèm option đang dùng, và media giới hạn 20 cấp Product + 10 mỗi SKU. Không chứa quantity, stock availability, reservation, stock revision hay stock timestamp. Nhãn category/attribute được resolve tại thời điểm đọc và không thuộc Product aggregate, nên response không mang ETag.
         */
        get: operations["getBuyerProductDetail"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/internal/v1/categories/export": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Export keyset category tree cho Search full reindex
         * @description Chỉ Search Service gọi qua mTLS. Khi mở export session, Product chụp offset hiện tại của mọi partition t.category.events trước khi scan; mọi trang trong session trả cùng replay_from_offsets. Mỗi item có path và is_leaf dẫn xuất tại thời điểm đọc; Search import export này trước Product export rồi seek inclusive từ replay_from_offsets trong pha Catch-up.
         */
        get: operations["exportCategoriesForReindex"];
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
        /**
         * Format: uuid
         * @description UUIDv7 do Product Service sinh; consumer phải coi ID là opaque.
         */
        Uuid: string;
        NonEmptyText: string;
        /** @description Slug Product dạng kebab-case ASCII chữ thường (PRODUCT-BR-013). Admin gửi slug sai pattern nhận 400; Product Detail BFF dùng cùng pattern để trả 404 mà không gọi Product. */
        Slug: string;
        /** @enum {string} */
        ProductStatus: "DRAFT" | "ACTIVE" | "HIDDEN";
        /** @enum {string} */
        SkuStatus: "ACTIVE" | "INACTIVE";
        /** @enum {string} */
        AttributeValueType: "TEXT" | "NUMBER" | "BOOLEAN" | "SELECT";
        /** @enum {string} */
        MediaType: "IMAGE" | "VIDEO";
        /** @description Giá trị scalar. Key phải là attribute code thuộc category; application kiểm tra type theo attribute config và với SELECT kiểm tra option code. */
        AttributeValue: string | number | boolean;
        /** @description attributes dùng các mapping is_variant=false; variant_attributes dùng các mapping is_variant=true. Required mapping phải có giá trị hợp lệ. */
        AttributeValues: {
            [key: string]: components["schemas"]["AttributeValue"];
        };
        Media: {
            media_id: components["schemas"]["Uuid"];
            product_id: components["schemas"]["Uuid"];
            /** @description Null là media cấp Product; giá trị khác null phải là SKU của product_id. */
            sku_id?: components["schemas"]["Uuid"] | null;
            media_type: components["schemas"]["MediaType"];
            storage_key: string;
            alt_text?: string | null;
            sort_order: number;
            /** Format: date-time */
            created_at: string;
            /** Format: date-time */
            updated_at: string;
        };
        Sku: {
            sku_id: components["schemas"]["Uuid"];
            product_id: components["schemas"]["Uuid"];
            sku_code: string;
            /** Format: int64 */
            base_price_vnd: number;
            /**
             * Format: int64
             * @description Giá VND Product công bố sau markdown; admin không được gửi field này.
             */
            readonly selling_price_vnd: number;
            status: components["schemas"]["SkuStatus"];
            variant_attributes: components["schemas"]["AttributeValues"];
            media: components["schemas"]["Media"][];
            /** @description Thumbnail hiệu lực do Product resolve: IMAGE của chính SKU có sort_order nhỏ nhất, fallback IMAGE cấp Product có sort_order nhỏ nhất, còn lại null. Giá trị khớp sku.thumbnail_storage_key trong sku-upserted nên full reindex từ export và luồng event cho ra cùng kết quả; consumer không tự derive từ media[]. */
            readonly thumbnail_storage_key: string | null;
            /** Format: date-time */
            created_at: string;
            /** Format: date-time */
            updated_at: string;
        };
        Product: {
            product_id: components["schemas"]["Uuid"];
            category_id: components["schemas"]["Uuid"];
            name: string;
            slug: components["schemas"]["Slug"];
            description?: string | null;
            attributes: components["schemas"]["AttributeValues"];
            status: components["schemas"]["ProductStatus"];
            /**
             * Format: int64
             * @description Version ordering guard của toàn Product aggregate.
             */
            readonly catalog_revision: number;
            media: components["schemas"]["Media"][];
            skus: components["schemas"]["Sku"][];
            /** Format: date-time */
            created_at: string;
            /** Format: date-time */
            updated_at: string;
        };
        ProductSummary: {
            product_id: components["schemas"]["Uuid"];
            category_id: components["schemas"]["Uuid"];
            name: string;
            slug: components["schemas"]["Slug"];
            status: components["schemas"]["ProductStatus"];
            /** Format: int64 */
            catalog_revision: number;
            /** Format: date-time */
            updated_at: string;
        };
        ProductPage: {
            items: components["schemas"]["ProductSummary"][];
            next_cursor?: string;
        };
        /** @description Projection buyer của Product ACTIVE cho Product Detail BFF (ADR-018). Chỉ catalog_revision là version của Product aggregate; category và nhãn attribute/option phản ánh trạng thái hiện hành lúc đọc. */
        BuyerProductDetail: {
            product_id: components["schemas"]["Uuid"];
            name: string;
            slug: components["schemas"]["Slug"];
            description: string | null;
            /** Format: int64 */
            catalog_revision: number;
            /** Format: date-time */
            updated_at: string;
            category: components["schemas"]["BuyerCategory"];
            /** @description Thuộc tính không biến thể (mapping is_variant=false) có giá trị trong Product.attributes. Sắp theo name bằng collation tiếng Việt, tie-break code. */
            attributes: components["schemas"]["BuyerAttributeValue"][];
            /** @description Mỗi mapping is_variant=true có ít nhất một SKU ACTIVE mang giá trị. Sắp như attributes. Mọi giá trị trong skus[].variant_attributes có đúng một option tương ứng ở đây. */
            variant_axes: components["schemas"]["BuyerVariantAxis"][];
            /** @description Danh sách phẳng: tối đa 20 media cấp Product (sku_id null) và tối đa 10 media cho mỗi SKU ACTIVE, mỗi nhóm lấy theo sort_order rồi media_id tăng dần. Sắp media cấp Product trước, rồi theo sku_id, sort_order, media_id. Không có media của SKU INACTIVE. */
            media: components["schemas"]["BuyerMedia"][];
            /** @description Chỉ SKU ACTIVE, sắp theo sku_code. */
            skus: components["schemas"]["BuyerSku"][];
        };
        BuyerCategoryRef: {
            category_id: components["schemas"]["Uuid"];
            name: string;
            slug: string;
        };
        BuyerCategory: {
            category_id: components["schemas"]["Uuid"];
            name: string;
            slug: string;
            /** @description Breadcrumb từ category gốc tới chính category lá của Product (phần tử cuối). */
            path: components["schemas"]["BuyerCategoryRef"][];
        };
        BuyerAttributeValue: {
            code: string;
            name: string;
            value_type: components["schemas"]["AttributeValueType"];
            value: components["schemas"]["AttributeValue"];
            /** @description Label của option khi value_type là SELECT; null với TEXT/NUMBER/BOOLEAN. Không có đơn vị đo. */
            value_label: string | null;
        };
        BuyerVariantAxis: {
            code: string;
            name: string;
            value_type: components["schemas"]["AttributeValueType"];
            /** @description Giá trị phân biệt mà ít nhất một SKU ACTIVE đang dùng. SELECT sắp theo option sort_order rồi code; kiểu khác sắp theo value tăng dần. */
            options: components["schemas"]["BuyerVariantOption"][];
        };
        BuyerVariantOption: {
            value: components["schemas"]["AttributeValue"];
            /** @description Label của option khi trục là SELECT; null với kiểu khác. */
            label: string | null;
        };
        BuyerMedia: {
            media_id: components["schemas"]["Uuid"];
            /** @description Null là media cấp Product; khác null luôn là một SKU có trong skus[]. */
            sku_id: components["schemas"]["Uuid"] | null;
            media_type: components["schemas"]["MediaType"];
            storage_key: string;
            alt_text: string | null;
            sort_order: number;
        };
        BuyerSku: {
            sku_id: components["schemas"]["Uuid"];
            sku_code: string;
            /** Format: int64 */
            base_price_vnd: number;
            /** Format: int64 */
            selling_price_vnd: number;
            variant_attributes: components["schemas"]["AttributeValues"];
            /** @description Thumbnail hiệu lực theo ADR-013, resolve trên toàn bộ media của SKU/Product, không phụ thuộc giới hạn media của projection này. */
            thumbnail_storage_key: string | null;
        };
        CreateProductRequest: {
            category_id: components["schemas"]["Uuid"];
            name: string;
            slug: components["schemas"]["Slug"];
            description?: string | null;
            attributes: components["schemas"]["AttributeValues"];
            media?: components["schemas"]["CreateProductMediaInput"][];
            skus: components["schemas"]["CreateSkuRequest"][];
        };
        ReplaceProductRequest: {
            category_id: components["schemas"]["Uuid"];
            name: string;
            slug: components["schemas"]["Slug"];
            description: string | null;
            attributes: components["schemas"]["AttributeValues"];
        };
        ChangeProductStatusRequest: {
            /**
             * @description DRAFT chỉ được tạo, không phải target của lifecycle transition.
             * @enum {string}
             */
            status: "ACTIVE" | "HIDDEN";
        };
        CreateSkuRequest: {
            sku_code: string;
            /** Format: int64 */
            base_price_vnd: number;
            status: components["schemas"]["SkuStatus"];
            variant_attributes: components["schemas"]["AttributeValues"];
            media?: components["schemas"]["CreateSkuMediaInput"][];
        };
        ReplaceSkuRequest: {
            sku_code: string;
            /** Format: int64 */
            base_price_vnd: number;
            status: components["schemas"]["SkuStatus"];
            variant_attributes: components["schemas"]["AttributeValues"];
        };
        CreateProductMediaInput: {
            media_type: components["schemas"]["MediaType"];
            storage_key: string;
            alt_text?: string | null;
            /** @default 0 */
            sort_order: number;
        };
        /** @description Media được gắn với SKU đang được tạo; không nhận sku_id. */
        CreateSkuMediaInput: components["schemas"]["CreateProductMediaInput"];
        CreateMediaRequest: {
            /** @description Missing hoặc null tạo media Product-level. */
            sku_id?: components["schemas"]["Uuid"] | null;
            media_type: components["schemas"]["MediaType"];
            storage_key: string;
            alt_text?: string | null;
            /** @default 0 */
            sort_order: number;
        };
        ReplaceMediaRequest: {
            sku_id: components["schemas"]["Uuid"] | null;
            media_type: components["schemas"]["MediaType"];
            storage_key: string;
            alt_text: string | null;
            sort_order: number;
        };
        Category: {
            category_id: components["schemas"]["Uuid"];
            parent_id?: components["schemas"]["Uuid"] | null;
            name: string;
            slug: string;
            /** Format: int64 */
            readonly category_revision: number;
            attributes: components["schemas"]["CategoryAttribute"][];
            /** Format: date-time */
            created_at: string;
            /** Format: date-time */
            updated_at: string;
        };
        CategoryList: {
            items: components["schemas"]["Category"][];
        };
        CreateCategoryRequest: {
            parent_id?: components["schemas"]["Uuid"];
            name: string;
            slug: string;
        };
        ReplaceCategoryRequest: {
            parent_id: components["schemas"]["Uuid"] | null;
            name: string;
            slug: string;
        };
        AttributeOption: {
            option_id: components["schemas"]["Uuid"];
            attribute_id: components["schemas"]["Uuid"];
            code: string;
            label: string;
            sort_order: number;
            /** Format: date-time */
            created_at: string;
            /** Format: date-time */
            updated_at: string;
        };
        Attribute: {
            attribute_id: components["schemas"]["Uuid"];
            code: string;
            name: string;
            value_type: components["schemas"]["AttributeValueType"];
            options: components["schemas"]["AttributeOption"][];
            /** Format: date-time */
            created_at: string;
            /** Format: date-time */
            updated_at: string;
        };
        AttributeList: {
            items: components["schemas"]["Attribute"][];
        };
        CreateAttributeRequest: {
            code: string;
            name: string;
            value_type: components["schemas"]["AttributeValueType"];
        };
        ReplaceAttributeRequest: {
            code: string;
            name: string;
            value_type: components["schemas"]["AttributeValueType"];
        };
        CreateAttributeOptionRequest: {
            code: string;
            label: string;
            /** @default 0 */
            sort_order: number;
        };
        ReplaceAttributeOptionRequest: {
            code: string;
            label: string;
            sort_order: number;
        };
        CategoryAttribute: {
            category_id: components["schemas"]["Uuid"];
            attribute_id: components["schemas"]["Uuid"];
            attribute_code: string;
            attribute_name: string;
            value_type: components["schemas"]["AttributeValueType"];
            is_required: boolean;
            is_variant: boolean;
        };
        CategoryAttributeList: {
            items: components["schemas"]["CategoryAttribute"][];
        };
        PutCategoryAttributeRequest: {
            is_required: boolean;
            is_variant: boolean;
        };
        ProductExportPage: {
            products: components["schemas"]["Product"][];
            /** @description Missing khi đã export tới upper bound. */
            next_cursor?: string;
            /** @description Offset của mọi partition t.product.events được chụp trước scan ở trang đầu. Lặp lại không đổi trên mọi trang của cùng export session; offset là record đầu tiên Search phải replay, theo semantics inclusive. */
            replay_from_offsets: components["schemas"]["ReplayOffset"][];
        };
        CategoryExport: {
            category_id: components["schemas"]["Uuid"];
            parent_id?: components["schemas"]["Uuid"] | null;
            name: string;
            slug: string;
            /** Format: int64 */
            category_revision: number;
            path: {
                category_id: components["schemas"]["Uuid"];
                name: string;
                slug: string;
            }[];
            is_leaf: boolean;
            /** Format: date-time */
            updated_at: string;
        };
        CategoryExportPage: {
            categories: components["schemas"]["CategoryExport"][];
            /** @description Missing khi đã export tới upper bound. */
            next_cursor?: string;
            /** @description Offset của mọi partition t.category.events được chụp trước scan ở trang đầu. Lặp lại không đổi trên mọi trang của cùng export session; offset là record đầu tiên Search phải replay, theo semantics inclusive. */
            replay_from_offsets: components["schemas"]["ReplayOffset"][];
        };
        ReplayOffset: {
            topic: string;
            /** Format: int32 */
            partition: number;
            /** Format: int64 */
            offset: number;
        };
        Problem: components["schemas"]["problem.schema"];
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
        /** @description Request không hợp lệ; code INVALID_PRICE hoặc validation code khác. */
        BadRequest: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Thiếu hoặc không hợp lệ trusted Gateway identity. */
        Unauthorized: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Caller không có role ADMIN. */
        Forbidden: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Thiếu header X-Service-Identity (defense-in-depth trong Product). Principal không được mesh cho phép bị từ chối sớm hơn bằng 403 text/plain từ waypoint, không có body problem+json. */
        UnauthorizedService: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description X-Service-Identity không nằm trong allowlist của nhóm route (defense-in-depth trong Product). Principal sai đã bị mesh chặn trước đó bằng 403 text/plain. */
        ForbiddenService: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description PRODUCT_NOT_FOUND. */
        ProductNotFound: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description SKU_NOT_FOUND, bao gồm trường hợp SKU không thuộc Product trên path. */
        SkuNotFound: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description MEDIA_NOT_FOUND hoặc media không thuộc Product trên path. */
        MediaNotFound: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description CATEGORY_NOT_FOUND. */
        CategoryNotFound: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description ATTRIBUTE_NOT_FOUND. */
        AttributeNotFound: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description CATEGORY_NOT_FOUND hoặc ATTRIBUTE_NOT_FOUND. */
        AttributeOrCategoryNotFound: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description ATTRIBUTE_OPTION_NOT_FOUND hoặc option không thuộc Attribute trên path. */
        AttributeOptionNotFound: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Conflict nghiệp vụ, gồm CATEGORY_NOT_LEAF, CATEGORY_NOT_EMPTY, CATEGORY_CYCLE_DETECTED, SKU_CODE_ALREADY_EXISTS, SLUG_ALREADY_EXISTS, ATTRIBUTE_CODE_ALREADY_EXISTS, ATTRIBUTE_OPTION_CODE_ALREADY_EXISTS, PRODUCT_NOT_SUBMITTABLE, SKU_LAST_DELETE_FORBIDDEN, INVALID_STATE_TRANSITION, ATTRIBUTE_IN_USE và CATALOG_CONSTRAINT_VIOLATION. */
        DomainConflict: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description If-Match không bằng catalog_revision hiện tại; code PRODUCT_REVISION_MISMATCH. */
        ProductRevisionMismatch: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description PostgreSQL/lock dependency tạm thời không khả dụng; problem.retryable=true. */
        RetryableUnavailable: {
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
        /** @description ETag Product dạng `"catalog-revision-<n>"`; wildcard không được chấp nhận. */
        IfMatchProduct: string;
        ProductId: components["schemas"]["Uuid"];
        SkuId: components["schemas"]["Uuid"];
        MediaId: components["schemas"]["Uuid"];
        CategoryId: components["schemas"]["Uuid"];
        AttributeId: components["schemas"]["Uuid"];
        OptionId: components["schemas"]["Uuid"];
        /** @description Opaque cursor từ next_cursor của trang trước. */
        Cursor: string;
        Limit: number;
        CategoryIdQuery: components["schemas"]["Uuid"];
        ProductStatusQuery: components["schemas"]["ProductStatus"];
        /** @description Chỉ trả direct children của parent. Không kết hợp với root_only=true. */
        CategoryParentIdQuery: components["schemas"]["Uuid"];
        /** @description Chỉ trả category root. Không kết hợp với parent_id. */
        RootOnly: boolean;
        /** @description Opaque, signed keyset cursor chứa last ID và upper bound; TTL do service cấu hình. */
        ExportCursor: string;
        ExportLimit: number;
    };
    requestBodies: never;
    headers: {
        /** @description Strong ETag đại diện catalog_revision hiện tại, ví dụ `"catalog-revision-42"`. */
        ProductETag: string;
        /** @description URI Product vừa tạo. */
        ProductLocation: string;
        /** @description URI SKU vừa tạo. */
        SkuLocation: string;
        /** @description URI media vừa tạo. */
        MediaLocation: string;
        /** @description URI category vừa tạo. */
        CategoryLocation: string;
        /** @description URI attribute vừa tạo. */
        AttributeLocation: string;
        /** @description URI option vừa tạo. */
        AttributeOptionLocation: string;
    };
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    listProducts: {
        parameters: {
            query?: {
                /** @description Opaque cursor từ next_cursor của trang trước. */
                cursor?: components["parameters"]["Cursor"];
                limit?: components["parameters"]["Limit"];
                category_id?: components["parameters"]["CategoryIdQuery"];
                status?: components["parameters"]["ProductStatusQuery"];
            };
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Trang Product theo cursor; không phải search/browse API cho shopper. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ProductPage"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
        };
    };
    createProduct: {
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
                "application/json": components["schemas"]["CreateProductRequest"];
            };
        };
        responses: {
            /** @description Product DRAFT đã được tạo và SKU snapshot `sku-upserted` đã nằm trong outbox transaction. */
            201: {
                headers: {
                    Location: components["headers"]["ProductLocation"];
                    ETag: components["headers"]["ProductETag"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Product"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            409: components["responses"]["DomainConflict"];
            503: components["responses"]["RetryableUnavailable"];
        };
    };
    getProduct: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                product_id: components["parameters"]["ProductId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Snapshot Product, SKU và media hiện tại. */
            200: {
                headers: {
                    ETag: components["headers"]["ProductETag"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Product"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["ProductNotFound"];
        };
    };
    replaceProduct: {
        parameters: {
            query?: never;
            header: {
                /** @description ETag Product dạng `"catalog-revision-<n>"`; wildcard không được chấp nhận. */
                "If-Match": components["parameters"]["IfMatchProduct"];
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                product_id: components["parameters"]["ProductId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ReplaceProductRequest"];
            };
        };
        responses: {
            /** @description Product đã thay; catalog_revision tăng đúng một lần. */
            200: {
                headers: {
                    ETag: components["headers"]["ProductETag"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Product"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["ProductNotFound"];
            409: components["responses"]["DomainConflict"];
            412: components["responses"]["ProductRevisionMismatch"];
            503: components["responses"]["RetryableUnavailable"];
        };
    };
    deleteProduct: {
        parameters: {
            query?: never;
            header: {
                /** @description ETag Product dạng `"catalog-revision-<n>"`; wildcard không được chấp nhận. */
                "If-Match": components["parameters"]["IfMatchProduct"];
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                product_id: components["parameters"]["ProductId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Product đã bị hard delete; tombstone đã commit. */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["ProductNotFound"];
            412: components["responses"]["ProductRevisionMismatch"];
            503: components["responses"]["RetryableUnavailable"];
        };
    };
    changeProductStatus: {
        parameters: {
            query?: never;
            header: {
                /** @description ETag Product dạng `"catalog-revision-<n>"`; wildcard không được chấp nhận. */
                "If-Match": components["parameters"]["IfMatchProduct"];
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                product_id: components["parameters"]["ProductId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ChangeProductStatusRequest"];
            };
        };
        responses: {
            /** @description Lifecycle transition đã commit và status event đã vào outbox. */
            200: {
                headers: {
                    ETag: components["headers"]["ProductETag"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Product"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["ProductNotFound"];
            409: components["responses"]["DomainConflict"];
            412: components["responses"]["ProductRevisionMismatch"];
            503: components["responses"]["RetryableUnavailable"];
        };
    };
    createSku: {
        parameters: {
            query?: never;
            header: {
                /** @description ETag Product dạng `"catalog-revision-<n>"`; wildcard không được chấp nhận. */
                "If-Match": components["parameters"]["IfMatchProduct"];
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                product_id: components["parameters"]["ProductId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateSkuRequest"];
            };
        };
        responses: {
            /** @description SKU đã được tạo. */
            201: {
                headers: {
                    Location: components["headers"]["SkuLocation"];
                    ETag: components["headers"]["ProductETag"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Sku"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["ProductNotFound"];
            409: components["responses"]["DomainConflict"];
            412: components["responses"]["ProductRevisionMismatch"];
            503: components["responses"]["RetryableUnavailable"];
        };
    };
    replaceSku: {
        parameters: {
            query?: never;
            header: {
                /** @description ETag Product dạng `"catalog-revision-<n>"`; wildcard không được chấp nhận. */
                "If-Match": components["parameters"]["IfMatchProduct"];
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                product_id: components["parameters"]["ProductId"];
                sku_id: components["parameters"]["SkuId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ReplaceSkuRequest"];
            };
        };
        responses: {
            /** @description SKU đã được thay và Product revision tăng một lần. */
            200: {
                headers: {
                    ETag: components["headers"]["ProductETag"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Sku"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["SkuNotFound"];
            409: components["responses"]["DomainConflict"];
            412: components["responses"]["ProductRevisionMismatch"];
            503: components["responses"]["RetryableUnavailable"];
        };
    };
    deleteSku: {
        parameters: {
            query?: never;
            header: {
                /** @description ETag Product dạng `"catalog-revision-<n>"`; wildcard không được chấp nhận. */
                "If-Match": components["parameters"]["IfMatchProduct"];
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                product_id: components["parameters"]["ProductId"];
                sku_id: components["parameters"]["SkuId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description SKU và media liên quan đã bị hard delete; SKU_DELETED tombstone đã commit. */
            204: {
                headers: {
                    ETag: components["headers"]["ProductETag"];
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["SkuNotFound"];
            409: components["responses"]["DomainConflict"];
            412: components["responses"]["ProductRevisionMismatch"];
            503: components["responses"]["RetryableUnavailable"];
        };
    };
    createProductMedia: {
        parameters: {
            query?: never;
            header: {
                /** @description ETag Product dạng `"catalog-revision-<n>"`; wildcard không được chấp nhận. */
                "If-Match": components["parameters"]["IfMatchProduct"];
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                product_id: components["parameters"]["ProductId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateMediaRequest"];
            };
        };
        responses: {
            /** @description Media đã được tạo. */
            201: {
                headers: {
                    Location: components["headers"]["MediaLocation"];
                    ETag: components["headers"]["ProductETag"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Media"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["ProductNotFound"];
            409: components["responses"]["DomainConflict"];
            412: components["responses"]["ProductRevisionMismatch"];
            503: components["responses"]["RetryableUnavailable"];
        };
    };
    replaceProductMedia: {
        parameters: {
            query?: never;
            header: {
                /** @description ETag Product dạng `"catalog-revision-<n>"`; wildcard không được chấp nhận. */
                "If-Match": components["parameters"]["IfMatchProduct"];
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                product_id: components["parameters"]["ProductId"];
                media_id: components["parameters"]["MediaId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ReplaceMediaRequest"];
            };
        };
        responses: {
            /** @description Media đã được thay. */
            200: {
                headers: {
                    ETag: components["headers"]["ProductETag"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Media"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["MediaNotFound"];
            409: components["responses"]["DomainConflict"];
            412: components["responses"]["ProductRevisionMismatch"];
            503: components["responses"]["RetryableUnavailable"];
        };
    };
    deleteProductMedia: {
        parameters: {
            query?: never;
            header: {
                /** @description ETag Product dạng `"catalog-revision-<n>"`; wildcard không được chấp nhận. */
                "If-Match": components["parameters"]["IfMatchProduct"];
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                product_id: components["parameters"]["ProductId"];
                media_id: components["parameters"]["MediaId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Media đã bị xóa. */
            204: {
                headers: {
                    ETag: components["headers"]["ProductETag"];
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["MediaNotFound"];
            412: components["responses"]["ProductRevisionMismatch"];
            503: components["responses"]["RetryableUnavailable"];
        };
    };
    listCategories: {
        parameters: {
            query?: {
                /** @description Chỉ trả direct children của parent. Không kết hợp với root_only=true. */
                parent_id?: components["parameters"]["CategoryParentIdQuery"];
                /** @description Chỉ trả category root. Không kết hợp với parent_id. */
                root_only?: components["parameters"]["RootOnly"];
            };
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Category matching filter, sắp xếp name tăng dần. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CategoryList"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
        };
    };
    createCategory: {
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
                "application/json": components["schemas"]["CreateCategoryRequest"];
            };
        };
        responses: {
            /** @description Category đã tạo. */
            201: {
                headers: {
                    Location: components["headers"]["CategoryLocation"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Category"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            409: components["responses"]["DomainConflict"];
        };
    };
    getCategory: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                category_id: components["parameters"]["CategoryId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Category hiện tại. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Category"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["CategoryNotFound"];
        };
    };
    replaceCategory: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                category_id: components["parameters"]["CategoryId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ReplaceCategoryRequest"];
            };
        };
        responses: {
            /** @description Category đã thay. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Category"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["CategoryNotFound"];
            409: components["responses"]["DomainConflict"];
        };
    };
    deleteCategory: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                category_id: components["parameters"]["CategoryId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Category đã bị xóa. */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["CategoryNotFound"];
            409: components["responses"]["DomainConflict"];
        };
    };
    listCategoryAttributes: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                category_id: components["parameters"]["CategoryId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Attribute mappings của category. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CategoryAttributeList"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["CategoryNotFound"];
        };
    };
    putCategoryAttribute: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                category_id: components["parameters"]["CategoryId"];
                attribute_id: components["parameters"]["AttributeId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PutCategoryAttributeRequest"];
            };
        };
        responses: {
            /** @description Mapping đã tồn tại và được thay. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CategoryAttribute"];
                };
            };
            /** @description Mapping mới đã được tạo. */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CategoryAttribute"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["AttributeOrCategoryNotFound"];
            409: components["responses"]["DomainConflict"];
        };
    };
    deleteCategoryAttribute: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                category_id: components["parameters"]["CategoryId"];
                attribute_id: components["parameters"]["AttributeId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Mapping đã bị xóa. */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["AttributeOrCategoryNotFound"];
            409: components["responses"]["DomainConflict"];
        };
    };
    listAttributes: {
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
            /** @description Tất cả attribute, gồm option cho SELECT. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AttributeList"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
        };
    };
    createAttribute: {
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
                "application/json": components["schemas"]["CreateAttributeRequest"];
            };
        };
        responses: {
            /** @description Attribute đã được tạo. */
            201: {
                headers: {
                    Location: components["headers"]["AttributeLocation"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Attribute"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            409: components["responses"]["DomainConflict"];
        };
    };
    getAttribute: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                attribute_id: components["parameters"]["AttributeId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Attribute hiện tại. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Attribute"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["AttributeNotFound"];
        };
    };
    replaceAttribute: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                attribute_id: components["parameters"]["AttributeId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ReplaceAttributeRequest"];
            };
        };
        responses: {
            /** @description Attribute đã thay. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Attribute"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["AttributeNotFound"];
            409: components["responses"]["DomainConflict"];
        };
    };
    deleteAttribute: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                attribute_id: components["parameters"]["AttributeId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Attribute đã bị xóa. */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["AttributeNotFound"];
            409: components["responses"]["DomainConflict"];
        };
    };
    createAttributeOption: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                attribute_id: components["parameters"]["AttributeId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateAttributeOptionRequest"];
            };
        };
        responses: {
            /** @description Option đã tạo. */
            201: {
                headers: {
                    Location: components["headers"]["AttributeOptionLocation"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AttributeOption"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["AttributeNotFound"];
            409: components["responses"]["DomainConflict"];
        };
    };
    replaceAttributeOption: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                attribute_id: components["parameters"]["AttributeId"];
                option_id: components["parameters"]["OptionId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ReplaceAttributeOptionRequest"];
            };
        };
        responses: {
            /** @description Option đã thay. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AttributeOption"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["AttributeOptionNotFound"];
            409: components["responses"]["DomainConflict"];
        };
    };
    deleteAttributeOption: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                attribute_id: components["parameters"]["AttributeId"];
                option_id: components["parameters"]["OptionId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Option đã bị xóa. */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["AttributeOptionNotFound"];
            409: components["responses"]["DomainConflict"];
        };
    };
    exportProductsForReindex: {
        parameters: {
            query?: {
                /** @description Opaque, signed keyset cursor chứa last ID và upper bound; TTL do service cấu hình. */
                cursor?: components["parameters"]["ExportCursor"];
                limit?: components["parameters"]["ExportLimit"];
            };
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Một trang catalog snapshot, không có stock fields. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ProductExportPage"];
                };
            };
            401: components["responses"]["UnauthorizedService"];
            403: components["responses"]["ForbiddenService"];
            503: components["responses"]["RetryableUnavailable"];
        };
    };
    getProductSnapshotForReindex: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                product_id: components["parameters"]["ProductId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Snapshot catalog hiện tại. */
            200: {
                headers: {
                    ETag: components["headers"]["ProductETag"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Product"];
                };
            };
            401: components["responses"]["UnauthorizedService"];
            403: components["responses"]["ForbiddenService"];
            404: components["responses"]["ProductNotFound"];
            503: components["responses"]["RetryableUnavailable"];
        };
    };
    getBuyerProductDetail: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                slug: components["schemas"]["Slug"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Buyer-readable catalog snapshot; BFF tự ghép advisory availability từ Inventory Availability Read API. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["BuyerProductDetail"];
                };
            };
            401: components["responses"]["UnauthorizedService"];
            403: components["responses"]["ForbiddenService"];
            404: components["responses"]["ProductNotFound"];
            503: components["responses"]["RetryableUnavailable"];
        };
    };
    exportCategoriesForReindex: {
        parameters: {
            query?: {
                /** @description Opaque, signed keyset cursor chứa last ID và upper bound; TTL do service cấu hình. */
                cursor?: components["parameters"]["ExportCursor"];
                limit?: components["parameters"]["ExportLimit"];
            };
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Một trang category snapshot, không chứa Product hay stock. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CategoryExportPage"];
                };
            };
            401: components["responses"]["UnauthorizedService"];
            403: components["responses"]["ForbiddenService"];
            503: components["responses"]["RetryableUnavailable"];
        };
    };
}
