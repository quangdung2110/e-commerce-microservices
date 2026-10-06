/**
 * FILE SINH TỰ ĐỘNG — KHÔNG SỬA TAY.
 * Nguồn: contracts/http/notification/v1/openapi.yaml
 * Sinh lại bằng `scripts/node.sh npm run gen:api`.
 */
export interface paths {
    "/api/v1/notifications/inbox": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Danh sách thông báo in-app của user hiện tại
         * @description Cursor pagination theo (created_at DESC, id). Chỉ trả item chưa archive. Đọc từ read model inbox_item; item xuất hiện ngay khi deliver worker commit, không có cache trung gian.
         */
        get: operations["listInbox"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/notifications/inbox/unread-count": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Số thông báo chưa đọc, cho badge
         * @description Đọc từ Redis; có thể lệch vài giây so với inbox_item và được recompute khi cache miss. Frontend poll mỗi 30–60 s; không thiết kế cho realtime.
         */
        get: operations["getUnreadCount"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/notifications/inbox/{item_id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /**
         * Ẩn item khỏi inbox (archive, không xoá vật lý)
         * @description Idempotent; archive lần hai vẫn trả 204. Item đã archive không còn xuất hiện trong list và không tính vào unread.
         */
        delete: operations["archiveInboxItem"];
        options?: never;
        head?: never;
        /**
         * Đánh dấu đã đọc / chưa đọc
         * @description Idempotent tự nhiên: đặt read = true trên item đã đọc không đổi gì và vẫn trả 200. Item không thuộc user hiện tại trả 404 (không lộ tồn tại).
         */
        patch: operations["updateInboxItem"];
        trace?: never;
    };
    "/api/v1/notifications/inbox/mark-all-read": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Đánh dấu toàn bộ inbox đã đọc
         * @description Action vì không biểu diễn được bằng một resource. Idempotent: gọi lại khi không còn item chưa đọc vẫn trả 204. Không cần Idempotency-Key vì kết quả cuối không phụ thuộc số lần gọi.
         */
        post: operations["markAllInboxRead"];
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
        InboxItem: {
            /** Format: uuid */
            id: string;
            template: string;
            title: string;
            body: string;
            /** Format: uri-reference */
            deep_link?: string | null;
            /** Format: date-time */
            created_at: string;
            read: boolean;
            /** Format: date-time */
            read_at?: string | null;
        };
        InboxPage: {
            items: components["schemas"]["InboxItem"][];
            /** @description null khi hết trang. */
            next_cursor: string | null;
        };
        UnreadCount: {
            count: number;
            /**
             * Format: date-time
             * @description Thời điểm giá trị được tính; có thể cũ vài giây.
             */
            as_of: string;
        };
        InboxItemUpdate: {
            read: boolean;
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
        /** @description Tham số không hợp lệ (cursor hỏng, limit ngoài khoảng, body sai). */
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
        /** @description Item không tồn tại hoặc không thuộc user hiện tại. */
        NotFound: {
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
        /** @description Bằng delivery_id của kênh in_app. */
        ItemId: string;
    };
    requestBodies: never;
    headers: {
        /** @description Dữ liệu cá nhân; không cache. */
        NoStore: "no-store";
    };
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    listInbox: {
        parameters: {
            query?: {
                /** @description Giá trị next_cursor của trang trước; bỏ trống để lấy trang đầu. */
                cursor?: string;
                limit?: number;
                unread_only?: boolean;
            };
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Một trang inbox; dữ liệu cá nhân, không được shared-cache. */
            200: {
                headers: {
                    "Cache-Control": components["headers"]["NoStore"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["InboxPage"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
        };
    };
    getUnreadCount: {
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
            /** @description Số chưa đọc. */
            200: {
                headers: {
                    "Cache-Control": components["headers"]["NoStore"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["UnreadCount"];
                };
            };
            401: components["responses"]["Unauthorized"];
        };
    };
    archiveInboxItem: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                /** @description Bằng delivery_id của kênh in_app. */
                item_id: components["parameters"]["ItemId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Đã archive. */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["Unauthorized"];
            404: components["responses"]["NotFound"];
        };
    };
    updateInboxItem: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                /** @description Bằng delivery_id của kênh in_app. */
                item_id: components["parameters"]["ItemId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["InboxItemUpdate"];
            };
        };
        responses: {
            /** @description Item sau cập nhật. */
            200: {
                headers: {
                    "Cache-Control": components["headers"]["NoStore"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["InboxItem"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            404: components["responses"]["NotFound"];
        };
    };
    markAllInboxRead: {
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
            /** @description Đã đánh dấu. */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["Unauthorized"];
        };
    };
}
