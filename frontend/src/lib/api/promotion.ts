import type { AxiosRequestConfig } from "axios";

import { api } from "@/lib/api/client";
import {
  getVersioned,
  ifMatchHeaders,
  toVersioned,
  type Versioned,
} from "@/lib/api/etag";
import type { components, operations } from "@/lib/api/generated/promotion";
import { toPollRead } from "@/lib/api/operation";

type Schemas = components["schemas"];

export type Campaign = Schemas["Campaign"];
export type CampaignPage = Schemas["CampaignPage"];
export type CampaignPublicationStatus = Schemas["CampaignPublicationStatus"];
export type CampaignPhase = Schemas["CampaignPhase"];
export type CampaignCreate = Schemas["CreateCampaignRequest"];
export type CampaignUpdate = Schemas["UpdateCampaignRequest"];
export type CampaignClone = Schemas["CloneCampaignRequest"];
export type CampaignTransition = Schemas["CampaignTransitionRequest"];
export type PromotionCodeOverride = Schemas["PromotionCodeOverride"];
export type Promotion = Schemas["Promotion"];
export type PromotionPage = Schemas["PromotionPage"];
export type PromotionCreate = Schemas["CreatePromotionRequest"];
export type PromotionUpdate = Schemas["UpdatePromotionRequest"];
export type DistributionMode = Schemas["DistributionMode"];
export type Discount = Schemas["Discount"];
export type ValidityPolicy = Schemas["ValidityPolicy"];
export type BudgetDefinition = Schemas["BudgetDefinition"];
export type Budget = Schemas["Budget"];
export type RuleNode = Schemas["RuleNode"];
export type RuleValue = Schemas["RuleValue"];
export type MarkdownRule = Schemas["MarkdownRule"];
export type MarkdownRulePage = Schemas["MarkdownRulePage"];
export type MarkdownRuleCreate = Schemas["CreateMarkdownRuleRequest"];
export type MarkdownRuleUpdate = Schemas["UpdateMarkdownRuleRequest"];
export type WalletVoucher = Schemas["WalletVoucher"];
export type WalletEntry = Schemas["WalletEntry"];
export type WalletPage = Schemas["WalletPage"];
export type WalletFilter = WalletEntry["effective_status"];
export type SaveVoucherResponse = Schemas["SaveAndEvaluateResponse"];
export type ClaimVoucherRequest = Schemas["ClaimVoucherRequest"];
export type ClaimVoucherResponse = Schemas["ClaimVoucherResponse"];
export type ClaimPendingResponse = Schemas["ClaimPendingResponse"];
export type ClaimResult = ClaimVoucherResponse | ClaimPendingResponse;
export type GrantVoucherRequest = Schemas["GrantVoucherRequest"];
export type CampaignListParams = NonNullable<
  operations["listPromotionCampaigns"]["parameters"]["query"]
>;

const CAMPAIGNS = "/api/v1/admin/promotion-campaigns";
const PROMOTIONS = "/api/v1/admin/promotions";
const MARKDOWN_RULES = "/api/v1/admin/markdown-rules";
const MERGE_PATCH = { "Content-Type": "application/merge-patch+json" };

const campaignPath = (campaignId: string) => `${CAMPAIGNS}/${encodeURIComponent(campaignId)}`;

/**
 * `If-Match` của mọi lệnh sửa là revision của **campaign cha**, kể cả khi lệnh sửa
 * một voucher hoặc markdown (Promotion OpenAPI v1). ETag trả về từ GET voucher/markdown
 * cũng là ETag của campaign cha.
 */
function versioned(etag: string, extra: Record<string, string> = {}): AxiosRequestConfig {
  return { headers: { ...ifMatchHeaders(etag), ...extra } };
}

