const DAY_MS = 24 * 60 * 60 * 1000;
const HOUR_MS = 60 * 60 * 1000;

export const REVIEW_CONTENT_MAX = 5000;

/**
 * Còn bao lâu để viết đánh giá. Mốc `expires_at` do Review tính từ lúc đơn hoàn thành;
 * quá mốc đó entitlement chuyển `EXPIRED` và không viết được nữa.
 */
export function reviewExpiryNote(expiresAt: string, now: number = Date.now()): { text: string; urgent: boolean } {
  const remaining = Date.parse(expiresAt) - now;
  if (Number.isNaN(remaining)) return { text: "", urgent: false };
  if (remaining <= 0) return { text: "Đã hết hạn đánh giá", urgent: true };
  if (remaining < DAY_MS) {
    const hours = Math.max(1, Math.floor(remaining / HOUR_MS));
    return { text: `Còn khoảng ${hours} giờ để đánh giá`, urgent: true };
  }
  const days = Math.floor(remaining / DAY_MS);
  return { text: `Còn ${days} ngày để đánh giá`, urgent: days <= 3 };
}

/**
 * Nội dung gửi lên: khoảng trắng đầu/cuối bị cắt, nội dung trống thành `null`
 * (contract không nhận chuỗi rỗng hay toàn khoảng trắng).
 */
export function normalizeReviewContent(raw: string): string | null {
  const trimmed = raw.trim();
  return trimmed ? trimmed : null;
}
