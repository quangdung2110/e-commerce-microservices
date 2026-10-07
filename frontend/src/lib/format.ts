/**
 * Định dạng hiển thị dùng chung. Mọi con số và mốc thời gian trên UI đi qua đây
 * để cả ứng dụng nhất quán.
 */

/**
 * Múi giờ hiển thị cố định. Backend trả RFC 3339 (thường là UTC); nếu để trình
 * duyệt tự chọn múi giờ thì cùng một đơn hàng hiện hai ngày khác nhau cho khách ở
 * Việt Nam và admin đang ở nước ngoài.
 */
export const DISPLAY_TIME_ZONE = "Asia/Ho_Chi_Minh";

const vndFormatter = new Intl.NumberFormat("vi-VN", {
  style: "currency",
  currency: "VND",
  maximumFractionDigits: 0,
});

const dateTimeFormatter = new Intl.DateTimeFormat("vi-VN", {
  timeZone: DISPLAY_TIME_ZONE,
  day: "2-digit",
  month: "2-digit",
  year: "numeric",
  hour: "2-digit",
  minute: "2-digit",
  hourCycle: "h23",
});

const dateFormatter = new Intl.DateTimeFormat("vi-VN", {
  timeZone: DISPLAY_TIME_ZONE,
  day: "2-digit",
  month: "2-digit",
  year: "numeric",
});

/**
 * Tiền VND. Mọi số tiền trong contract là **số nguyên VND** — `*_vnd` của
 * Product, `*_minor` của Order/Checkout (minor unit của VND chính là đồng) — nên
 * không chia 100.
 */
export function formatVND(amount: number): string {
  return vndFormatter.format(amount);
}

/** Phần trăm giảm giá làm tròn, 0 khi giá gốc không hợp lệ. */
export function calcDiscount(original: number, best: number): number {
  if (original <= 0) return 0;
  return Math.round(((original - best) / original) * 100);
}

/**
 * Mốc thời gian RFC 3339 (`created_at`, `updated_at`, `last_used_at`…) theo
 * giờ Việt Nam, thứ tự chuẩn của vi-VN: `HH:mm dd/MM/yyyy`.
 */
export function formatDateTime(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "—";
  return dateTimeFormatter.format(date);
}

const DATE_ONLY = /^(\d{4})-(\d{2})-(\d{2})$/;

/**
 * Chỉ phần ngày, dạng `dd/MM/yyyy`. Nhận mốc RFC 3339 (quy về giờ Việt Nam) hoặc
 * chuỗi ngày thuần `YYYY-MM-DD` như `date_of_birth` — loại sau là ngày lịch,
 * không có múi giờ, nên được giữ nguyên chứ không quy đổi.
 */
export function formatISODate(iso: string): string {
  const dateOnly = DATE_ONLY.exec(iso);
  if (dateOnly) {
    const [, year, month, day] = dateOnly;
    return `${day}/${month}/${year}`;
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "—";
  return dateFormatter.format(date);
}

/**
 * Chuẩn hoá số điện thoại người dùng gõ về E.164 — dạng duy nhất mà User
 * Service chấp nhận (`^\+[1-9][0-9]{7,14}$`).
 *
 * Backend có gỡ khoảng trắng và dấu gạch, nhưng KHÔNG tự thêm mã quốc gia: số
 * nội địa "0912345678" sẽ bị từ chối. Việc đổi `0…` thành `+84…` phải làm ở đây.
 */
export function toE164(raw: string, defaultCallingCode = "84"): string {
  const compact = raw.replace(/[\s.\-()]/g, "");
  if (compact.startsWith("+")) return compact;
  if (compact.startsWith("00")) return `+${compact.slice(2)}`;
  if (compact.startsWith("0")) return `+${defaultCallingCode}${compact.slice(1)}`;
  if (compact.startsWith(defaultCallingCode)) return `+${compact}`;
  return compact ? `+${defaultCallingCode}${compact}` : "";
}

/** Hiển thị số E.164 theo thói quen đọc của người Việt: +84912345678 → 0912 345 678. */
export function formatPhone(e164: string): string {
  const local = e164.startsWith("+84") ? `0${e164.slice(3)}` : e164;
  return /^0\d{9}$/.test(local)
    ? `${local.slice(0, 4)} ${local.slice(4, 7)} ${local.slice(7)}`
    : local;
}
