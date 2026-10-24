/**
 * FILE SINH TỰ ĐỘNG — KHÔNG SỬA TAY.
 * Nguồn: contracts/http/auth/v1/openapi.yaml
 * Sinh lại bằng `scripts/node.sh npm run gen:api`.
 */
export interface paths {
    "/api/v1/auth/registrations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Tạo account chờ xác minh email */
        post: operations["registerAccount"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/email-verification-requests": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Gửi lại OTP xác minh email
         * @description Luôn trả cùng phản hồi thành công để không tiết lộ email có account chờ xác minh hay không. Mỗi account chỉ được gửi lại 3 lần trong 15 phút; vượt ngân sách đó Auth không gửi nữa nhưng vẫn trả `202`, vì một `429` chỉ xuất hiện với địa chỉ có account chờ xác minh sẽ là chính điều cần giấu. `429` ở đây chỉ đến từ rate limit theo IP.
         */
        post: operations["requestEmailVerification"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/email-verifications": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Xác minh OTP email và kích hoạt account */
        post: operations["verifyEmail"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/email-change-requests": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Yêu cầu đổi email của account hiện tại
         * @description Xác thực password hiện tại, rồi gửi OTP tới email mới. Email chỉ thay đổi sau khi confirmation OTP thành công.
         */
        post: operations["requestEmailChange"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/email-changes/confirmations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Xác minh OTP và hoàn tất đổi email
         * @description Khi thành công, Auth revoke toàn bộ refresh session, đặt `revoked_before` và phát `USER_EMAIL_CHANGED` để thông báo email cũ.
         */
        post: operations["confirmEmailChange"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/sessions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Liệt kê session đang có hiệu lực của account hiện tại */
        get: operations["listSessions"];
        put?: never;
        /**
         * Đăng nhập và tạo session mới
         * @description Mật khẩu được kiểm trước trạng thái account. Sai mật khẩu luôn là `401 INVALID_CREDENTIALS` (email không tồn tại, account chờ xác minh, đang bị khóa hay đang hoạt động đều giống nhau); chỉ người đúng mật khẩu mới thấy `403 ACCOUNT_NOT_VERIFIED` hoặc `423 ACCOUNT_LOCKED`. Lần sai chạm ngưỡng khóa trả `423` kèm `Retry-After`. Đăng nhập đúng sau khi lock hết hạn tự mở khóa account.
         */
        post: operations["login"];
        /**
         * Thu hồi toàn bộ session của account hiện tại
         * @description Thu hồi toàn bộ refresh token và đặt mốc `revoked_before`; Gateway phải từ chối access token được phát hành trước hoặc đúng mốc này. Mốc và `iat` đều có độ phân giải một giây nên token cấp trong cùng giây với lần thu hồi cũng bị từ chối; đây là chủ ý, và client phải đăng nhập lại sau thao tác này.
         */
        delete: operations["revokeAllSessions"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/sessions/refresh": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Rotate refresh token và cấp access token mới
         * @description Cookie `auth_refresh` chỉ thành công một lần. Retry sau timeout không được giả định là idempotent; client phải thực hiện lại login khi cần.
         */
        post: operations["refreshSession"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/sessions/{session_id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /** Thu hồi một session của account hiện tại */
        delete: operations["revokeSession"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/password": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Đổi password của account hiện tại
         * @description Yêu cầu password hiện tại. Khi thành công, toàn bộ refresh session bị thu hồi; client cần login lại.
         */
        put: operations["changePassword"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/password-reset-requests": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Yêu cầu email có opaque reset-token link
         * @description Luôn trả cùng phản hồi thành công dù email có tồn tại hay không. Reset token là opaque, single-use, có TTL và không xuất hiện trong response. Mỗi account chỉ được yêu cầu 5 lần trong 15 phút; vượt ngân sách đó Auth không gửi nữa nhưng vẫn trả `202` (một `429` theo account sẽ lộ account tồn tại). `429` ở đây chỉ đến từ rate limit theo IP. Một yêu cầu mới thay thế link cũ.
         */
        post: operations["requestPasswordReset"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/password-resets": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Đổi password bằng opaque reset token
         * @description Reset token được nhận từ URL fragment phía frontend và gửi trong body HTTPS; token không được ghi log hoặc đưa vào URL của API request.
         */
        post: operations["completePasswordReset"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/accounts/me/deletion-requests": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Yêu cầu xóa account hiện tại theo GDPR
         * @description Yêu cầu password hiện tại. Account được anonymize ngay, session bị thu hồi và bản ghi bị hard delete sau 90 ngày; không hỗ trợ restore.
         */
        post: operations["requestOwnAccountDeletion"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/admin/accounts/{account_id}/deletion-requests": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Xóa account do quản trị viên yêu cầu
         * @description Chỉ trusted Gateway identity có role `ADMIN` được gọi. Lý do xóa luôn là `ADMIN_DELETION`.
         */
        post: operations["requestAdminAccountDeletion"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/.well-known/jwks.json": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Công bố public signing key hiện hành và phase-out key */
        get: operations["getJwks"];
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
        /** Format: email */
        Email: string;
        /**
         * Format: password
         * @description Hỗ trợ khoảng trắng và không ép tổ hợp ký tự; việc kiểm tra password phổ biến/đã lộ là trách nhiệm implementation.
         */
        Password: string;
        /** @description OTP sáu chữ số, single-use và có TTL. */
        Otp: string;
        RegistrationRequest: {
            email: components["schemas"]["Email"];
            password: components["schemas"]["Password"];
            confirm_password: components["schemas"]["Password"];
        };
        RegistrationAccepted: {
            /** @constant */
            status: "PENDING_VERIFICATION";
        };
        EmailRequest: {
            email: components["schemas"]["Email"];
        };
        EmailVerificationRequest: {
            email: components["schemas"]["Email"];
            otp: components["schemas"]["Otp"];
        };
        EmailChangeRequest: {
            current_password: components["schemas"]["Password"];
            new_email: components["schemas"]["Email"];
        };
        EmailChangeConfirmationRequest: {
            new_email: components["schemas"]["Email"];
            otp: components["schemas"]["Otp"];
        };
        LoginRequest: {
            email: components["schemas"]["Email"];
            password: components["schemas"]["Password"];
            device_name?: string;
        };
        AccessTokenResponse: {
            access_token: string;
            /** @constant */
            token_type: "Bearer";
            expires_in: number;
            /**
             * Format: uuid
             * @description `family_id` ổn định của session, không phải refresh-token row ID.
             */
            session_id: string;
        };
        Session: {
            /**
             * Format: uuid
             * @description `family_id` ổn định của session.
             */
            id: string;
            device_name?: string;
            /** @description IPv4 hoặc IPv6 được ghi nhận khi session được tạo. */
            ip_address?: string;
            /** Format: date-time */
            created_at: string;
            /** Format: date-time */
            last_used_at: string;
            /** Format: date-time */
            expires_at: string;
        };
        SessionList: {
            sessions: components["schemas"]["Session"][];
            next_cursor?: string;
        };
        ChangePasswordRequest: {
            current_password: components["schemas"]["Password"];
            new_password: components["schemas"]["Password"];
            confirm_password: components["schemas"]["Password"];
        };
        CompletePasswordResetRequest: {
            reset_token: string;
            new_password: components["schemas"]["Password"];
            confirm_password: components["schemas"]["Password"];
        };
        CurrentPasswordRequest: {
            current_password: components["schemas"]["Password"];
        };
        AccountDeletionAccepted: {
            /** @constant */
            status: "DELETED";
            /** Format: date-time */
            hard_delete_after: string;
        };
        Jwk: {
            /** @constant */
            kty: "RSA";
            /** Format: uuid */
            kid: string;
            /** @constant */
            use: "sig";
            /** @constant */
            alg: "RS256";
            n: string;
            e: string;
        };
        Jwks: {
            keys: components["schemas"]["Jwk"][];
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
        /** @description Yêu cầu đã được chấp nhận mà không tiết lộ account có tồn tại. */
        GenericAccepted: {
            headers: {
                [name: string]: unknown;
            };
            content?: never;
        };
        /** @description Request không hợp lệ. */
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
        /** @description Email hoặc password không hợp lệ. */
        InvalidCredentials: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Refresh token không hợp lệ, hết hạn hoặc đã bị thu hồi. */
        InvalidRefreshToken: {
            headers: {
                "Set-Cookie": components["headers"]["ClearedRefreshCookie"];
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Account chưa xác minh email. */
        AccountNotVerified: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Account đang bị lock tạm thời. */
        AccountLocked: {
            headers: {
                "Retry-After"?: number;
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Trusted identity không có quyền `ADMIN`. */
        Forbidden: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Email đã được đăng ký. */
        EmailAlreadyRegistered: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Account đang ở trạng thái không cho phép thao tác (`ACCOUNT_NOT_ACTIVE`), gồm cả yêu cầu xóa account đã bị xóa. */
        AccountStateConflict: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Session không tồn tại hoặc không thuộc account hiện tại. */
        SessionNotFound: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Account không tồn tại. */
        AccountNotFound: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description OTP không hợp lệ, hết hạn hoặc đã dùng. */
        InvalidOtp: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Reset token không hợp lệ, hết hạn hoặc đã dùng. */
        InvalidResetToken: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Password mới không đạt chính sách bảo mật. */
        PasswordPolicyViolation: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Vượt rate limit. */
        RateLimited: {
            headers: {
                "Retry-After"?: number;
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Lỗi không mong đợi (`INTERNAL_ERROR`), hoặc lỗi retryable: `DEPENDENCY_UNAVAILABLE` khi Redis không trả lời (Auth fail closed; contract chưa định nghĩa `503`) và `CONCURRENT_UPDATE` khi account bị ghi đè liên tục. Với đổi mật khẩu, đổi email và xóa account, thay đổi đã commit trước khi lỗi Redis xảy ra; event vẫn được phát và client có thể gọi `DELETE /api/v1/auth/sessions` để ghi lại mốc thu hồi. */
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
        SessionId: string;
        AccountId: string;
        /** @description Cursor opaque từ `next_cursor` của response trước. */
        Cursor: string;
        Limit: number;
    };
    requestBodies: never;
    headers: {
        /** @description Response chứa credential không được browser/proxy cache. */
        NoStore: "no-store";
        /** @description auth_refresh=<opaque>; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth/sessions; Max-Age=<configured>. Giá trị thật không được log, cache hoặc xuất hiện trong JSON response. */
        RefreshCookie: string;
        /** @description Xóa cookie `auth_refresh` của browser hiện tại bằng cùng Path, `HttpOnly; Secure; SameSite=Strict; Max-Age=0`. */
        ClearedRefreshCookie: string;
    };
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    registerAccount: {
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
                "application/json": components["schemas"]["RegistrationRequest"];
            };
        };
        responses: {
            /** @description Account đã được tạo và email xác minh đã được yêu cầu. */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RegistrationAccepted"];
                };
            };
            400: components["responses"]["BadRequest"];
            409: components["responses"]["EmailAlreadyRegistered"];
            429: components["responses"]["RateLimited"];
            500: components["responses"]["InternalError"];
        };
    };
    requestEmailVerification: {
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
                "application/json": components["schemas"]["EmailRequest"];
            };
        };
        responses: {
            202: components["responses"]["GenericAccepted"];
            400: components["responses"]["BadRequest"];
            429: components["responses"]["RateLimited"];
            500: components["responses"]["InternalError"];
        };
    };
    verifyEmail: {
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
                "application/json": components["schemas"]["EmailVerificationRequest"];
            };
        };
        responses: {
            /** @description Email đã được xác minh. */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            400: components["responses"]["BadRequest"];
            409: components["responses"]["AccountStateConflict"];
            422: components["responses"]["InvalidOtp"];
            429: components["responses"]["RateLimited"];
            500: components["responses"]["InternalError"];
        };
    };
    requestEmailChange: {
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
                "application/json": components["schemas"]["EmailChangeRequest"];
            };
        };
        responses: {
            202: components["responses"]["GenericAccepted"];
            400: components["responses"]["BadRequest"];
            401: components["responses"]["InvalidCredentials"];
            409: components["responses"]["EmailAlreadyRegistered"];
            429: components["responses"]["RateLimited"];
            500: components["responses"]["InternalError"];
        };
    };
    confirmEmailChange: {
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
                "application/json": components["schemas"]["EmailChangeConfirmationRequest"];
            };
        };
        responses: {
            /** @description Email đã được đổi và session đã bị thu hồi. */
            204: {
                headers: {
                    "Set-Cookie": components["headers"]["ClearedRefreshCookie"];
                    [name: string]: unknown;
                };
                content?: never;
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            409: components["responses"]["AccountStateConflict"];
            422: components["responses"]["InvalidOtp"];
            429: components["responses"]["RateLimited"];
            500: components["responses"]["InternalError"];
        };
    };
    listSessions: {
        parameters: {
            query?: {
                /** @description Cursor opaque từ `next_cursor` của response trước. */
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
            /** @description Danh sách session không chứa refresh token hoặc hash. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SessionList"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            500: components["responses"]["InternalError"];
        };
    };
    login: {
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
                "application/json": components["schemas"]["LoginRequest"];
            };
        };
        responses: {
            /** @description Access token được trả trong JSON. Refresh token chỉ được trả qua cookie `auth_refresh`, không xuất hiện trong response body. */
            200: {
                headers: {
                    "Cache-Control": components["headers"]["NoStore"];
                    "Set-Cookie": components["headers"]["RefreshCookie"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AccessTokenResponse"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["InvalidCredentials"];
            403: components["responses"]["AccountNotVerified"];
            423: components["responses"]["AccountLocked"];
            429: components["responses"]["RateLimited"];
            500: components["responses"]["InternalError"];
        };
    };
    revokeAllSessions: {
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
            /** @description Tất cả session đã được thu hồi. */
            204: {
                headers: {
                    "Set-Cookie": components["headers"]["ClearedRefreshCookie"];
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["Unauthorized"];
            500: components["responses"]["InternalError"];
        };
    };
    refreshSession: {
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
            /** @description Refresh token đã được rotate. */
            200: {
                headers: {
                    "Cache-Control": components["headers"]["NoStore"];
                    "Set-Cookie": components["headers"]["RefreshCookie"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AccessTokenResponse"];
                };
            };
            401: components["responses"]["InvalidRefreshToken"];
            429: components["responses"]["RateLimited"];
            500: components["responses"]["InternalError"];
        };
    };
    revokeSession: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                session_id: components["parameters"]["SessionId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Session family đã được thu hồi hoặc đã không còn hiệu lực. Nếu đây là session hiện tại, Auth denylist `jti` và xóa refresh cookie. */
            204: {
                headers: {
                    "Set-Cookie": components["headers"]["ClearedRefreshCookie"];
                    [name: string]: unknown;
                };
                content?: never;
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            404: components["responses"]["SessionNotFound"];
            500: components["responses"]["InternalError"];
        };
    };
    changePassword: {
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
                "application/json": components["schemas"]["ChangePasswordRequest"];
            };
        };
        responses: {
            /** @description Password đã được đổi và session đã bị thu hồi. */
            204: {
                headers: {
                    "Set-Cookie": components["headers"]["ClearedRefreshCookie"];
                    [name: string]: unknown;
                };
                content?: never;
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["InvalidCredentials"];
            422: components["responses"]["PasswordPolicyViolation"];
            500: components["responses"]["InternalError"];
        };
    };
    requestPasswordReset: {
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
                "application/json": components["schemas"]["EmailRequest"];
            };
        };
        responses: {
            202: components["responses"]["GenericAccepted"];
            400: components["responses"]["BadRequest"];
            429: components["responses"]["RateLimited"];
            500: components["responses"]["InternalError"];
        };
    };
    completePasswordReset: {
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
                "application/json": components["schemas"]["CompletePasswordResetRequest"];
            };
        };
        responses: {
            /** @description Password đã được reset; user phải login lại. */
            204: {
                headers: {
                    "Set-Cookie": components["headers"]["ClearedRefreshCookie"];
                    [name: string]: unknown;
                };
                content?: never;
            };
            400: components["responses"]["BadRequest"];
            422: components["responses"]["InvalidResetToken"];
            429: components["responses"]["RateLimited"];
            500: components["responses"]["InternalError"];
        };
    };
    requestOwnAccountDeletion: {
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
                "application/json": components["schemas"]["CurrentPasswordRequest"];
            };
        };
        responses: {
            /** @description Yêu cầu xóa đã được chấp nhận. */
            202: {
                headers: {
                    "Set-Cookie": components["headers"]["ClearedRefreshCookie"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AccountDeletionAccepted"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["InvalidCredentials"];
            409: components["responses"]["AccountStateConflict"];
            500: components["responses"]["InternalError"];
        };
    };
    requestAdminAccountDeletion: {
        parameters: {
            query?: never;
            header?: {
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                account_id: components["parameters"]["AccountId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Yêu cầu xóa admin đã được chấp nhận. */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AccountDeletionAccepted"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["AccountNotFound"];
            409: components["responses"]["AccountStateConflict"];
            500: components["responses"]["InternalError"];
        };
    };
    getJwks: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description JSON Web Key Set; private key không bao giờ xuất hiện. */
            200: {
                headers: {
                    /** @description Gateway có thể cache theo thời gian do Auth cấu hình. */
                    "Cache-Control"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Jwks"];
                };
            };
            500: components["responses"]["InternalError"];
        };
    };
}
