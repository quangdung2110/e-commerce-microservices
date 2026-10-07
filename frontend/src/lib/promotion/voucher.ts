import type {
  BudgetDefinition,
  DistributionMode,
  Promotion,
  PromotionCreate,
  PromotionUpdate,
  ValidityPolicy,
} from "@/lib/api/promotion";
import {
  DESCRIPTION_MAX_LENGTH,
  NAME_MAX_LENGTH,
  validateCode,
} from "@/lib/promotion/campaign";
import {
  discountFromDraft,
  discountToDraft,
  emptyDiscountDraft,
  validateDiscount,
  vnd,
  type DiscountDraft,
} from "@/lib/promotion/discount";
import {
  ALWAYS_TRUE_RULE,
  RULE_SCHEMA_VERSION,
  fromWire,
  toWire,
  validateRule,
  type DraftNode,
} from "@/lib/promotion/rule";

export type VoucherMode = Exclude<DistributionMode, "UNKNOWN">;

/** Giới hạn pool mặc định của `promotion.claim.max-pool-size` (application.yml). */
export const MAX_CLAIM_POOL = 1_000_000;

export type DurationUnit = "MINUTES" | "HOURS" | "DAYS";

export const DURATION_UNITS: Record<DurationUnit, { label: string; seconds: number }> = {
  MINUTES: { label: "phút", seconds: 60 },
  HOURS: { label: "giờ", seconds: 3_600 },
  DAYS: { label: "ngày", seconds: 86_400 },
};

export interface ValidityDraft {
  mode: ValidityPolicy["mode"];
  duration: number | undefined;
  unit: DurationUnit;
  redeemUntil: string | undefined;
}

export interface BudgetDraft {
  claimQuantity: number | undefined;
  redemptionQuantity: number | undefined;
  totalBudget: number | undefined;
}

export interface VoucherDraft {
  code: string;
  name: string;
  description: string;
  mode: VoucherMode;
  discount: DiscountDraft;
  minOrder: number | undefined;
  rule: DraftNode;
  validity: ValidityDraft;
  enabled: boolean;
  budget: BudgetDraft;
}

export function emptyVoucherDraft(): VoucherDraft {
  return {
    code: "",
    name: "",
    description: "",
    mode: "PUBLIC_SAVE",
    discount: emptyDiscountDraft(),
    minOrder: 0,
    rule: fromWire(ALWAYS_TRUE_RULE),
    validity: { mode: "CAMPAIGN_END", duration: undefined, unit: "DAYS", redeemUntil: undefined },
    enabled: true,
    budget: { claimQuantity: undefined, redemptionQuantity: undefined, totalBudget: undefined },
  };
}

export function validityToDraft(validity: ValidityPolicy): ValidityDraft {
  if (validity.mode === "DURATION_AFTER_SAVE") {
    const unit =
      validity.valid_for_seconds % DURATION_UNITS.DAYS.seconds === 0
        ? "DAYS"
        : validity.valid_for_seconds % DURATION_UNITS.HOURS.seconds === 0
          ? "HOURS"
          : "MINUTES";
    return {
      mode: "DURATION_AFTER_SAVE",
      duration: Math.floor(validity.valid_for_seconds / DURATION_UNITS[unit].seconds),
      unit,
      redeemUntil: undefined,
    };
  }
  return {
    mode: validity.mode,
    duration: undefined,
    unit: "DAYS",
    redeemUntil: validity.mode === "FIXED_DATE" ? validity.redeem_until : undefined,
  };
}

/** Giả định `validateValidity` đã trả `null`. */
export function validityFromDraft(draft: ValidityDraft): ValidityPolicy {
  if (draft.mode === "DURATION_AFTER_SAVE") {
    return {
      mode: "DURATION_AFTER_SAVE",
      valid_for_seconds: (draft.duration ?? 0) * DURATION_UNITS[draft.unit].seconds,
    };
  }
  if (draft.mode === "FIXED_DATE") {
    return { mode: "FIXED_DATE", redeem_until: draft.redeemUntil as string };
  }
  return { mode: "CAMPAIGN_END" };
}

export function validateValidity(draft: ValidityDraft, campaignStart?: string): string | null {
  if (draft.mode === "DURATION_AFTER_SAVE") {
    return draft.duration !== undefined && Number.isSafeInteger(draft.duration) && draft.duration >= 1
      ? null
      : "Hãy nhập khoảng thời gian dùng được, tối thiểu 1.";
  }
  if (draft.mode === "FIXED_DATE") {
    if (!draft.redeemUntil) return "Hãy chọn thời điểm hết hạn.";
    if (campaignStart && new Date(draft.redeemUntil).getTime() <= new Date(campaignStart).getTime()) {
      return "Thời điểm hết hạn phải sau lúc campaign bắt đầu.";
    }
  }
  return null;
}

export function budgetToDraft(budget: Promotion["budget"]): BudgetDraft {
  return {
    claimQuantity: budget.total_claim_quantity ?? undefined,
    redemptionQuantity: budget.total_redemption_quantity ?? undefined,
    totalBudget: budget.total_budget?.amount_minor,
  };
}

