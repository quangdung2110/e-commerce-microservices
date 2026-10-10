import type { PromotionReason, VoucherOption } from "@/lib/api/checkout";
import type { ClaimResult, ClaimVoucherResponse } from "@/lib/api/promotion";
import { formatVND } from "@/lib/format";

const REASONS: Record<PromotionReason, string> = {
  UNKNOWN: "Chưa xác định được điều kiện áp dụng. Hãy tải lại voucher.",
  ELIGIBLE: "Có thể áp dụng cho đơn này.",
  CONDITIONS_NOT_MET: "Đơn hàng chưa đáp ứng điều kiện của voucher.",
  MIN_ORDER_NOT_MET: "Đơn hàng chưa đạt giá trị tối thiểu.",
  VOUCHER_NOT_OWNED: "Bạn cần lưu hoặc nhận voucher trước khi áp dụng.",
  VOUCHER_EXPIRED: "Voucher đã hết hạn.",
  ALREADY_USED_VOUCHER: "Voucher đã được sử dụng.",
  CAMPAIGN_NOT_ACTIVE: "Chương trình chưa bắt đầu hoặc đã kết thúc.",
  PROMOTION_DISABLED: "Voucher đang tạm ngừng áp dụng.",
  BUDGET_EXHAUSTED: "Voucher đã hết lượt sử dụng.",
};

export function voucherReason(reason: string): string {
  return REASONS[reason as PromotionReason] ?? REASONS.UNKNOWN;
}

export function voucherDiscount(option: VoucherOption): string {
  const rule = option.discount_rule;
  if (rule.type === "FIXED_VND") return `Giảm ${formatVND(rule.value.amount_minor)}`;
  const percent = new Intl.NumberFormat("vi-VN", { maximumFractionDigits: 2 }).format(rule.value_basis_points / 100);
  return `Giảm ${percent}%${rule.max_discount ? `, tối đa ${formatVND(rule.max_discount.amount_minor)}` : ""}`;
}

export function isClaimCommitted(result: ClaimResult): result is ClaimVoucherResponse {
  return "wallet" in result;
}
