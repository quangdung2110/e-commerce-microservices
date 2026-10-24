/**
 * FILE SINH TỰ ĐỘNG — KHÔNG SỬA TAY.
 * Nguồn: contracts/http/user/v1/openapi.yaml
 * Sinh lại bằng `scripts/node.sh npm run gen:api`.
 */
export interface paths {
    "/api/v1/users/me/profile": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Đọc profile của caller hiện tại */
        get: operations["getMyProfile"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        /**
         * Cập nhật một phần profile của caller hiện tại
         * @description Dùng JSON Merge Patch. Field không gửi giữ nguyên; `null` xóa giá trị nullable. `display_name` là read-only và không được gửi trong request.
         */
        patch: operations["patchMyProfile"];
        trace?: never;
    };
    "/api/v1/users/me/preferences": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Đọc preferences của caller hiện tại */
        get: operations["getMyPreferences"];
        /**
         * Ghi toàn bộ notification preferences của caller hiện tại
         * @description `language` và `currency` là read-only (`vi` và `VND`); request chỉ thay đổi ba notification flags. Gửi lại cùng body là idempotent.
         */
        put: operations["replaceMyNotificationPreferences"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/users/me/addresses": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Liệt kê address book của caller hiện tại
         * @description Trả danh sách theo thứ tự address default trước, sau đó `created_at` giảm dần, tối đa 50 address (giới hạn của address book, nên danh sách không phân trang). Server có thể phục vụ từ Redis cache-aside nhưng PostgreSQL là nguồn chân lý. Account đã bị xóa (đang trong retention window) trả `404 PROFILE_NOT_FOUND` như profile đã purge.
         */
        get: operations["listMyAddresses"];
        put?: never;
        /**
         * Tạo address mới cho caller hiện tại
         * @description Address đầu tiên của profile trở thành default. Một profile giữ tối đa 50 address; vượt giới hạn trả `422 INVALID_ADDRESS`. Operation này không có idempotency key; client không được tự retry mù quáng sau timeout.
         */
        post: operations["createMyAddress"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/users/me/addresses/{address_id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Thay thế đầy đủ một address của caller hiện tại
         * @description Gửi lại cùng body là idempotent.
         */
        put: operations["replaceMyAddress"];
        post?: never;
        /**
         * Hard-delete một address
         * @description Address default chỉ xóa được khi nó là address duy nhất còn lại; khi address book còn address khác, owner phải đặt address khác làm default trước, nếu không nhận `409 DEFAULT_ADDRESS_CANNOT_BE_DELETED`. Xóa address cuối cùng làm address book rỗng; address tạo sau đó lại trở thành default.
         */
        delete: operations["deleteMyAddress"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/users/me/addresses/{address_id}/default": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Đặt một address làm default
         * @description Gửi lại cùng request là idempotent.
         */
        put: operations["setMyDefaultAddress"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/internal/v1/users/{user_id}/addresses/{address_id}/checkout-snapshot": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Resolve address snapshot cho Checkout Service
         * @description Endpoint nội bộ chỉ dành cho Checkout, listener riêng không đi qua public API Gateway. Service mesh thực thi mTLS và chỉ cho principal `checkout-service` chạm path này; User thêm allowlist header `X-Service-Identity` làm defense-in-depth (thiếu header `401`, service không nằm trong allowlist `403`). User xác minh address thuộc `user_id` trước khi trả snapshot, và rate limit theo cặp (service gọi, `user_id`).
         */
        get: operations["getCheckoutAddressSnapshot"];
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
        /** Format: uuid */
        Uuid: string;
        /** Format: date-time */
        Timestamp: string;
        /** Format: date */
        NullableDate: string | null;
        NullableShortName: string | null;
        Profile: {
            id: components["schemas"]["Uuid"];
            first_name: components["schemas"]["NullableShortName"];
            last_name: components["schemas"]["NullableShortName"];
            readonly display_name: string;
            date_of_birth: components["schemas"]["NullableDate"];
            /** @enum {string} */
            gender: "MALE" | "FEMALE" | "OTHER" | "UNKNOWN";
            created_at: components["schemas"]["Timestamp"];
            updated_at: components["schemas"]["Timestamp"];
        };
        ProfilePatchRequest: {
            first_name?: components["schemas"]["NullableShortName"];
            last_name?: components["schemas"]["NullableShortName"];
            date_of_birth?: components["schemas"]["NullableDate"];
            /** @enum {string} */
            gender?: "MALE" | "FEMALE" | "OTHER" | "UNKNOWN";
        };
        Preferences: {
            /** @constant */
            readonly language: "vi";
            /** @constant */
            readonly currency: "VND";
            email_enabled: boolean;
            in_app_enabled: boolean;
            /** @description Opt-in: mặc định `false` cho account mới và chỉ bật khi owner đồng ý. User tự tắt cờ này khi Notification báo người dùng đánh dấu spam. */
            marketing_enabled: boolean;
            updated_at: components["schemas"]["Timestamp"];
        };
        NotificationPreferencesUpdateRequest: {
            email_enabled: boolean;
            in_app_enabled: boolean;
            marketing_enabled: boolean;
        };
        /** @description Mã địa giới Việt Nam. V1 kiểm tra format; validation quan hệ province, district và ward cần master-data contract riêng. */
        VietnameseAdministrativeCode: string;
        /** @description Số điện thoại người nhận ở dạng E.164. */
        RecipientPhone: string;
        AddressWriteRequest: {
            recipient_name: string;
            recipient_phone: components["schemas"]["RecipientPhone"];
            address_line: string;
            province_code: components["schemas"]["VietnameseAdministrativeCode"];
            district_code: components["schemas"]["VietnameseAdministrativeCode"];
            ward_code: components["schemas"]["VietnameseAdministrativeCode"];
        };
        Address: {
            id: components["schemas"]["Uuid"];
            recipient_name: string;
            recipient_phone: components["schemas"]["RecipientPhone"];
            address_line: string;
            province_code: components["schemas"]["VietnameseAdministrativeCode"];
            district_code: components["schemas"]["VietnameseAdministrativeCode"];
            ward_code: components["schemas"]["VietnameseAdministrativeCode"];
            is_default: boolean;
            created_at: components["schemas"]["Timestamp"];
            updated_at: components["schemas"]["Timestamp"];
        };
        AddressList: {
            addresses: components["schemas"]["Address"][];
        };
        CheckoutAddressSnapshot: {
            address_id: components["schemas"]["Uuid"];
            recipient_name: string;
            recipient_phone: components["schemas"]["RecipientPhone"];
            address_line: string;
            province_code: components["schemas"]["VietnameseAdministrativeCode"];
            district_code: components["schemas"]["VietnameseAdministrativeCode"];
            ward_code: components["schemas"]["VietnameseAdministrativeCode"];
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
        /** @description JSON, media type hoặc request shape không hợp lệ. */
        BadRequest: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Thiếu hoặc không tin cậy được identity/service credential. */
        Unauthorized: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description `X-Service-Identity` không nằm trong allowlist caller của listener nội bộ. */
        Forbidden: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                /**
                 * @example {
                 *       "type": "https://errors.ecommerce.local/forbidden",
                 *       "title": "Forbidden",
                 *       "status": 403,
                 *       "detail": "This service identity may not resolve address snapshots.",
                 *       "instance": "/internal/v1/users/00000000-0000-0000-0000-000000000000/addresses/00000000-0000-0000-0000-000000000000/checkout-snapshot",
                 *       "code": "FORBIDDEN"
                 *     }
                 */
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Profile chưa bootstrap, đã bị anonymize khi account bị xóa (đang chờ purge) hoặc đã bị purge. Ba trường hợp không thể phân biệt từ phía client. */
        ProfileNotFound: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                /**
                 * @example {
                 *       "type": "https://errors.ecommerce.local/profile-not-found",
                 *       "title": "Profile không tồn tại",
                 *       "status": 404,
                 *       "detail": "Profile không tồn tại.",
                 *       "instance": "/api/v1/users/me/profile",
                 *       "code": "PROFILE_NOT_FOUND"
                 *     }
                 */
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Address không tồn tại hoặc không thuộc user yêu cầu. */
        AddressNotFound: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                /**
                 * @example {
                 *       "type": "https://errors.ecommerce.local/address-not-found",
                 *       "title": "Address không tồn tại",
                 *       "status": 404,
                 *       "detail": "Address không tồn tại.",
                 *       "instance": "/api/v1/users/me/addresses/00000000-0000-0000-0000-000000000000",
                 *       "code": "ADDRESS_NOT_FOUND"
                 *     }
                 */
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Address default phải được thay bằng address khác trước khi xóa. */
        DefaultAddressCannotBeDeleted: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                /**
                 * @example {
                 *       "type": "https://errors.ecommerce.local/default-address-cannot-be-deleted",
                 *       "title": "Không thể xóa address default",
                 *       "status": 409,
                 *       "detail": "Hãy đặt address khác làm default trước.",
                 *       "instance": "/api/v1/users/me/addresses/00000000-0000-0000-0000-000000000000",
                 *       "code": "DEFAULT_ADDRESS_CANNOT_BE_DELETED"
                 *     }
                 */
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Một hoặc nhiều field profile không hợp lệ. */
        InvalidProfileField: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Notification preferences không hợp lệ. */
        InvalidPreference: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Address, recipient phone hoặc mã địa giới không hợp lệ. */
        InvalidAddress: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Gateway hoặc service tạm giới hạn request. */
        RateLimited: {
            headers: {
                "Retry-After": components["headers"]["RetryAfter"];
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Lỗi hạ tầng hoặc lỗi không mong muốn. */
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
        RequestId: string;
        UserId: components["schemas"]["Uuid"];
        AddressId: components["schemas"]["Uuid"];
    };
    requestBodies: never;
    headers: {
        /** @description Seconds to wait before repeating the request. Present on every 429 and on every 503 with retryable=true (contracts ADR-005); a 503 with retryable=false does not carry it. */
        RetryAfter: number;
        /** @description Response chứa PII không được browser hoặc proxy cache. */
        PrivateNoStore: "private, no-store";
        /** @description URI của address mới tạo. */
        AddressLocation: string;
    };
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    getMyProfile: {
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
            /** @description Profile hiện tại. */
            200: {
                headers: {
                    "Cache-Control": components["headers"]["PrivateNoStore"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Profile"];
                };
            };
            401: components["responses"]["Unauthorized"];
            404: components["responses"]["ProfileNotFound"];
            429: components["responses"]["RateLimited"];
            500: components["responses"]["InternalError"];
        };
    };
    patchMyProfile: {
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
                "application/merge-patch+json": components["schemas"]["ProfilePatchRequest"];
            };
        };
        responses: {
            /** @description Profile sau khi cập nhật. */
            200: {
                headers: {
                    "Cache-Control": components["headers"]["PrivateNoStore"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Profile"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            404: components["responses"]["ProfileNotFound"];
            422: components["responses"]["InvalidProfileField"];
            429: components["responses"]["RateLimited"];
            500: components["responses"]["InternalError"];
        };
    };
    getMyPreferences: {
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
            /** @description Preferences hiện tại. */
            200: {
                headers: {
                    "Cache-Control": components["headers"]["PrivateNoStore"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Preferences"];
                };
            };
            401: components["responses"]["Unauthorized"];
            404: components["responses"]["ProfileNotFound"];
            429: components["responses"]["RateLimited"];
            500: components["responses"]["InternalError"];
        };
    };
    replaceMyNotificationPreferences: {
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
                "application/json": components["schemas"]["NotificationPreferencesUpdateRequest"];
            };
        };
        responses: {
            /** @description Preferences sau khi cập nhật. */
            200: {
                headers: {
                    "Cache-Control": components["headers"]["PrivateNoStore"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Preferences"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            404: components["responses"]["ProfileNotFound"];
            422: components["responses"]["InvalidPreference"];
            429: components["responses"]["RateLimited"];
            500: components["responses"]["InternalError"];
        };
    };
    listMyAddresses: {
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
            /** @description Danh sách address của caller. */
            200: {
                headers: {
                    "Cache-Control": components["headers"]["PrivateNoStore"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AddressList"];
                };
            };
            401: components["responses"]["Unauthorized"];
            404: components["responses"]["ProfileNotFound"];
            429: components["responses"]["RateLimited"];
            500: components["responses"]["InternalError"];
        };
    };
    createMyAddress: {
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
                "application/json": components["schemas"]["AddressWriteRequest"];
            };
        };
        responses: {
            /** @description Address đã được tạo. */
            201: {
                headers: {
                    Location: components["headers"]["AddressLocation"];
                    "Cache-Control": components["headers"]["PrivateNoStore"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Address"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            404: components["responses"]["ProfileNotFound"];
            422: components["responses"]["InvalidAddress"];
            429: components["responses"]["RateLimited"];
            500: components["responses"]["InternalError"];
        };
    };
    replaceMyAddress: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                address_id: components["parameters"]["AddressId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AddressWriteRequest"];
            };
        };
        responses: {
            /** @description Address sau khi cập nhật. */
            200: {
                headers: {
                    "Cache-Control": components["headers"]["PrivateNoStore"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Address"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            404: components["responses"]["AddressNotFound"];
            422: components["responses"]["InvalidAddress"];
            429: components["responses"]["RateLimited"];
            500: components["responses"]["InternalError"];
        };
    };
    deleteMyAddress: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                address_id: components["parameters"]["AddressId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Address đã được xóa. */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["Unauthorized"];
            404: components["responses"]["AddressNotFound"];
            409: components["responses"]["DefaultAddressCannotBeDeleted"];
            429: components["responses"]["RateLimited"];
            500: components["responses"]["InternalError"];
        };
    };
    setMyDefaultAddress: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                address_id: components["parameters"]["AddressId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Address đã là default. */
            200: {
                headers: {
                    "Cache-Control": components["headers"]["PrivateNoStore"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Address"];
                };
            };
            401: components["responses"]["Unauthorized"];
            404: components["responses"]["AddressNotFound"];
            429: components["responses"]["RateLimited"];
            500: components["responses"]["InternalError"];
        };
    };
    getCheckoutAddressSnapshot: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                user_id: components["parameters"]["UserId"];
                address_id: components["parameters"]["AddressId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Snapshot address để Checkout persist vào session/Order. */
            200: {
                headers: {
                    "Cache-Control": components["headers"]["PrivateNoStore"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CheckoutAddressSnapshot"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["AddressNotFound"];
            429: components["responses"]["RateLimited"];
            500: components["responses"]["InternalError"];
        };
    };
}
