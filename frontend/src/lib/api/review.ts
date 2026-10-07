import { api } from "@/lib/api/client";
import type { components, operations } from "@/lib/api/generated/review";

type Schemas = components["schemas"];

export type PublicReview = Schemas["PublicReview"];
export type ReviewPage = Schemas["PublicReviewPage"];
export type ReviewAggregate = Schemas["ProductReviewAggregate"];
export type ReportRequest = Schemas["CreateReviewReportRequest"];
export type ReviewParams = NonNullable<operations["listProductReviews"]["parameters"]["query"]>;

export type OwnerReview = Schemas["OwnerReview"];
export type OwnerReviewPage = Schemas["OwnerReviewPage"];
export type ReviewVisibilityStatus = Schemas["ReviewVisibilityStatus"];
export type PendingEligibility = Schemas["PendingEligibility"];
export type PendingEligibilityPage = Schemas["PendingEligibilityPage"];
export type EligibilityReviewStatus = Schemas["MyEligibilityReviewStatus"];
export type CreateReviewRequest = Schemas["CreateReviewRequest"];
export type PatchReviewRequest = Schemas["PatchReviewRequest"];
export type ReviewMediaType = Schemas["ReviewMediaType"];
export type IssueMediaUploadRequest = Schemas["IssueMediaUploadRequest"];
export type IssuedMediaAsset = Schemas["IssuedMediaAsset"];
export type UploadInstruction = Schemas["UploadInstruction"];
export type OwnerListParams = { cursor?: string; limit?: number };

const REVIEW_BASE = "/api/v1/reviews";

export const reviewApi = {
  report: async (reviewId: string, body: ReportRequest): Promise<void> => {
    await api.post<void>(`${REVIEW_BASE}/${encodeURIComponent(reviewId)}/reports`, body);
  },
  list: async (productId: string, params: ReviewParams, signal?: AbortSignal) =>
    (await api.get<ReviewPage>(`${REVIEW_BASE}/products/${encodeURIComponent(productId)}`, { params, signal })).data,

  /** Entitlement PENDING của người mua, sắp hết hạn trước. */
  listPending: async (params: OwnerListParams, signal?: AbortSignal) =>
    (await api.get<PendingEligibilityPage>(`${REVIEW_BASE}/me/pending`, { params, signal })).data,

  /** Review của chính người mua, mới nhất trước, kể cả khi không còn công khai. */
  listMine: async (params: OwnerListParams, signal?: AbortSignal) =>
    (await api.get<OwnerReviewPage>(`${REVIEW_BASE}/me`, { params, signal })).data,

  mine: async (reviewId: string, signal?: AbortSignal) =>
    (await api.get<OwnerReview>(`${REVIEW_BASE}/me/${encodeURIComponent(reviewId)}`, { signal })).data,

  /** Đọc phục hồi sau khi POST tạo review mất phản hồi: CLAIMED trả `review_id`. */
  eligibilityStatus: async (eligibilityId: string, signal?: AbortSignal) =>
    (await api.get<EligibilityReviewStatus>(`${REVIEW_BASE}/me/eligibilities/${encodeURIComponent(eligibilityId)}`, { signal })).data,

  /** Không có Idempotency-Key: X-Request-Id chỉ để truy vết, nên không được gửi lại mù quáng. */
  create: async (body: CreateReviewRequest) =>
    (await api.post<OwnerReview>(REVIEW_BASE, body)).data,

  patch: async (reviewId: string, body: PatchReviewRequest) =>
    (await api.patch<OwnerReview>(`${REVIEW_BASE}/${encodeURIComponent(reviewId)}`, body)).data,

  issueUpload: async (body: IssueMediaUploadRequest) =>
    (await api.post<IssuedMediaAsset>(`${REVIEW_BASE}/media/upload-urls`, body)).data,
};
