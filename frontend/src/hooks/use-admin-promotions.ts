"use client";

import { useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";

import { useCursorInfiniteList, useCursorPager } from "@/hooks/use-cursor-list";
import type { Versioned } from "@/lib/api/etag";
import {
  promotionApi,
  type Campaign,
  type CampaignListParams,
} from "@/lib/api/promotion";

const ROOT = ["admin", "promotions"] as const;

export const promotionKeys = {
  all: ROOT,
  campaignList: (filters: CampaignListParams) => [...ROOT, "campaigns", filters] as const,
  campaign: (campaignId: string) => [...ROOT, "campaign", campaignId] as const,
  vouchers: (campaignId: string) => [...ROOT, "campaign", campaignId, "vouchers"] as const,
  markdowns: (campaignId: string) => [...ROOT, "campaign", campaignId, "markdowns"] as const,
  voucher: (promotionId: string) => [...ROOT, "voucher", promotionId] as const,
  markdown: (ruleId: string) => [...ROOT, "markdown", ruleId] as const,
};

const CHILD_PAGE_SIZE = 50;

export function useAdminCampaigns(filters: CampaignListParams) {
  return useCursorPager({
    queryKey: promotionKeys.campaignList(filters),
    fetchPage: (cursor, signal) =>
      promotionApi.listCampaigns({ ...filters, cursor, limit: 20 }, signal),
  });
}

export function useCampaign(campaignId: string) {
  return useQuery({
    queryKey: promotionKeys.campaign(campaignId),
    queryFn: ({ signal }) => promotionApi.getCampaign(campaignId, signal),
    enabled: Boolean(campaignId),
  });
}

export function useCampaignVouchers(campaignId: string) {
  return useCursorInfiniteList({
    queryKey: promotionKeys.vouchers(campaignId),
    fetchPage: (cursor, signal) =>
      promotionApi.listPromotions(campaignId, { cursor, limit: CHILD_PAGE_SIZE }, signal),
  });
}

export function useCampaignMarkdowns(campaignId: string) {
  return useCursorInfiniteList({
    queryKey: promotionKeys.markdowns(campaignId),
    fetchPage: (cursor, signal) =>
      promotionApi.listMarkdownRules(campaignId, { cursor, limit: CHILD_PAGE_SIZE }, signal),
  });
}

export function usePromotion(promotionId: string) {
  return useQuery({
    queryKey: promotionKeys.voucher(promotionId),
    queryFn: ({ signal }) => promotionApi.getPromotion(promotionId, signal),
    enabled: Boolean(promotionId),
  });
}

export function useMarkdownRule(ruleId: string) {
  return useQuery({
    queryKey: promotionKeys.markdown(ruleId),
    queryFn: ({ signal }) => promotionApi.getMarkdownRule(ruleId, signal),
    enabled: Boolean(ruleId),
  });
}

/**
 * Mọi lệnh sửa voucher/markdown tăng revision của campaign cha, nên ETag đang giữ
 * trong cache của campaign, mọi voucher và mọi markdown đều đã cũ. Làm mới tất cả
 * thay vì đoán cái nào còn dùng được.
 */
export function refreshPromotionData(queryClient: QueryClient) {
  return queryClient.invalidateQueries({ queryKey: promotionKeys.all });
}

/** Ghi campaign mới nhận từ một lệnh vào cache để lệnh kế tiếp dùng đúng ETag. */
export function useAcceptCampaign() {
  const queryClient = useQueryClient();
  return (next: Versioned<Campaign>) => {
    queryClient.setQueryData(promotionKeys.campaign(next.data.id), next);
    void queryClient.invalidateQueries({
      predicate: (query) => {
        const key = query.queryKey;
        const isCampaignDetail = key[2] === "campaign" && key.length === 4;
        return key[0] === "admin" && key[1] === "promotions" && !isCampaignDetail;
      },
    });
  };
}
