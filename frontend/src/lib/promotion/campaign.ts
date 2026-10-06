import type {
  Campaign,
  CampaignCreate,
  CampaignUpdate,
  MarkdownRule,
  MarkdownRuleCreate,
  MarkdownRuleUpdate,
} from "@/lib/api/promotion";
import {
  discountFromDraft,
  discountToDraft,
  validateDiscount,
  type DiscountDraft,
} from "@/lib/promotion/discount";
import { isUuid } from "@/lib/promotion/rule";

/** Mã campaign/voucher: chữ HOA, số, `_`, `-`; ký tự đầu là chữ hoặc số (contract). */
export const CODE_PATTERN = /^[A-Z0-9][A-Z0-9_-]*$/;
export const CODE_MAX_LENGTH = 80;
export const NAME_MAX_LENGTH = 255;
export const DESCRIPTION_MAX_LENGTH = 4000;
export const PRIORITY_MIN = -2_147_483_648;
export const PRIORITY_MAX = 2_147_483_647;

/** Gõ thường vẫn ra chữ hoa; ký tự ngoài bảng mã bị loại ngay khi gõ. */
export function normalizeCode(text: string): string {
  return text.toUpperCase().replace(/[^A-Z0-9_-]/g, "").slice(0, CODE_MAX_LENGTH);
}

export function validateCode(code: string): string | null {
  if (!code) return "Hãy nhập mã.";
  return CODE_PATTERN.test(code)
    ? null
    : "Mã chỉ gồm chữ HOA, số, gạch dưới hoặc gạch ngang và bắt đầu bằng chữ hoặc số.";
}

export interface CampaignDraft {
  code: string;
  name: string;
  description: string;
  /** RFC 3339 (UTC); `undefined` khi chưa nhập. */
  startTime: string | undefined;
  endTime: string | undefined;
}

export const emptyCampaignDraft: CampaignDraft = {
  code: "",
  name: "",
  description: "",
  startTime: undefined,
  endTime: undefined,
};

export function campaignToDraft(campaign: Campaign): CampaignDraft {
  return {
    code: campaign.code,
    name: campaign.name,
    description: campaign.description ?? "",
    startTime: campaign.start_time,
    endTime: campaign.end_time,
  };
}

export interface CampaignErrors {
  code?: string;
  name?: string;
  description?: string;
  schedule?: string;
}

export function validateCampaign(draft: CampaignDraft, options: { checkCode: boolean }): CampaignErrors {
  const errors: CampaignErrors = {};
  if (options.checkCode) {
    const code = validateCode(draft.code);
    if (code) errors.code = code;
  }
  if (!draft.name.trim()) errors.name = "Hãy nhập tên campaign.";
  else if (draft.name.trim().length > NAME_MAX_LENGTH) errors.name = `Tên tối đa ${NAME_MAX_LENGTH} ký tự.`;
  if (draft.description.length > DESCRIPTION_MAX_LENGTH) {
    errors.description = `Mô tả tối đa ${DESCRIPTION_MAX_LENGTH} ký tự.`;
  }
  if (!draft.startTime || !draft.endTime) {
    errors.schedule = "Hãy nhập đủ thời điểm bắt đầu và kết thúc.";
  } else if (new Date(draft.startTime).getTime() >= new Date(draft.endTime).getTime()) {
    errors.schedule = "Thời điểm kết thúc phải sau thời điểm bắt đầu.";
  }
  return errors;
}

export function hasErrors(errors: object): boolean {
  return Object.values(errors).some((value) => (Array.isArray(value) ? value.length > 0 : Boolean(value)));
}

/** Giả định `validateCampaign` đã không có lỗi. */
export function campaignCreatePayload(draft: CampaignDraft): CampaignCreate {
  return {
    code: draft.code,
    name: draft.name.trim(),
    description: draft.description.trim() || null,
    start_time: draft.startTime as string,
    end_time: draft.endTime as string,
  };
}

/** merge-patch: chỉ gửi trường đã đổi. Trả `null` khi không có gì thay đổi. */
export function campaignPatch(original: Campaign, draft: CampaignDraft): CampaignUpdate | null {
  const patch: CampaignUpdate = {};
  if (draft.name.trim() !== original.name) patch.name = draft.name.trim();
  if ((draft.description.trim() || null) !== (original.description ?? null)) {
    patch.description = draft.description.trim() || null;
  }
  if (draft.startTime && !sameInstant(draft.startTime, original.start_time)) patch.start_time = draft.startTime;
  if (draft.endTime && !sameInstant(draft.endTime, original.end_time)) patch.end_time = draft.endTime;
  return Object.keys(patch).length > 0 ? patch : null;
}

function sameInstant(left: string, right: string): boolean {
  return new Date(left).getTime() === new Date(right).getTime();
}

export interface MarkdownDraft {
  productId: string;
  discount: DiscountDraft;
  priority: number | undefined;
  enabled: boolean;
}

export function markdownToDraft(rule: MarkdownRule): MarkdownDraft {
  return {
    productId: rule.product_id,
    discount: discountToDraft(rule.discount),
    priority: rule.priority,
    enabled: rule.is_enabled,
  };
}

export interface MarkdownErrors {
  product?: string;
  discount?: string;
  priority?: string;
}

export function validateMarkdown(draft: MarkdownDraft, options: { checkProduct: boolean }): MarkdownErrors {
  const errors: MarkdownErrors = {};
  if (options.checkProduct && !isUuid(draft.productId.trim())) {
    errors.product = "Hãy chọn sản phẩm hoặc nhập mã sản phẩm (UUID) hợp lệ.";
  }
  const discount = validateDiscount(draft.discount);
  if (discount) errors.discount = discount;
  if (
    draft.priority === undefined ||
    !Number.isInteger(draft.priority) ||
    draft.priority < PRIORITY_MIN ||
    draft.priority > PRIORITY_MAX
  ) {
    errors.priority = "Độ ưu tiên phải là số nguyên.";
  }
  return errors;
}

export function markdownCreatePayload(draft: MarkdownDraft): MarkdownRuleCreate {
  return {
    product_id: draft.productId.trim().toLowerCase(),
    discount: discountFromDraft(draft.discount),
    priority: draft.priority ?? 0,
    is_enabled: draft.enabled,
  };
}

export function markdownPatch(original: MarkdownRule, draft: MarkdownDraft): MarkdownRuleUpdate | null {
  const patch: MarkdownRuleUpdate = {};
  const discount = discountFromDraft(draft.discount);
  if (JSON.stringify(discount) !== JSON.stringify(normalizeDiscount(original.discount))) {
    patch.discount = discount;
  }
  if (draft.priority !== undefined && draft.priority !== original.priority) patch.priority = draft.priority;
  if (draft.enabled !== original.is_enabled) patch.is_enabled = draft.enabled;
  return Object.keys(patch).length > 0 ? patch : null;
}

/** Đưa discount từ server về đúng hình dạng `discountFromDraft` để so sánh chuỗi JSON. */
export function normalizeDiscount(discount: MarkdownRule["discount"]): MarkdownRule["discount"] {
  return discountFromDraft(discountToDraft(discount));
}
