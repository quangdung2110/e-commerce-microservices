/**
 * FILE SINH TỰ ĐỘNG — KHÔNG SỬA TAY.
 * Nguồn: contracts/http/search/v2/openapi.yaml
 * Sinh lại bằng `scripts/node.sh npm run gen:api`.
 */
export interface paths {
    "/api/v2/search/products": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Search và browse Product catalog đang hiển thị
         * @description Public read qua API Gateway. Kết quả là Product card từ SKU projection, có thể cũ vài giây so với Product/Inventory. Không dùng response này để reserve, chốt giá hoặc xác nhận tồn kho. Chọn SKU theo selling_price_vnd asc rồi sku_id asc. Mọi sort Product dùng product_id asc làm tie-break; cursor có chữ ký, giữ snapshot PIT và chỉ hợp lệ với đúng query/filter/sort đã sinh nó. Giá tăng/giảm dùng giá của SKU đại diện; relevance lấy score cao nhất của SKU khớp, newest lấy updated_at lớn nhất, rating lấy rating lớn nhất của các SKU khớp. Snapshot hết hạn sau 2 phút không hoạt động: INVALID_CURSOR, tải lại trang đầu.
         */
        get: operations["searchProducts"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v2/search/suggestions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Gợi ý autocomplete từ projection pre-compute
         * @description q có tối thiểu hai ký tự. Đây là capability degrade độc lập: client có thể ẩn dropdown khi endpoint lỗi mà không chặn search chính. Mỗi item là một cụm từ để hoàn tất truy vấn: khi được chọn, client dùng text làm q cho tìm kiếm sản phẩm. Item không phải link đến Product, SKU hay Category và response không cung cấp định danh để điều hướng.
         */
        get: operations["suggestSearchTerms"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v2/search/categories": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Lấy cây category storefront
         * @description Chỉ category chưa tombstone được trả. product_count là DISTINCT Product do job định kỳ tính lại, nên có thể trễ hơn search result. Search không trả cây thiếu khi Elasticsearch quá hạn hoặc lỗi shard: kết quả đó là 504 SEARCH_TIMEOUT.
         */
        get: operations["getCategoryTree"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v2/search/categories/{category_id}/facets": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Lấy filter facets khả dụng trong một nhánh category
         * @description Counts dùng cùng visibility filters với search (ACTIVE, chưa xóa và in_stock=true mặc định), đếm DISTINCT product_id bằng cardinality nên là ước lượng. Filter option value là opaque token để gửi lại qua query parameter filter của searchProducts.
         */
        get: operations["getCategoryFacets"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v2/search/reindex-runs": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Liệt kê các full reindex run, mới nhất trước
         * @description Chỉ ADMIN qua Gateway. Cần để tìm run do control event tự khởi chạy, vì admin không biết run_id của nó.
         */
        get: operations["listReindexRuns"];
        put?: never;
        /**
         * Trigger full reindex bất đồng bộ
         * @description Chỉ ADMIN qua Gateway. Server lấy actor từ trusted identity header, không tin actor do body tự khai. Một reindex chưa kết thúc sẽ chặn trigger mới (REINDEX_ALREADY_RUNNING). Một run mà process sở hữu nó đã chết được xử lý trước (resume hoặc đánh dấu bị bỏ rơi) nên không chặn trigger mãi mãi. Cluster red hoặc thiếu dung lượng đĩa cho một index thứ hai bị từ chối bằng 409 REINDEX_PRECONDITION_FAILED; cluster yellow vẫn được nhận. Search cũng tự tạo run (actor `system:catalog-control`) khi Product phát catalog-reindex-requested; run đó xuất hiện ở listReindexRuns.
         */
        post: operations["createReindexRun"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v2/search/reindex-runs/{run_id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Đọc trạng thái một full reindex run */
        get: operations["getReindexRun"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v2/search/reindex-runs/{run_id}/cancellation": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Yêu cầu hủy một full reindex run
         * @description Chỉ ADMIN qua Gateway. Run đang Build hoặc Catch-up dừng ở trang kế tiếp và kết thúc `CANCELLED`, các index nó đã dựng bị xóa, alias không đổi; response `202` vẫn mang trạng thái hiện tại và client đọc lại run để thấy `CANCELLED`. Run không còn process sở hữu được kết thúc ngay trong chính request này. Run đã kết thúc, hoặc đang kiểm tra và chuyển alias (CUTOVER), không hủy được (409 REINDEX_RUN_NOT_CANCELLABLE).
         */
        post: operations["cancelReindexRun"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v2/search/reindex-runs/{run_id}/rollback": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Trả các alias về index mà một run đã hoàn tất thay thế
         * @description Chỉ ADMIN qua Gateway. Chuyển cả bốn alias về index trước đó trong một lệnh `_aliases` atomic và đặt run `ROLLED_BACK`; xử lý đồng bộ nên trả 200. Chỉ run `COMPLETED` là lần cutover mới nhất mới rollback được, và chỉ khi index trước đó chưa bị job dọn dẹp xóa (giữ tối thiểu 24 giờ sau cutover). Dữ liệu ghi vào index mới sau cutover không có trong index cũ: chạy reindex mới để hội tụ.
         */
        post: operations["rollbackReindexRun"];
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
         * @description Opaque UUID owned by the upstream service or Search for run IDs.
         */
        Uuid: string;
        /**
         * @default relevance
         * @enum {string}
         */
        SearchSort: "relevance" | "price_asc" | "price_desc" | "newest" | "rating";
        SearchPage: {
            items: components["schemas"]["SearchProductItem"][];
            /**
             * Format: int64
             * @description Exact number of distinct matching products in the PIT snapshot.
             */
            total_products: number;
            /** @description Null khi không còn Product tiếp theo. */
            next_cursor: string | null;
            facets: components["schemas"]["FacetSet"];
        };
        /** @description Một Product duy nhất; sku_id, giá, thumbnail và tồn kho thuộc SKU đại diện giá thấp nhất thỏa tất cả bộ lọc. */
        SearchProductItem: {
            sku_id: components["schemas"]["Uuid"];
            product_id: components["schemas"]["Uuid"];
            product_slug: string;
            name: string;
            /**
             * Format: uri-reference
             * @description CDN URL hoặc configured placeholder; không phải thumbnail_storage_key.
             */
            thumbnail_url: string;
            /** Format: int64 */
            base_price_vnd: number;
            /** Format: int64 */
            selling_price_vnd: number;
            discount_percentage: number;
            product_rating_avg: number | null;
            /** Format: int64 */
            product_rating_count: number;
            /** @description Eventual Inventory snapshot, không phải kết quả reserve. */
            is_in_stock: boolean;
        };
        SuggestionList: {
            items: components["schemas"]["Suggestion"][];
        };
        Suggestion: {
            text: string;
            /** @description Suggestion kind for dropdown presentation only; it is not a navigation target and clients must tolerate newly added types. */
            type: string;
        };
        CategoryTree: {
            /** @constant */
            count_semantics: "DISTINCT_PRODUCT";
            categories: components["schemas"]["CategoryNode"][];
        };
        CategoryNode: {
            category_id: components["schemas"]["Uuid"];
            name: string;
            slug: string;
            /** Format: int64 */
            product_count: number;
            /**
             * Format: date-time
             * @description Null cho tới khi job recompute chạy lần đầu cho node này; category_upsert không khởi tạo số đếm.
             */
            product_count_updated_at: string | null;
            children: components["schemas"]["CategoryNode"][];
        };
        FacetSet: {
            /** @constant */
            count_semantics: "DISTINCT_PRODUCT";
            /** @constant */
            count_accuracy: "APPROXIMATE";
            filters: components["schemas"]["AttributeFacet"][];
        };
        AttributeFacet: {
            code: string;
            label: string;
            /** @constant */
            type: "MULTI_SELECT";
            options: components["schemas"]["FacetOption"][];
        };
        FacetOption: {
            /** @description Opaque token accepted by the repeatable filter parameter. */
            value: string;
            label: string;
            /** Format: int64 */
            count: number;
        };
        CreateReindexRunRequest: {
            reason: string;
        };
        /**
         * @description STARTED, BUILDING, CATCHING_UP và CUTOVER là các trạng thái chưa kết thúc; một run chưa kết thúc giữ slot singleton. CUTOVER gồm bước kiểm tra index mới (số document, sample diff, smoke query) và lệnh chuyển alias. COMPLETED, FAILED, ROLLED_BACK, SUPERSEDED và CANCELLED là trạng thái kết thúc. SUPERSEDED nghĩa là một catalog-reindex-requested mới tới trong lúc run đang chạy; run bị bỏ, alias giữ nguyên và request vẫn được lưu để một run mới được khởi chạy. CANCELLED nghĩa là admin đã hủy (failure.code RUN_CANCELLED). ROLLED_BACK nghĩa là các alias đã được trả về index cũ sau khi COMPLETED.
         * @enum {string}
         */
        ReindexRunStatus: "STARTED" | "BUILDING" | "CATCHING_UP" | "CUTOVER" | "COMPLETED" | "FAILED" | "ROLLED_BACK" | "SUPERSEDED" | "CANCELLED";
        ReindexRunList: {
            items: components["schemas"]["ReindexRun"][];
        };
        ReindexRun: {
            run_id: components["schemas"]["Uuid"];
            status: components["schemas"]["ReindexRunStatus"];
            /** @enum {string|null} */
            phase: "BUILDING" | "CATCHING_UP" | "CUTOVER" | null;
            /** @description Lý do admin đã khai, hoặc "catalog-reindex-requested: ..." với run tự khởi chạy. */
            reason: string;
            /** @description Identity đã trigger run, hoặc `system:catalog-control` với run tự khởi chạy. */
            actor: string;
            progress: components["schemas"]["ReindexProgress"];
            /** Format: uri-reference */
            status_url: string;
            /** Format: date-time */
            started_at: string;
            /** Format: date-time */
            finished_at: string | null;
            failure: components["schemas"]["ReindexFailure"] | null;
        };
        ReindexProgress: {
            /** Format: int64 */
            categories_exported: number;
            /** Format: int64 */
            products_exported: number;
            /** Format: int64 */
            stock_snapshots_imported: number;
            /** Format: int64 */
            rating_aggregates_imported: number;
            /** Format: int64 */
            catch_up_lag: number | null;
        };
        ReindexFailure: {
            /** @description REINDEX_FAILED (build, catch-up hoặc kiểm tra không đạt), RUN_SUPERSEDED, RUN_CANCELLED hoặc RUN_ABANDONED (không process nào giữ run sống sau số lần resume tối đa). */
            code: string;
            message: string;
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
        /** @description INVALID_QUERY_PARAMETER (kèm errors[] theo field: tham số sai kiểu, ngoài khoảng hoặc vượt giới hạn độ dài/số lượng, token `filter` không phải giá trị facet, `category` không khớp category nào đang hiển thị, body không đọc được) hoặc INVALID_CURSOR (cursor sai chữ ký, đổi tham số, quá hạn hoặc quá dài). */
        BadRequest: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Missing or invalid trusted Gateway identity. */
        Unauthorized: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Caller does not have role ADMIN. */
        Forbidden: {
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
        /** @description REINDEX_RUN_NOT_FOUND. */
        ReindexRunNotFound: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description REINDEX_ALREADY_RUNNING (retry after the active run completes), or REINDEX_PRECONDITION_FAILED (cluster red or not enough free disk; retry once the cluster has recovered). */
        ReindexAlreadyRunning: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description REINDEX_RUN_NOT_CANCELLABLE; the run already finished or is switching the aliases. */
        ReindexRunNotCancellable: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description REINDEX_ROLLBACK_UNAVAILABLE; the run is not COMPLETED, replaced no index, is not the latest cutover, the previous index was already removed, or another run is in progress. */
        ReindexRollbackUnavailable: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description ELASTICSEARCH_UNAVAILABLE; retryable infrastructure failure. */
        ElasticsearchUnavailable: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description SEARCH_TIMEOUT; Search did not retry internally. */
        SearchTimeout: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description INTERNAL_ERROR. */
        InternalError: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
    };
    parameters: {
        /** @description Echoed in the response header and as the Problem `trace_id` when it is 16..64 characters of `[A-Za-z0-9._:-]`; a value outside that, or none, is replaced by an id Search generates. */
        RequestId: string;
        /** @description Trim, lower-case và collapse whitespace trước khi fingerprint cursor. */
        SearchQuery: string;
        /** @description Category ID hoặc slug; Search resolve từ projection category hiện hành. Giá trị không khớp category nào đang hiển thị là 400 INVALID_QUERY_PARAMETER (field `category`), không phải tìm trên toàn catalog. */
        Category: string;
        /** @description Lặp lại cho từng opaque attribute token từ FacetOption.value, ví dụ filter=brand:s:apple&filter=color:s:black. Token là chuỗi đục do Search sinh; client chỉ gửi lại nguyên văn giá trị nhận được và không tự ghép. OR trong cùng attribute code, AND giữa code khác nhau. Một token không có dạng của FacetOption.value bị từ chối 400 INVALID_QUERY_PARAMETER (field `filter`), không bị bỏ qua. */
        Filter: string[];
        PriceMin: number;
        /** @description Must be greater than or equal to price_min when both are supplied. */
        PriceMax: number;
        InStock: boolean;
        HasPromotion: boolean;
        SearchSort: components["schemas"]["SearchSort"];
        /** @description Opaque signed cursor từ next_cursor; không tự sinh hoặc dùng lại với query khác. Cursor SKU cũ không còn hợp lệ. */
        SearchCursor: string;
        SearchSize: number;
        SuggestQuery: string;
        SuggestLimit: number;
        CategoryId: components["schemas"]["Uuid"];
        ReindexRunId: components["schemas"]["Uuid"];
    };
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    searchProducts: {
        parameters: {
            query?: {
                /** @description Trim, lower-case và collapse whitespace trước khi fingerprint cursor. */
                q?: components["parameters"]["SearchQuery"];
                /** @description Category ID hoặc slug; Search resolve từ projection category hiện hành. Giá trị không khớp category nào đang hiển thị là 400 INVALID_QUERY_PARAMETER (field `category`), không phải tìm trên toàn catalog. */
                category?: components["parameters"]["Category"];
                /** @description Lặp lại cho từng opaque attribute token từ FacetOption.value, ví dụ filter=brand:s:apple&filter=color:s:black. Token là chuỗi đục do Search sinh; client chỉ gửi lại nguyên văn giá trị nhận được và không tự ghép. OR trong cùng attribute code, AND giữa code khác nhau. Một token không có dạng của FacetOption.value bị từ chối 400 INVALID_QUERY_PARAMETER (field `filter`), không bị bỏ qua. */
                filter?: components["parameters"]["Filter"];
                price_min?: components["parameters"]["PriceMin"];
                /** @description Must be greater than or equal to price_min when both are supplied. */
                price_max?: components["parameters"]["PriceMax"];
                in_stock?: components["parameters"]["InStock"];
                has_promotion?: components["parameters"]["HasPromotion"];
                sort?: components["parameters"]["SearchSort"];
                /** @description Opaque signed cursor từ next_cursor; không tự sinh hoặc dùng lại với query khác. Cursor SKU cũ không còn hợp lệ. */
                cursor?: components["parameters"]["SearchCursor"];
                size?: components["parameters"]["SearchSize"];
            };
            header?: {
                /** @description Echoed in the response header and as the Problem `trace_id` when it is 16..64 characters of `[A-Za-z0-9._:-]`; a value outside that, or none, is replaced by an id Search generates. */
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Trang Product duy nhất; total_products chính xác trong cùng snapshot. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SearchPage"];
                };
            };
            400: components["responses"]["BadRequest"];
            500: components["responses"]["InternalError"];
            503: components["responses"]["ElasticsearchUnavailable"];
            504: components["responses"]["SearchTimeout"];
        };
    };
    suggestSearchTerms: {
        parameters: {
            query: {
                q: components["parameters"]["SuggestQuery"];
                limit?: components["parameters"]["SuggestLimit"];
            };
            header?: {
                /** @description Echoed in the response header and as the Problem `trace_id` when it is 16..64 characters of `[A-Za-z0-9._:-]`; a value outside that, or none, is replaced by an id Search generates. */
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Suggestions theo weight giảm dần và text tăng dần khi hòa. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SuggestionList"];
                };
            };
            400: components["responses"]["BadRequest"];
            500: components["responses"]["InternalError"];
            503: components["responses"]["ElasticsearchUnavailable"];
            504: components["responses"]["SearchTimeout"];
        };
    };
    getCategoryTree: {
        parameters: {
            query?: never;
            header?: {
                /** @description Echoed in the response header and as the Problem `trace_id` when it is 16..64 characters of `[A-Za-z0-9._:-]`; a value outside that, or none, is replaced by an id Search generates. */
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Cây category đã materialize; node mồ côi bị loại khỏi response. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CategoryTree"];
                };
            };
            500: components["responses"]["InternalError"];
            503: components["responses"]["ElasticsearchUnavailable"];
            504: components["responses"]["SearchTimeout"];
        };
    };
    getCategoryFacets: {
        parameters: {
            query?: never;
            header?: {
                /** @description Echoed in the response header and as the Problem `trace_id` when it is 16..64 characters of `[A-Za-z0-9._:-]`; a value outside that, or none, is replaced by an id Search generates. */
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                category_id: components["parameters"]["CategoryId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Facet bootstrap cho category; không nhận buyer-selected filters. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["FacetSet"];
                };
            };
            404: components["responses"]["CategoryNotFound"];
            500: components["responses"]["InternalError"];
            503: components["responses"]["ElasticsearchUnavailable"];
            504: components["responses"]["SearchTimeout"];
        };
    };
    listReindexRuns: {
        parameters: {
            query?: {
                /** @description Chỉ trả run ở trạng thái này. */
                status?: components["schemas"]["ReindexRunStatus"];
                limit?: number;
            };
            header?: {
                /** @description Echoed in the response header and as the Problem `trace_id` when it is 16..64 characters of `[A-Za-z0-9._:-]`; a value outside that, or none, is replaced by an id Search generates. */
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Các run, sắp xếp theo started_at giảm dần. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ReindexRunList"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            500: components["responses"]["InternalError"];
            503: components["responses"]["ElasticsearchUnavailable"];
            504: components["responses"]["SearchTimeout"];
        };
    };
    createReindexRun: {
        parameters: {
            query?: never;
            header?: {
                /** @description Echoed in the response header and as the Problem `trace_id` when it is 16..64 characters of `[A-Za-z0-9._:-]`; a value outside that, or none, is replaced by an id Search generates. */
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateReindexRunRequest"];
            };
        };
        responses: {
            /** @description Run đã được nhận; công việc tiếp tục nền. */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ReindexRun"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            409: components["responses"]["ReindexAlreadyRunning"];
            500: components["responses"]["InternalError"];
            503: components["responses"]["ElasticsearchUnavailable"];
            504: components["responses"]["SearchTimeout"];
        };
    };
    getReindexRun: {
        parameters: {
            query?: never;
            header?: {
                /** @description Echoed in the response header and as the Problem `trace_id` when it is 16..64 characters of `[A-Za-z0-9._:-]`; a value outside that, or none, is replaced by an id Search generates. */
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                run_id: components["parameters"]["ReindexRunId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Trạng thái và tiến độ hiện tại của run. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ReindexRun"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["ReindexRunNotFound"];
            500: components["responses"]["InternalError"];
            503: components["responses"]["ElasticsearchUnavailable"];
            504: components["responses"]["SearchTimeout"];
        };
    };
    cancelReindexRun: {
        parameters: {
            query?: never;
            header?: {
                /** @description Echoed in the response header and as the Problem `trace_id` when it is 16..64 characters of `[A-Za-z0-9._:-]`; a value outside that, or none, is replaced by an id Search generates. */
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                run_id: components["parameters"]["ReindexRunId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Yêu cầu hủy đã được ghi nhận. */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ReindexRun"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["ReindexRunNotFound"];
            409: components["responses"]["ReindexRunNotCancellable"];
            500: components["responses"]["InternalError"];
            503: components["responses"]["ElasticsearchUnavailable"];
            504: components["responses"]["SearchTimeout"];
        };
    };
    rollbackReindexRun: {
        parameters: {
            query?: never;
            header?: {
                /** @description Echoed in the response header and as the Problem `trace_id` when it is 16..64 characters of `[A-Za-z0-9._:-]`; a value outside that, or none, is replaced by an id Search generates. */
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                run_id: components["parameters"]["ReindexRunId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Alias đã về index cũ; run ở trạng thái ROLLED_BACK. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ReindexRun"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["ReindexRunNotFound"];
            409: components["responses"]["ReindexRollbackUnavailable"];
            500: components["responses"]["InternalError"];
            503: components["responses"]["ElasticsearchUnavailable"];
            504: components["responses"]["SearchTimeout"];
        };
    };
}
