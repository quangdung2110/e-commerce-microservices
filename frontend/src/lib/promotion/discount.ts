import type { Discount } from "@/lib/api/promotion";

/**
 * Mức giảm dùng chung cho voucher và markdown.
 *
 * Phần trăm đi qua dây dưới dạng basis point (1% = 100) nên UI nhập phần trăm có tối
 * đa hai chữ số thập phân và đổi bằng `Math.round`, không bao giờ gửi số thực.
 */

export type DiscountType = Discount["type"];

export const DISCOUNT_TYPE_LABELS: Record<DiscountType, string> = {
  PERCENTAGE: "Giảm theo phần trăm",
  FIXED_VND: "Giảm số tiền cố định",
};

export const MAX_BASIS_POINTS = 10_000;

export interface DiscountDraft {
  type: DiscountType;
  /** Chuỗi để người dùng gõ được dở dang (“12.”), ví dụ “12.5”. */
  percent: string;
  /** Trần giảm tối đa cho kiểu phần trăm; `undefined` = không giới hạn. */
  maxDiscount: number | undefined;
  fixed: number | undefined;
}

export function emptyDiscountDraft(): DiscountDraft {
  return { type: "PERCENTAGE", percent: "", maxDiscount: undefined, fixed: undefined };
}

export function vnd(amount: number) {
  return { amount_minor: amount, currency: "VND" as const };
}

export function basisPointsToPercent(basisPoints: number): string {
  return String(basisPoints / 100);
}

/** `null` khi chuỗi không phải phần trăm hợp lệ (0,01–100, tối đa hai số thập phân). */
export function percentToBasisPoints(text: string): number | null {
  const normalized = text.trim().replace(",", ".");
  if (!/^\d{1,3}(\.\d{1,2})?$/.test(normalized)) return null;
  const basisPoints = Math.round(Number.parseFloat(normalized) * 100);
  return basisPoints >= 1 && basisPoints <= MAX_BASIS_POINTS ? basisPoints : null;
}

export function discountToDraft(discount: Discount): DiscountDraft {
  if (discount.type === "PERCENTAGE") {
    return {
      type: "PERCENTAGE",
      percent: basisPointsToPercent(discount.value_basis_points),
      maxDiscount: discount.max_discount?.amount_minor,
      fixed: undefined,
    };
  }
  return {
    type: "FIXED_VND",
    percent: "",
    maxDiscount: undefined,
    fixed: discount.value.amount_minor,
  };
}

/** Giả định `validateDiscount` đã trả `null`. */
export function discountFromDraft(draft: DiscountDraft): Discount {
  if (draft.type === "PERCENTAGE") {
    return {
      type: "PERCENTAGE",
      value_basis_points: percentToBasisPoints(draft.percent) ?? 0,
      max_discount: draft.maxDiscount === undefined ? null : vnd(draft.maxDiscount),
    };
  }
  return { type: "FIXED_VND", value: vnd(draft.fixed ?? 0) };
}

export function validateDiscount(draft: DiscountDraft): string | null {
  if (draft.type === "PERCENTAGE") {
    if (percentToBasisPoints(draft.percent) === null) {
      return "Phần trăm giảm phải từ 0,01 đến 100, tối đa hai chữ số thập phân.";
    }
    if (draft.maxDiscount !== undefined && draft.maxDiscount < 1) {
      return "Mức giảm tối đa phải lớn hơn 0 hoặc để trống.";
    }
    return null;
  }
  return draft.fixed !== undefined && draft.fixed >= 1 ? null : "Số tiền giảm phải lớn hơn 0.";
}

/** Câu ngắn gọn cho danh sách: “Giảm 10% (tối đa 50.000 ₫)”. */
export function describeDiscount(
  discount: Discount,
  formatMoney: (amount: number) => string
): string {
  if (discount.type === "FIXED_VND") return `Giảm ${formatMoney(discount.value.amount_minor)}`;
  const percent = basisPointsToPercent(discount.value_basis_points).replace(".", ",");
  const cap = discount.max_discount
    ? ` (tối đa ${formatMoney(discount.max_discount.amount_minor)})`
    : "";
  return `Giảm ${percent}%${cap}`;
}