export const promotionApi = {
  listMyWallet: async (params: { status?: WalletFilter; cursor?: string; limit?: number }, signal?: AbortSignal): Promise<WalletPage> =>
    (await api.get<WalletPage>("/api/v1/me/promotion-vouchers", { params, signal })).data,

  // Natural key (authenticated user, promotion): no browser-owned checkout context.
  saveVoucher: async (promotionId: string, signal?: AbortSignal): Promise<SaveVoucherResponse> =>
    (await api.put<SaveVoucherResponse>(
      `/api/v1/me/promotion-vouchers/${encodeURIComponent(promotionId)}`, {}, { signal, timeout: 8_000 }
    )).data,

  // 202 has no GET status endpoint. Replay the identical POST and expected revision.
  claimVoucher: async (input: ClaimVoucherRequest, signal?: AbortSignal) =>
    toPollRead(await api.post<ClaimResult>("/api/v1/me/promotion-voucher-claims", input, {
      signal, timeout: 8_000,
    })),

  listCampaigns: async (params: CampaignListParams, signal?: AbortSignal): Promise<CampaignPage> =>
    (await api.get<CampaignPage>(CAMPAIGNS, { params, signal })).data,

  // POST không idempotent: trùng `code` mới bị từ chối (409), nên không tự gửi lại khi mất kết nối.
  createCampaign: async (input: CampaignCreate): Promise<Versioned<Campaign>> =>
    toVersioned(await api.post<Campaign>(CAMPAIGNS, input)),

  getCampaign: (campaignId: string, signal?: AbortSignal): Promise<Versioned<Campaign>> =>
    getVersioned(campaignPath(campaignId), { signal }),

  updateCampaign: async (
    campaignId: string,
    etag: string,
    patch: CampaignUpdate
  ): Promise<Versioned<Campaign>> =>
    toVersioned(await api.patch<Campaign>(campaignPath(campaignId), patch, versioned(etag, MERGE_PATCH))),

  cloneCampaign: async (
    campaignId: string,
    etag: string,
    input: CampaignClone
  ): Promise<Versioned<Campaign>> =>
    toVersioned(await api.post<Campaign>(`${campaignPath(campaignId)}/copies`, input, versioned(etag))),

  transitionCampaign: async (
    campaignId: string,
    etag: string,
    input: CampaignTransition
  ): Promise<Versioned<Campaign>> =>
    toVersioned(
      await api.post<Campaign>(`${campaignPath(campaignId)}/transitions`, input, versioned(etag))
    ),

  listPromotions: async (
    campaignId: string,
    params: { cursor?: string; limit?: number },
    signal?: AbortSignal
  ): Promise<PromotionPage> =>
    (await api.get<PromotionPage>(`${campaignPath(campaignId)}/promotions`, { params, signal })).data,

  createPromotion: async (
    campaignId: string,
    etag: string,
    input: PromotionCreate
  ): Promise<Versioned<Promotion>> =>
    toVersioned(
      await api.post<Promotion>(`${campaignPath(campaignId)}/promotions`, input, versioned(etag))
    ),

  getPromotion: (promotionId: string, signal?: AbortSignal): Promise<Versioned<Promotion>> =>
    getVersioned(`${PROMOTIONS}/${encodeURIComponent(promotionId)}`, { signal }),

  updatePromotion: async (
    promotionId: string,
    etag: string,
    patch: PromotionUpdate
  ): Promise<Versioned<Promotion>> =>
    toVersioned(
      await api.patch<Promotion>(
        `${PROMOTIONS}/${encodeURIComponent(promotionId)}`,
        patch,
        versioned(etag, MERGE_PATCH)
      )
    ),

  // Natural key (user_id, promotion_id): gửi lại cùng payload trả cùng wallet nên không cần Idempotency-Key.
  grantVoucher: async (
    promotionId: string,
    input: GrantVoucherRequest
  ): Promise<{ wallet: WalletVoucher; created: boolean }> => {
    const response = await api.post<WalletVoucher>(
      `${PROMOTIONS}/${encodeURIComponent(promotionId)}/grants`,
      input
    );
    return { wallet: response.data, created: response.status === 201 };
  },

  listMarkdownRules: async (
    campaignId: string,
    params: { cursor?: string; limit?: number },
    signal?: AbortSignal
  ): Promise<MarkdownRulePage> =>
    (await api.get<MarkdownRulePage>(`${campaignPath(campaignId)}/markdown-rules`, { params, signal }))
      .data,

  createMarkdownRule: async (
    campaignId: string,
    etag: string,
    input: MarkdownRuleCreate
  ): Promise<Versioned<MarkdownRule>> =>
    toVersioned(
      await api.post<MarkdownRule>(`${campaignPath(campaignId)}/markdown-rules`, input, versioned(etag))
    ),

  getMarkdownRule: (ruleId: string, signal?: AbortSignal): Promise<Versioned<MarkdownRule>> =>
    getVersioned(`${MARKDOWN_RULES}/${encodeURIComponent(ruleId)}`, { signal }),

  updateMarkdownRule: async (
    ruleId: string,
    etag: string,
    patch: MarkdownRuleUpdate
  ): Promise<Versioned<MarkdownRule>> =>
    toVersioned(
      await api.patch<MarkdownRule>(
        `${MARKDOWN_RULES}/${encodeURIComponent(ruleId)}`,
        patch,
        versioned(etag, MERGE_PATCH)
      )
    ),
};

/** Đọc hết các trang (tối đa 100 mục/trang) — dùng cho clone, cần phủ mọi voucher. */
export async function listAllPromotions(
  campaignId: string,
  signal?: AbortSignal
): Promise<Promotion[]> {
  const all: Promotion[] = [];
  let cursor: string | undefined;
  do {
    const page = await promotionApi.listPromotions(campaignId, { cursor, limit: 100 }, signal);
    all.push(...page.items);
    cursor = page.next_cursor ?? undefined;
  } while (cursor);
  return all;
}