/** Số suất nhận chỉ có nghĩa với `LIMITED_CLAIM`; chế độ khác luôn gửi `null`. */
export function budgetFromDraft(draft: BudgetDraft, mode: VoucherMode): BudgetDefinition {
  return {
    total_claim_quantity: mode === "LIMITED_CLAIM" ? (draft.claimQuantity ?? null) : null,
    total_redemption_quantity: draft.redemptionQuantity ?? null,
    total_budget: draft.totalBudget === undefined ? null : vnd(draft.totalBudget),
  };
}

export function validateBudget(draft: BudgetDraft, mode: VoucherMode): string | null {
  if (mode === "LIMITED_CLAIM") {
    if (draft.claimQuantity === undefined || draft.claimQuantity < 1) {
      return "Voucher số lượng giới hạn cần khai báo số suất nhận, tối thiểu 1.";
    }
    if (draft.claimQuantity > MAX_CLAIM_POOL) {
      return `Số suất nhận tối đa ${MAX_CLAIM_POOL.toLocaleString("vi-VN")}.`;
    }
  }
  if (draft.redemptionQuantity !== undefined && draft.redemptionQuantity < 1) {
    return "Số lượt dùng tối đa phải từ 1 hoặc để trống.";
  }
  if (draft.totalBudget !== undefined && draft.totalBudget < 1) {
    return "Ngân sách tối đa phải lớn hơn 0 hoặc để trống.";
  }
  return null;
}

export function voucherToDraft(promotion: Promotion): VoucherDraft {
  return {
    code: promotion.code,
    name: promotion.name,
    description: promotion.description ?? "",
    mode: promotion.distribution_mode === "UNKNOWN" ? "PUBLIC_SAVE" : promotion.distribution_mode,
    discount: discountToDraft(promotion.discount),
    minOrder: promotion.min_order_amount.amount_minor,
    rule: fromWire(promotion.conditions_rule),
    validity: validityToDraft(promotion.validity),
    enabled: promotion.is_enabled,
    budget: budgetToDraft(promotion.budget),
  };
}

export interface VoucherErrors {
  code?: string;
  name?: string;
  description?: string;
  discount?: string;
  minOrder?: string;
  rule: string[];
  validity?: string;
  budget?: string;
}

export function validateVoucher(
  draft: VoucherDraft,
  options: { checkCode: boolean; campaignStart?: string }
): VoucherErrors {
  const errors: VoucherErrors = { rule: validateRule(draft.rule) };
  if (options.checkCode) {
    const code = validateCode(draft.code);
    if (code) errors.code = code;
  }
  if (!draft.name.trim()) errors.name = "Hãy nhập tên voucher.";
  else if (draft.name.trim().length > NAME_MAX_LENGTH) errors.name = `Tên tối đa ${NAME_MAX_LENGTH} ký tự.`;
  if (draft.description.length > DESCRIPTION_MAX_LENGTH) {
    errors.description = `Mô tả tối đa ${DESCRIPTION_MAX_LENGTH} ký tự.`;
  }
  const discount = validateDiscount(draft.discount);
  if (discount) errors.discount = discount;
  if (draft.minOrder === undefined || draft.minOrder < 0) {
    errors.minOrder = "Giá trị đơn tối thiểu phải từ 0 trở lên.";
  }
  const validity = validateValidity(draft.validity, options.campaignStart);
  if (validity) errors.validity = validity;
  const budget = validateBudget(draft.budget, draft.mode);
  if (budget) errors.budget = budget;
  return errors;
}

/** Giả định `validateVoucher` đã không có lỗi. */
export function voucherCreatePayload(draft: VoucherDraft): PromotionCreate {
  return {
    code: draft.code,
    name: draft.name.trim(),
    description: draft.description.trim() || null,
    distribution_mode: draft.mode,
    discount: discountFromDraft(draft.discount),
    min_order_amount: vnd(draft.minOrder ?? 0),
    conditions_rule: toWire(draft.rule),
    rule_schema_version: RULE_SCHEMA_VERSION,
    validity: validityFromDraft(draft.validity),
    is_enabled: draft.enabled,
    budget: budgetFromDraft(draft.budget, draft.mode),
  };
}

/**
 * merge-patch của voucher: chỉ gửi trường đã đổi, và object lồng (`discount`,
 * `validity`, `budget`, `conditions_rule`) luôn gửi nguyên cả khối vì backend thay
 * thế chứ không trộn từng khoá. Trả `null` khi không có gì thay đổi.
 */
export function voucherPatch(original: Promotion, draft: VoucherDraft): PromotionUpdate | null {
  const next = voucherCreatePayload(draft);
  const before = voucherCreatePayload(voucherToDraft(original));
  const patch: Record<string, unknown> = {};
  const keys = [
    "name",
    "description",
    "distribution_mode",
    "discount",
    "min_order_amount",
    "conditions_rule",
    "validity",
    "is_enabled",
    "budget",
  ] as const;
  for (const key of keys) {
    if (JSON.stringify(next[key]) !== JSON.stringify(before[key])) patch[key] = next[key];
  }
  if ("conditions_rule" in patch) patch.rule_schema_version = RULE_SCHEMA_VERSION;
  return Object.keys(patch).length > 0 ? (patch as PromotionUpdate) : null;
}
