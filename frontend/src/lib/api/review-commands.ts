import { AxiosError } from "axios";

import {
  reviewApi,
  type CreateReviewRequest,
  type OwnerReview,
  type PatchReviewRequest,
} from "@/lib/api/review";
import { problemStatus } from "@/lib/api/problem";

/**
 * Review không có Idempotency-Key: `X-Request-Id` chỉ để truy vết (contract Review).
 * Vì vậy một lệnh có thể đã commit dù response không tới được — gửi lại mù quáng sẽ
 * thành `ALREADY_REVIEWED`/`EDIT_QUOTA_EXHAUSTED`, hoặc tệ hơn là tiêu mất lượt sửa.
 * Contract chỉ định đọc phục hồi: theo `eligibility_id` cho create, theo `review_id`
 * cho edit.
 */

/** Kết quả chưa xác định: request không có phản hồi, hoặc server lỗi 5xx. */
export function isUnknownOutcome(error: unknown): boolean {
  if (error instanceof AxiosError && !error.response) return true;
  const status = problemStatus(error);
  return status !== null && status >= 500;
}

/** Tạo review; nếu kết quả không rõ thì kiểm tra eligibility trước khi báo lỗi. */
export async function createReviewWithRecovery(body: CreateReviewRequest): Promise<OwnerReview> {
  try {
    return await reviewApi.create(body);
  } catch (error) {
    if (!isUnknownOutcome(error)) throw error;
    const status = await reviewApi.eligibilityStatus(body.eligibility_id).catch(() => null);
    if (status?.status === "CLAIMED" && status.review_id) {
      return reviewApi.mine(status.review_id);
    }
    // Vẫn PENDING (hoặc không đọc được): lệnh chưa chắc đã commit; để người dùng quyết.
    throw error;
  }
}

/**
 * Sửa review (đúng một lần). Nếu kết quả không rõ mà review đã có `edit_count = 1`
 * thì lượt sửa đã commit; trả bản hiện tại thay vì báo lỗi.
 */
export async function patchReviewWithRecovery(
  reviewId: string,
  body: PatchReviewRequest
): Promise<OwnerReview> {
  try {
    return await reviewApi.patch(reviewId, body);
  } catch (error) {
    if (!isUnknownOutcome(error)) throw error;
    const current = await reviewApi.mine(reviewId).catch(() => null);
    if (current && current.edit_count >= 1) return current;
    throw error;
  }
}
