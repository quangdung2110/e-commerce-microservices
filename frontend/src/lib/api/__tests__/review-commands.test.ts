import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/lib/api/client", () => ({
  api: { get: vi.fn(), post: vi.fn(), patch: vi.fn(), delete: vi.fn() },
}));

import { api } from "@/lib/api/client";
import { httpError, networkError, problem } from "@/lib/api/__tests__/helpers";
import { notificationApi } from "@/lib/api/notification";
import { reviewApi, type OwnerReview } from "@/lib/api/review";
import { createReviewWithRecovery, isUnknownOutcome, patchReviewWithRecovery } from "@/lib/api/review-commands";

const eligibilityId = "7f1c5a52-0000-4000-8000-000000000001";
const reviewId = "7f1c5a52-0000-4000-8000-000000000002";

const owner = (overrides: Partial<OwnerReview> = {}): OwnerReview => ({
  review_id: reviewId,
  product_id: "p-1",
  sku_id: "sku-1",
  author_name: "Khách hàng #AAAAAAAAAAAA",
  variant_name: null,
  product_name: "Áo thun",
  product_image_url: null,
  rating: 5,
  content: "Tốt",
  has_media: false,
  media: [],
  admin_reply: null,
  visibility_status: "APPROVED",
  edit_count: 0,
  created_at: "2026-10-01T00:00:00Z",
  updated_at: "2026-10-01T00:00:00Z",
  ...overrides,
});

beforeEach(() => {
  vi.clearAllMocks();
});

describe("reviewApi / notificationApi", () => {
  it("gọi đúng endpoint owner của Review và encode ID", async () => {
    vi.mocked(api.get).mockResolvedValue({ data: {} });
    const signal = new AbortController().signal;
    await reviewApi.listPending({ limit: 20, cursor: "c" }, signal);
    await reviewApi.listMine({ limit: 20 }, signal);
    await reviewApi.mine("a/b", signal);
    await reviewApi.eligibilityStatus(eligibilityId, signal);

    expect(api.get).toHaveBeenNthCalledWith(1, "/api/v1/reviews/me/pending", { params: { limit: 20, cursor: "c" }, signal });
    expect(api.get).toHaveBeenNthCalledWith(2, "/api/v1/reviews/me", { params: { limit: 20 }, signal });
    expect(api.get).toHaveBeenNthCalledWith(3, "/api/v1/reviews/me/a%2Fb", { signal });
    expect(api.get).toHaveBeenNthCalledWith(4, `/api/v1/reviews/me/eligibilities/${eligibilityId}`, { signal });
  });

  it("tạo, sửa và xin upload không gửi Idempotency-Key", async () => {
    vi.mocked(api.post).mockResolvedValue({ data: {} });
    vi.mocked(api.patch).mockResolvedValue({ data: {} });
    await reviewApi.create({ eligibility_id: eligibilityId, rating: 4 });
    await reviewApi.patch(reviewId, { content: null });
    await reviewApi.issueUpload({ media_type: "IMAGE", content_type: "image/png", byte_size: 1024 });

    expect(api.post).toHaveBeenNthCalledWith(1, "/api/v1/reviews", { eligibility_id: eligibilityId, rating: 4 });
    expect(api.patch).toHaveBeenCalledWith(`/api/v1/reviews/${reviewId}`, { content: null });
    expect(api.post).toHaveBeenNthCalledWith(2, "/api/v1/reviews/media/upload-urls", { media_type: "IMAGE", content_type: "image/png", byte_size: 1024 });
  });

  it("hộp thư dùng đúng verb và đường dẫn", async () => {
    vi.mocked(api.get).mockResolvedValue({ data: {} });
    vi.mocked(api.patch).mockResolvedValue({ data: {} });
    vi.mocked(api.delete).mockResolvedValue({ data: undefined });
    vi.mocked(api.post).mockResolvedValue({ data: undefined });

    await notificationApi.list({ limit: 20, unread_only: true });
    await notificationApi.unreadCount();
    await notificationApi.setRead("n/1", true);
    await notificationApi.archive("n/1");
    await notificationApi.markAllRead();

    expect(api.get).toHaveBeenNthCalledWith(1, "/api/v1/notifications/inbox", { params: { limit: 20, unread_only: true }, signal: undefined });
    expect(api.get).toHaveBeenNthCalledWith(2, "/api/v1/notifications/inbox/unread-count", { signal: undefined });
    expect(api.patch).toHaveBeenCalledWith("/api/v1/notifications/inbox/n%2F1", { read: true });
    expect(api.delete).toHaveBeenCalledWith("/api/v1/notifications/inbox/n%2F1");
    expect(api.post).toHaveBeenCalledWith("/api/v1/notifications/inbox/mark-all-read");
  });
});

