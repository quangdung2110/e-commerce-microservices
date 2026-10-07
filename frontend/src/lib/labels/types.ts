/**
 * Tông màu của một nhãn trạng thái — trùng tên với variant của `Badge`.
 *
 * Quy ước chọn tông: `success` cho trạng thái đang hoạt động/hoàn tất tốt,
 * `warning` cho trạng thái cần người xử lý, `destructive` cho trạng thái hỏng
 * hoặc đã huỷ không đảo ngược được, `info` cho trạng thái đang chạy, `default`
 * cho trạng thái trung tính (nháp, ẩn).
 */
export type LabelTone = "default" | "success" | "warning" | "destructive" | "info" | "clay";

export interface EnumLabel {
  /** Chữ tiếng Việt hiển thị. Màu không bao giờ là kênh thông tin duy nhất. */
  text: string;
  tone: LabelTone;
}

/**
 * Tra nhãn cho một giá trị enum. Giá trị lạ (backend thêm enum mới mà frontend
 * chưa biết) vẫn hiển thị nguyên văn với tông trung tính thay vì làm vỡ trang.
 */
export function labelOf<T extends string>(
  table: Record<T, EnumLabel>,
  value: T | string
): EnumLabel {
  return (table as Record<string, EnumLabel>)[value] ?? { text: value, tone: "default" };
}
