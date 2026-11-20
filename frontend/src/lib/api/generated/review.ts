/**
 * FILE SINH TỰ ĐỘNG — KHÔNG SỬA TAY.
 * Nguồn: contracts/http/review/v1/openapi.yaml
 * Sinh lại bằng `scripts/node.sh npm run gen:api`.
 */
export interface paths {
    "/api/v1/reviews/products/{product_id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Liệt kê review APPROVED của một product
         * @description Product/SKU không được Review xác minh với Catalog ở read path. Product chưa có review hoặc product ID không được biết bởi Review đều trả trang rỗng. Cursor là opaque keyset (created_at, review_id), không dùng OFFSET.
         */
        get: operations["listProductReviews"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/reviews/products/{product_id}/aggregate": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Lấy aggregate rating bất đồng bộ của product
         * @description Không có row aggregate trả các counter bằng 0, không phải 404. Aggregate là eventual projection: review create/edit vừa commit có thể chưa phản ánh ở đây.
         */
        get: operations["getProductReviewAggregate"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/reviews/me/pending": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Liệt kê entitlement PENDING của buyer hiện tại */
        get: operations["listMyPendingReviewEligibilities"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/reviews/me": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Liệt kê review của chính buyer, kể cả khi không còn public
         * @description Mới nhất trước, cursor là opaque keyset (created_at, review_id). Mỗi item là OwnerReview: gồm review HIDDEN_BY_REPORT và REMOVED nhưng không có signed media URL, report, legal record hay takedown reason. Trang đầy limit item luôn có next_cursor; trang kế tiếp có thể rỗng.
         */
        get: operations["listMyReviews"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/reviews/me/eligibilities/{eligibility_id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Xác nhận kết quả create review theo eligibility
         * @description Recovery read khi POST create timeout hoặc mất response. Một eligibility CLAIMED trả review_id, nên client không retry create command mù quáng.
         */
        get: operations["getMyEligibilityReviewStatus"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/reviews/me/{review_id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Đọc review của chính buyer, kể cả khi không còn public
         * @description Recovery read khi PATCH timeout hoặc mất response. Response không chứa report, legal record hay takedown reason; visibility_status chỉ dành cho owner.
         */
        get: operations["getMyReview"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/reviews": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Tạo review cho một entitlement PENDING
         * @description Review xác minh metadata của mỗi uploaded media asset trước transaction rồi atomically claim entitlement, attach asset và ghi REVIEW_CREATED vào outbox. Review tự sinh pseudonym tác giả từ trusted X-User-Id; không nhận profile name hoặc email từ client/Gateway. X-Request-Id chỉ dùng tracing, không phải idempotency key. Client nhận timeout phải dùng recovery read theo eligibility_id.
         */
        post: operations["createReview"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/reviews/{review_id}": {
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
         * Sửa review đúng một lần
         * @description Missing field được giữ nguyên; content:null xóa nội dung; missing media_asset_ids giữ attachment và [] gỡ toàn bộ attachment. Asset mới được xác minh trước transaction. PATCH thành công luôn tiêu quota sửa, kể cả khi chỉ đổi content; recovery read phải được dùng sau timeout.
         */
        patch: operations["patchReview"];
        trace?: never;
    };
    "/api/v1/reviews/{review_id}/reports": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Report một review APPROVED
         * @description Một caller có identity còn tồn tại chỉ có một active report/review. Sau khi admin dismiss report cũ, caller có thể report lại. Response thành công cố ý không có body để không tiết lộ active_report_count hay review có vừa chạm ngưỡng ẩn hay không.
         */
        post: operations["reportReview"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/reviews/media/upload-urls": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Cấp signed direct-upload instruction cho media asset
         * @description Caller upload byte trực tiếp vào storage. Response không có object key và API không có verify/poll endpoint; create/edit tự xác minh object immutable trước khi attach. Server/Gateway giới hạn số asset chưa attach và byte theo media type.
         */
        post: operations["issueReviewMediaUploadUrl"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/reviews": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Liệt kê review cần kiểm duyệt
         * @description Surface moderation duy nhất của admin. Đơn vị là review, không phải report: một review bị ba report là một case một dòng, và report-dismissals cũng thao tác theo review. status=REPORTED là review còn public nhưng đã có report chưa dismiss, xử lý được trước khi threshold tự ẩn nó. status=HIDDEN_BY_REPORT là review đã bị ẩn; hide chuyển mọi report PENDING thành ACTIONED nên đây là đường duy nhất lấy lại review_id cho dismiss. Cả hai không trả reporter_user_id.
         */
        get: operations["listReviewsUnderModeration"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/reviews/{review_id}/report-dismissals": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Dismiss toàn bộ active report của review
         * @description Idempotent theo outcome: review không còn active report vẫn trả kết quả hiện tại. Khi review đang HIDDEN_BY_REPORT, command restore APPROVED và ghi REVIEW_APPROVED vào outbox; không có manual admin-hide operation.
         */
        post: operations["dismissReviewReports"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/reviews/{review_id}/reply": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Tạo hoặc thay thế official reply duy nhất
         * @description PUT là upsert idempotent cấp resource. Chỉ lần tạo đầu tiên phát REVIEW_REPLY_NOTIFICATION_REQUESTED khi eligibility owner không có HMAC deletion guard; guard không từ chối reply nhưng suppress notification request. Thay thế nội dung không phát notification mới.
         */
        put: operations["putReviewAdminReply"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/reviews/{review_id}/takedown": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Thực hiện legal takedown không thể đảo ngược
         * @description Một admin có role phù hợp thực hiện command. Review lấy actor từ trusted Gateway identity và persist taken_down_by_admin_id; request body không có requester hay approver ID. Legal case, reason và identity admin không đi vào public response hoặc event payload. Review chỉ tạo removal notification request khi eligibility owner không có HMAC deletion guard; guard không từ chối takedown nhưng suppress notification request.
         */
        post: operations["takeDownReview"];
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
         * @description Opaque identifier owned by Review.
         */
        Uuid: string;
        ProductId: string;
        SkuId: string;
        /** @description JSON integer token from 1 to 5. Decimal tokens (including 1.0) and numeric strings are rejected with 400 INVALID_REQUEST without consuming eligibility or edit quota. */
        Rating: number;
        /** @enum {string} */
        ReviewVisibilityStatus: "APPROVED" | "HIDDEN_BY_REPORT" | "REMOVED";
        /** @enum {string} */
        ReviewMediaType: "IMAGE" | "VIDEO";
        /** @enum {string} */
        ReviewReportReason: "SPAM" | "OFFENSIVE" | "FAKE" | "OTHER";
        /**
         * @description ACTIONED nghĩa là report đã góp phần ẩn review, không phải đã được admin phân xử. Chỉ DISMISSED mới do admin quyết.
         * @enum {string}
         */
        ReviewReportStatus: "PENDING" | "ACTIONED" | "DISMISSED";
        /** @enum {string} */
        EligibilityStatus: "PENDING" | "CLAIMED" | "EXPIRED";
        PublicReviewMedia: {
            asset_id: components["schemas"]["Uuid"];
            media_type: components["schemas"]["ReviewMediaType"];
            /**
             * Format: uri
             * @description Short-lived signed download URL generated for this response only, using the browser-reachable storage origin (REVIEW_S3_PUBLIC_ENDPOINT, falling back to REVIEW_S3_ENDPOINT). Clients must use the URL unchanged; replacing its host/path breaks SigV4. Do not shared-cache it beyond url_expires_at.
             */
            url: string;
            /** Format: date-time */
            url_expires_at: string;
            sort_order: number;
        };
        OfficialReply: {
            content: string;
            /** Format: date-time */
            created_at: string;
            /** Format: date-time */
            updated_at: string;
        };
        PublicReview: {
            review_id: components["schemas"]["Uuid"];
            product_id: components["schemas"]["ProductId"];
            sku_id: components["schemas"]["SkuId"];
            author_name: string;
            variant_name: string | null;
            rating: components["schemas"]["Rating"];
            content: string | null;
            has_media: boolean;
            media: components["schemas"]["PublicReviewMedia"][];
            admin_reply: components["schemas"]["OfficialReply"] | null;
            /** Format: date-time */
            created_at: string;
            /** Format: date-time */
            updated_at: string;
        };
        OwnerReview: {
            review_id: components["schemas"]["Uuid"];
            product_id: components["schemas"]["ProductId"];
            sku_id: components["schemas"]["SkuId"];
            author_name: string;
            variant_name: string | null;
            /** @description Tên sản phẩm lúc mua, snapshot từ ORDER_COMPLETED; null với entitlement cấp trước khi có snapshot. Chỉ owner thấy; PublicReview không có field này. */
            product_name: string | null;
            /** @description Thumbnail lúc mua, cùng snapshot với product_name; null khi không có. */
            product_image_url: string | null;
            rating: components["schemas"]["Rating"];
            content: string | null;
            has_media: boolean;
            /** @description Empty when review is not APPROVED; signed URL is never issued for hidden/removed media. */
            media: components["schemas"]["PublicReviewMedia"][];
            admin_reply: components["schemas"]["OfficialReply"] | null;
            visibility_status: components["schemas"]["ReviewVisibilityStatus"];
            edit_count: number;
            /** Format: date-time */
            created_at: string;
            /**
             * Format: date-time
             * @description Last record update, including moderation/privacy; not the author edit time.
             */
            updated_at: string;
            /**
             * Format: date-time
             * @description Transaction time of the author's accepted PATCH (rating, content or media). Null before any edit or for legacy edited reviews whose original edit event is no longer available or unambiguous. Reporting, moderation, official replies and privacy processing never change it. Never infer it from updated_at.
             */
            readonly content_edited_at?: string | null;
        };
        PublicReviewPage: {
            items: components["schemas"]["PublicReview"][];
            next_cursor: string | null;
        };
        ProductReviewAggregate: {
            average_rating: number;
            /** Format: int64 */
            total_reviews: number;
            /** Format: int64 */
            total_with_media: number;
        };
        PendingEligibility: {
            eligibility_id: components["schemas"]["Uuid"];
            /** @description Opaque Order ID của đơn đã mua; chỉ để dẫn buyer về đúng đơn, không parse. */
            order_id: string;
            product_id: components["schemas"]["ProductId"];
            sku_id: components["schemas"]["SkuId"];
            variant_name: string | null;
            /** @description Tên sản phẩm lúc mua, snapshot từ ORDER_COMPLETED; null với entitlement cấp trước khi có snapshot. */
            product_name: string | null;
            /** @description Thumbnail lúc mua, cùng snapshot với product_name; null khi không có. */
            product_image_url: string | null;
            /** Format: date-time */
            expires_at: string;
        };
        OwnerReviewPage: {
            items: components["schemas"]["OwnerReview"][];
            next_cursor: string | null;
        };
        PendingEligibilityPage: {
            items: components["schemas"]["PendingEligibility"][];
            next_cursor: string | null;
        };
        MyEligibilityReviewStatus: {
            eligibility_id: components["schemas"]["Uuid"];
            status: components["schemas"]["EligibilityStatus"];
            /** Format: date-time */
            expires_at: string;
            review_id: components["schemas"]["Uuid"] | null;
        };
        CreateReviewRequest: {
            eligibility_id: components["schemas"]["Uuid"];
            rating: components["schemas"]["Rating"];
            /** @description Must be non-blank after trim when non-null. */
            content?: string | null;
            media_asset_ids?: components["schemas"]["Uuid"][];
        };
        PatchReviewRequest: {
            rating?: components["schemas"]["Rating"];
            /** @description Null clears content; a missing field preserves it; non-null value must be non-blank after trim. */
            content?: string | null;
            /** @description Missing preserves attachments; [] removes all; supplied IDs replace attachments and must be caller-owned uploaded, unattached assets. Retained attachment reordering is not supported in v1. */
            media_asset_ids?: components["schemas"]["Uuid"][];
        };
        CreateReviewReportRequest: {
            reason: components["schemas"]["ReviewReportReason"];
            detail?: string | null;
        };
        IssueMediaUploadRequest: {
            media_type: components["schemas"]["ReviewMediaType"];
            /** @description Must be a type the service accepts for `media_type`: image/jpeg, image/png or image/webp for IMAGE; video/mp4 or video/webm for VIDEO (case-insensitive). The list is service configuration (`review.media.*-content-types`); any other value is `400 INVALID_REQUEST`. */
            content_type: string;
            /**
             * Format: int64
             * @description Declared object size in bytes; the object that is uploaded must have exactly this size. Must be a JSON integer token; decimal tokens and numeric strings are rejected with 400 INVALID_REQUEST without issuing an asset or consuming quota. Above the configured limit for `media_type` (default 10 MiB for IMAGE, 100 MiB for VIDEO) is `400 INVALID_REQUEST`.
             */
            byte_size: number;
            /** @description Optional. Compared, case-insensitively, with the storage ETag of the uploaded object when the asset is attached, so it must be the hex MD5 digest of the body for a single-part upload (what S3-compatible storage reports as the ETag). A mismatch makes the asset `MEDIA_NOT_VERIFIED`. */
            checksum?: string;
        };
        UploadInstruction: {
            /** @constant */
            method: "PUT";
            /** Format: uri */
            url: string;
            /** @description Required storage headers, including signed If-None-Match '*'. Client must preserve signed headers (Content-Length is set by the browser). Storage rejects subsequent PUTs to the same object with 412 without changing bytes, including after attachment. Removing the conditional header invalidates the signature. Client must not add a different object key or overwrite target. */
            headers: {
                [key: string]: string;
            };
        };
        IssuedMediaAsset: {
            asset_id: components["schemas"]["Uuid"];
            /** Format: date-time */
            expires_at: string;
            upload: components["schemas"]["UploadInstruction"];
        };
        /**
         * @description REPORTED là review APPROVED còn active_report_count > 0, tức case chưa chạm threshold và admin còn kịp dismiss trước khi nó bị tự ẩn. HIDDEN_BY_REPORT là review đã bị ẩn. Mỗi giá trị có partial index riêng; thêm giá trị mới đòi thêm index vì mọi index review khác đều partial theo APPROVED.
         * @enum {string}
         */
        ModerationQueueStatus: "REPORTED" | "HIDDEN_BY_REPORT";
        /** @description Media của review đang bị ẩn, chỉ cấp trên admin moderation surface. Không có signed URL nào cho media này ở public hay owner surface. Một report về ảnh/video không thể phân xử nếu thiếu nó. */
        AdminReviewMedia: {
            asset_id: components["schemas"]["Uuid"];
            media_type: components["schemas"]["ReviewMediaType"];
            /**
             * Format: uri
             * @description Short-lived signed download URL sinh riêng cho response này. TTL phải ngắn hơn TTL của public read URL.
             */
            url: string;
            /** Format: date-time */
            url_expires_at: string;
            sort_order: number;
        };
        AdminReviewSnapshot: {
            review_id: components["schemas"]["Uuid"];
            product_id: components["schemas"]["ProductId"];
            sku_id: components["schemas"]["SkuId"];
            rating: components["schemas"]["Rating"];
            content: string | null;
            has_media: boolean;
            /** @description Rỗng khi review chưa có media. Signed URL được cấp kể cả khi review đang HIDDEN_BY_REPORT, chỉ trên surface admin này. */
            media: components["schemas"]["AdminReviewMedia"][];
            visibility_status: components["schemas"]["ReviewVisibilityStatus"];
            active_report_count: number;
            /**
             * Format: date-time
             * @description Lần gần nhất report threshold ẩn review; null nếu chưa từng bị ẩn. Giá trị này được giữ lại sau khi dismiss khôi phục review nên có thể khác null ngay cả khi visibility_status là APPROVED.
             */
            hidden_at: string | null;
            /**
             * Format: date-time
             * @description Report chưa dismiss đầu tiên của chu kỳ hiện tại; null khi không còn active report.
             */
            first_reported_at: string | null;
            /** Format: date-time */
            created_at: string;
        };
        /** @description Một review cần phân xử kèm toàn bộ ngữ cảnh để quyết trong một lần đọc. Dùng review.review_id làm path parameter cho report-dismissals. */
        AdminModerationCase: {
            review: components["schemas"]["AdminReviewSnapshot"];
            /** @description Report chưa DISMISSED của review, cũ trước. Không có reporter_user_id. Bị cắt ở 50; active_report_count mới là con số đầy đủ. */
            reports: components["schemas"]["AdminModerationCaseReport"][];
            /**
             * Format: date-time
             * @description Report chưa dismiss đầu tiên của chu kỳ hiện tại; khóa sắp xếp của cả hai status.
             */
            first_reported_at: string;
            /**
             * Format: date-time
             * @description Null khi review chưa từng bị ẩn. Dismiss cố ý giữ lại hidden_at khi khôi phục review, nên một case status=REPORTED vẫn có thể mang hidden_at khác null nếu review từng bị ẩn ở chu kỳ trước.
             */
            hidden_at: string | null;
        };
        AdminModerationCaseReport: {
            report_id: components["schemas"]["Uuid"];
            reason: components["schemas"]["ReviewReportReason"];
            detail: string | null;
            status: components["schemas"]["ReviewReportStatus"];
            /** Format: date-time */
            created_at: string;
        };
        AdminModerationCasePage: {
            items: components["schemas"]["AdminModerationCase"][];
            next_cursor: string | null;
        };
        AdminModerationResult: {
            review_id: components["schemas"]["Uuid"];
            visibility_status: components["schemas"]["ReviewVisibilityStatus"];
            dismissed_report_count: number;
            active_report_count: number;
        };
        PutAdminReplyRequest: {
            /** @description Must be non-blank after trim. */
            content: string;
        };
        CreateTakedownRequest: {
            legal_case_id: string;
            /** @description Internal-only reason; must be non-blank after trim. */
            reason: string;
        };
        TakedownReceipt: {
            takedown_id: components["schemas"]["Uuid"];
            review_id: components["schemas"]["Uuid"];
            legal_case_id: string;
            /** Format: date-time */
            created_at: string;
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
        /** @description Validation failed; response code identifies the invalid field or domain input. */
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
        /** @description Caller does not have the configured Review admin role. */
        AdminForbidden: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description NOT_ELIGIBILITY_OWNER; entitlement belongs to another caller or its owner identity was purged. */
        NotEligibilityOwner: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description NOT_REVIEW_OWNER. */
        NotReviewOwner: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description ELIGIBILITY_NOT_FOUND. */
        EligibilityNotFound: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description ELIGIBILITY_EXPIRED; client must not retry create for this entitlement. */
        EligibilityExpired: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description REVIEW_NOT_FOUND or a review not visible to this endpoint. */
        ReviewNotFound: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description ALREADY_REVIEWED, INVALID_MEDIA_REFERENCE or MEDIA_NOT_VERIFIED. All media rejections are 409: INVALID_MEDIA_REFERENCE when the asset_id does not resolve to a usable asset of the caller (unknown and foreign are deliberately indistinguishable), MEDIA_NOT_VERIFIED when the caller owns it but its object or state is not usable and a re-upload can recover. */
        CreateReviewConflict: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description EDIT_QUOTA_EXHAUSTED, REVIEW_NOT_EDITABLE, INVALID_MEDIA_REFERENCE or MEDIA_NOT_VERIFIED, with the same media semantics as create. */
        PatchReviewConflict: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description REVIEW_REPORT_FORBIDDEN, for example caller is the author. */
        ReviewReportForbidden: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description ALREADY_REPORTED, and never any moderation progress. REVIEW_NOT_REPORTABLE is reserved and is not produced in v1: a review that is not APPROVED is not on the public surface, so reporting it answers 404 REVIEW_NOT_FOUND instead of disclosing that it was hidden or removed. */
        ReportConflict: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description REVIEW_NOT_REPLYABLE because the review is not APPROVED. */
        ReviewNotReplyable: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description TAKEDOWN_ALREADY_EXISTS; REMOVED is terminal. */
        TakedownAlreadyExists: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description Unattached media asset or byte quota exceeded. */
        MediaQuotaExceeded: {
            headers: {
                "Retry-After"?: number;
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["problem.schema"];
            };
        };
        /** @description PostgreSQL, storage metadata read, lock or signer dependency is temporarily unavailable; problem.retryable=true. */
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
        /** @description Correlation/tracing identifier only; it is not an idempotency key. Review echoes it as the Problem `trace_id` only when it is 16..64 characters, the bounds of that schema; a shorter or longer value is accepted but not echoed. */
        RequestId: string;
        ProductId: components["schemas"]["ProductId"];
        ReviewId: components["schemas"]["Uuid"];
        EligibilityId: components["schemas"]["Uuid"];
        /** @description Opaque cursor from next_cursor of the previous page. */
        Cursor: string;
        PublicLimit: number;
        AdminLimit: number;
        SkuIdQuery: components["schemas"]["SkuId"];
        RatingQuery: components["schemas"]["Rating"];
        /** @description Chỉ liệt kê state cần admin hành động. Required để một client quên filter không vô tình phân trang toàn bộ review của hệ thống. */
        ModerationStatusQuery: components["schemas"]["ModerationQueueStatus"];
        /** @description Khi có mặt chỉ nhận true; false tương đương không filter và bị từ chối để giữ contract/index rõ ràng. */
        HasMediaQuery: true;
    };
    requestBodies: never;
    headers: {
        /** @description URI của review vừa tạo. */
        ReviewLocation: string;
    };
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    listProductReviews: {
        parameters: {
            query?: {
                /** @description Opaque cursor from next_cursor of the previous page. */
                cursor?: components["parameters"]["Cursor"];
                limit?: components["parameters"]["PublicLimit"];
                sku_id?: components["parameters"]["SkuIdQuery"];
                rating?: components["parameters"]["RatingQuery"];
                /** @description Khi có mặt chỉ nhận true; false tương đương không filter và bị từ chối để giữ contract/index rõ ràng. */
                has_media?: components["parameters"]["HasMediaQuery"];
            };
            header?: {
                /** @description Correlation/tracing identifier only; it is not an idempotency key. Review echoes it as the Problem `trace_id` only when it is 16..64 characters, the bounds of that schema; a shorter or longer value is accepted but not echoed. */
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                product_id: components["parameters"]["ProductId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Review APPROVED theo newest-first; signed media URL chỉ tồn tại trong response. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PublicReviewPage"];
                };
            };
            400: components["responses"]["BadRequest"];
            503: components["responses"]["RetryableUnavailable"];
        };
    };
    getProductReviewAggregate: {
        parameters: {
            query?: never;
            header?: {
                /** @description Correlation/tracing identifier only; it is not an idempotency key. Review echoes it as the Problem `trace_id` only when it is 16..64 characters, the bounds of that schema; a shorter or longer value is accepted but not echoed. */
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                product_id: components["parameters"]["ProductId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Aggregate hiện tại hoặc zero aggregate. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ProductReviewAggregate"];
                };
            };
            400: components["responses"]["BadRequest"];
            503: components["responses"]["RetryableUnavailable"];
        };
    };
    listMyPendingReviewEligibilities: {
        parameters: {
            query?: {
                /** @description Opaque cursor from next_cursor of the previous page. */
                cursor?: components["parameters"]["Cursor"];
                limit?: components["parameters"]["PublicLimit"];
            };
            header?: {
                /** @description Correlation/tracing identifier only; it is not an idempotency key. Review echoes it as the Problem `trace_id` only when it is 16..64 characters, the bounds of that schema; a shorter or longer value is accepted but not echoed. */
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Entitlement PENDING của caller, sắp expiry tăng dần. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PendingEligibilityPage"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            503: components["responses"]["RetryableUnavailable"];
        };
    };
    listMyReviews: {
        parameters: {
            query?: {
                /** @description Opaque cursor from next_cursor of the previous page. */
                cursor?: components["parameters"]["Cursor"];
                limit?: components["parameters"]["PublicLimit"];
            };
            header?: {
                /** @description Correlation/tracing identifier only; it is not an idempotency key. Review echoes it as the Problem `trace_id` only when it is 16..64 characters, the bounds of that schema; a shorter or longer value is accepted but not echoed. */
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Review của caller, mới nhất trước. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OwnerReviewPage"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            503: components["responses"]["RetryableUnavailable"];
        };
    };
    getMyEligibilityReviewStatus: {
        parameters: {
            query?: never;
            header?: {
                /** @description Correlation/tracing identifier only; it is not an idempotency key. Review echoes it as the Problem `trace_id` only when it is 16..64 characters, the bounds of that schema; a shorter or longer value is accepted but not echoed. */
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                eligibility_id: components["parameters"]["EligibilityId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Trạng thái entitlement hiện tại của caller. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MyEligibilityReviewStatus"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["NotEligibilityOwner"];
            404: components["responses"]["EligibilityNotFound"];
            503: components["responses"]["RetryableUnavailable"];
        };
    };
    getMyReview: {
        parameters: {
            query?: never;
            header?: {
                /** @description Correlation/tracing identifier only; it is not an idempotency key. Review echoes it as the Problem `trace_id` only when it is 16..64 characters, the bounds of that schema; a shorter or longer value is accepted but not echoed. */
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                review_id: components["parameters"]["ReviewId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Review thuộc caller. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OwnerReview"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["NotReviewOwner"];
            404: components["responses"]["ReviewNotFound"];
            503: components["responses"]["RetryableUnavailable"];
        };
    };
    createReview: {
        parameters: {
            query?: never;
            header?: {
                /** @description Correlation/tracing identifier only; it is not an idempotency key. Review echoes it as the Problem `trace_id` only when it is 16..64 characters, the bounds of that schema; a shorter or longer value is accepted but not echoed. */
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateReviewRequest"];
            };
        };
        responses: {
            /** @description Review APPROVED đã commit; rating aggregate có thể chưa hội tụ. */
            201: {
                headers: {
                    Location: components["headers"]["ReviewLocation"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OwnerReview"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["NotEligibilityOwner"];
            404: components["responses"]["EligibilityNotFound"];
            409: components["responses"]["CreateReviewConflict"];
            410: components["responses"]["EligibilityExpired"];
            503: components["responses"]["RetryableUnavailable"];
        };
    };
    patchReview: {
        parameters: {
            query?: never;
            header?: {
                /** @description Correlation/tracing identifier only; it is not an idempotency key. Review echoes it as the Problem `trace_id` only when it is 16..64 characters, the bounds of that schema; a shorter or longer value is accepted but not echoed. */
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                review_id: components["parameters"]["ReviewId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PatchReviewRequest"];
            };
        };
        responses: {
            /** @description Review đã sửa và REVIEW_UPDATED đã được ghi vào outbox. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OwnerReview"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["NotReviewOwner"];
            404: components["responses"]["ReviewNotFound"];
            409: components["responses"]["PatchReviewConflict"];
            503: components["responses"]["RetryableUnavailable"];
        };
    };
    reportReview: {
        parameters: {
            query?: never;
            header?: {
                /** @description Correlation/tracing identifier only; it is not an idempotency key. Review echoes it as the Problem `trace_id` only when it is 16..64 characters, the bounds of that schema; a shorter or longer value is accepted but not echoed. */
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                review_id: components["parameters"]["ReviewId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateReviewReportRequest"];
            };
        };
        responses: {
            /** @description Report đã được nhận mà không tiết lộ kết quả moderation. */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["ReviewReportForbidden"];
            404: components["responses"]["ReviewNotFound"];
            409: components["responses"]["ReportConflict"];
            503: components["responses"]["RetryableUnavailable"];
        };
    };
    issueReviewMediaUploadUrl: {
        parameters: {
            query?: never;
            header?: {
                /** @description Correlation/tracing identifier only; it is not an idempotency key. Review echoes it as the Problem `trace_id` only when it is 16..64 characters, the bounds of that schema; a shorter or longer value is accepted but not echoed. */
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["IssueMediaUploadRequest"];
            };
        };
        responses: {
            /** @description Asset ISSUED và upload instruction single-write/no-overwrite. */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["IssuedMediaAsset"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            429: components["responses"]["MediaQuotaExceeded"];
            503: components["responses"]["RetryableUnavailable"];
        };
    };
    listReviewsUnderModeration: {
        parameters: {
            query: {
                /** @description Chỉ liệt kê state cần admin hành động. Required để một client quên filter không vô tình phân trang toàn bộ review của hệ thống. */
                status: components["parameters"]["ModerationStatusQuery"];
                /** @description Opaque cursor from next_cursor of the previous page. */
                cursor?: components["parameters"]["Cursor"];
                limit?: components["parameters"]["AdminLimit"];
            };
            header?: {
                /** @description Correlation/tracing identifier only; it is not an idempotency key. Review echoes it as the Problem `trace_id` only when it is 16..64 characters, the bounds of that schema; a shorter or longer value is accepted but not echoed. */
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Case theo first_reported_at tăng dần, case mở lâu nhất trước, cùng một nghĩa sắp xếp cho cả hai status. Signed media URL chỉ tồn tại trong response này. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AdminModerationCasePage"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["AdminForbidden"];
            503: components["responses"]["RetryableUnavailable"];
        };
    };
    dismissReviewReports: {
        parameters: {
            query?: never;
            header?: {
                /** @description Correlation/tracing identifier only; it is not an idempotency key. Review echoes it as the Problem `trace_id` only when it is 16..64 characters, the bounds of that schema; a shorter or longer value is accepted but not echoed. */
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                review_id: components["parameters"]["ReviewId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Active reports đã dismissed; chỉ admin thấy counter kết quả. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AdminModerationResult"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["AdminForbidden"];
            404: components["responses"]["ReviewNotFound"];
            503: components["responses"]["RetryableUnavailable"];
        };
    };
    putReviewAdminReply: {
        parameters: {
            query?: never;
            header?: {
                /** @description Correlation/tracing identifier only; it is not an idempotency key. Review echoes it as the Problem `trace_id` only when it is 16..64 characters, the bounds of that schema; a shorter or longer value is accepted but not echoed. */
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                review_id: components["parameters"]["ReviewId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PutAdminReplyRequest"];
            };
        };
        responses: {
            /** @description Official reply hiện tại. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OfficialReply"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["AdminForbidden"];
            404: components["responses"]["ReviewNotFound"];
            409: components["responses"]["ReviewNotReplyable"];
            503: components["responses"]["RetryableUnavailable"];
        };
    };
    takeDownReview: {
        parameters: {
            query?: never;
            header?: {
                /** @description Correlation/tracing identifier only; it is not an idempotency key. Review echoes it as the Problem `trace_id` only when it is 16..64 characters, the bounds of that schema; a shorter or longer value is accepted but not echoed. */
                "X-Request-Id"?: components["parameters"]["RequestId"];
            };
            path: {
                review_id: components["parameters"]["ReviewId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateTakedownRequest"];
            };
        };
        responses: {
            /** @description Review REMOVED, audit record và outbox record đã commit. */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TakedownReceipt"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["AdminForbidden"];
            404: components["responses"]["ReviewNotFound"];
            409: components["responses"]["TakedownAlreadyExists"];
            503: components["responses"]["RetryableUnavailable"];
        };
    };
}
