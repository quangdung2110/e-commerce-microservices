import type { components } from "@/lib/api/generated/review";
import type { EnumLabel } from "@/lib/labels/types";

type Schemas = components["schemas"];

/** Trạng thái hiển thị của review, nhìn từ chính tác giả. */
export const REVIEW_VISIBILITY_LABELS: Record<Schemas["ReviewVisibilityStatus"], EnumLabel> = {
  APPROVED: { text: "Đang hiển thị", tone: "success" },
  HIDDEN_BY_REPORT: { text: "Đang bị ẩn", tone: "warning" },
  REMOVED: { text: "Đã gỡ", tone: "destructive" },
};

/** Câu giải thích cho tác giả; không tiết lộ số báo cáo hay lý do pháp lý. */
export const REVIEW_VISIBILITY_NOTES: Partial<Record<Schemas["ReviewVisibilityStatus"], string>> = {
  HIDDEN_BY_REPORT:
    "Đánh giá của bạn tạm thời không hiển thị công khai trong lúc cửa hàng xem xét. Bạn chưa thể chỉnh sửa đánh giá này.",
  REMOVED: "Đánh giá này đã bị gỡ và không còn hiển thị công khai. Thao tác này không thể hoàn tác.",
};

export const RATING_LABELS: Record<number, string> = {
  1: "Rất tệ",
  2: "Tệ",
  3: "Bình thường",
  4: "Tốt",
  5: "Rất tốt",
};