describe("isUnknownOutcome", () => {
  it("chỉ coi mất phản hồi và 5xx là chưa rõ kết quả", () => {
    expect(isUnknownOutcome(networkError())).toBe(true);
    expect(isUnknownOutcome(httpError(503, problem("REVIEW_STORE_UNAVAILABLE", 503)))).toBe(true);
    expect(isUnknownOutcome(httpError(500))).toBe(true);
    expect(isUnknownOutcome(httpError(409, problem("ALREADY_REVIEWED", 409)))).toBe(false);
    expect(isUnknownOutcome(httpError(400, problem("INVALID_REQUEST", 400)))).toBe(false);
    expect(isUnknownOutcome(httpError(429, problem("MEDIA_QUOTA_EXCEEDED", 429)))).toBe(false);
  });
});

describe("createReviewWithRecovery", () => {
  const body = { eligibility_id: eligibilityId, rating: 5 };

  it("trả thẳng review khi tạo thành công, không đọc phục hồi", async () => {
    vi.mocked(api.post).mockResolvedValue({ data: owner() });
    await expect(createReviewWithRecovery(body)).resolves.toMatchObject({ review_id: reviewId });
    expect(api.get).not.toHaveBeenCalled();
  });

  it("lỗi nghiệp vụ dứt khoát thì ném ngay, không đọc phục hồi", async () => {
    const failure = httpError(409, problem("ALREADY_REVIEWED", 409));
    vi.mocked(api.post).mockRejectedValue(failure);
    await expect(createReviewWithRecovery(body)).rejects.toBe(failure);
    expect(api.get).not.toHaveBeenCalled();
  });

  it("mất phản hồi nhưng eligibility đã CLAIMED thì coi là thành công và trả review đã tạo", async () => {
    vi.mocked(api.post).mockRejectedValue(networkError());
    vi.mocked(api.get)
      .mockResolvedValueOnce({ data: { eligibility_id: eligibilityId, status: "CLAIMED", expires_at: "2026-11-01T00:00:00Z", review_id: reviewId } })
      .mockResolvedValueOnce({ data: owner() });

    await expect(createReviewWithRecovery(body)).resolves.toMatchObject({ review_id: reviewId });
    expect(api.get).toHaveBeenNthCalledWith(1, `/api/v1/reviews/me/eligibilities/${eligibilityId}`, { signal: undefined });
    expect(api.get).toHaveBeenNthCalledWith(2, `/api/v1/reviews/me/${reviewId}`, { signal: undefined });
    // Không gửi lại POST: một lần duy nhất.
    expect(api.post).toHaveBeenCalledTimes(1);
  });

  it("mất phản hồi mà eligibility vẫn PENDING thì ném lỗi gốc để người dùng quyết định", async () => {
    const failure = networkError();
    vi.mocked(api.post).mockRejectedValue(failure);
    vi.mocked(api.get).mockResolvedValue({ data: { eligibility_id: eligibilityId, status: "PENDING", expires_at: "2026-11-01T00:00:00Z", review_id: null } });
    await expect(createReviewWithRecovery(body)).rejects.toBe(failure);
  });

  it("không đọc phục hồi được thì vẫn ném lỗi gốc", async () => {
    const failure = httpError(503, problem("REVIEW_STORE_UNAVAILABLE", 503));
    vi.mocked(api.post).mockRejectedValue(failure);
    vi.mocked(api.get).mockRejectedValue(networkError());
    await expect(createReviewWithRecovery(body)).rejects.toBe(failure);
  });
});

describe("patchReviewWithRecovery", () => {
  it("mất phản hồi nhưng review đã edit_count = 1 thì coi lượt sửa đã commit", async () => {
    vi.mocked(api.patch).mockRejectedValue(networkError());
    vi.mocked(api.get).mockResolvedValue({ data: owner({ edit_count: 1, content: "Đã sửa" }) });
    await expect(patchReviewWithRecovery(reviewId, { content: "Đã sửa" })).resolves.toMatchObject({ edit_count: 1, content: "Đã sửa" });
    expect(api.patch).toHaveBeenCalledTimes(1);
  });

  it("mất phản hồi mà chưa tiêu lượt sửa thì ném lỗi gốc", async () => {
    const failure = networkError();
    vi.mocked(api.patch).mockRejectedValue(failure);
    vi.mocked(api.get).mockResolvedValue({ data: owner({ edit_count: 0 }) });
    await expect(patchReviewWithRecovery(reviewId, { rating: 3 })).rejects.toBe(failure);
  });

  it("EDIT_QUOTA_EXHAUSTED là lỗi dứt khoát, không đọc phục hồi", async () => {
    const failure = httpError(409, problem("EDIT_QUOTA_EXHAUSTED", 409));
    vi.mocked(api.patch).mockRejectedValue(failure);
    await expect(patchReviewWithRecovery(reviewId, { rating: 3 })).rejects.toBe(failure);
    expect(api.get).not.toHaveBeenCalled();
  });
});
