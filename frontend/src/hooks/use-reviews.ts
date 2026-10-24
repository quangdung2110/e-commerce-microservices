"use client";

import { useEffect } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { useCursorInfiniteList } from "@/hooks/use-cursor-list";
import {
  reviewApi,
  type CreateReviewRequest,
  type OwnerReview,
  type PatchReviewRequest,
  type PendingEligibility,
} from "@/lib/api/review";
import { createReviewWithRecovery, patchReviewWithRecovery } from "@/lib/api/review-commands";
import { useAuthStore } from "@/store/useAuthStore";

const PAGE_SIZE = 20;
/** Số trang tối đa khi cần tìm một entitlement trong danh sách chờ (50 mỗi trang). */
const SCAN_PAGE_LIMIT = 4;

export const reviewKeys = {
  all: ["reviews"] as const,
  pending: (accountId: string | undefined) => ["reviews", accountId, "pending"] as const,
  mine: (accountId: string | undefined) => ["reviews", accountId, "mine"] as const,
  detail: (accountId: string | undefined, reviewId: string) =>
    ["reviews", accountId, "detail", reviewId] as const,
  eligibility: (accountId: string | undefined, eligibilityId: string) =>
    ["reviews", accountId, "eligibility", eligibilityId] as const,
};

function useAccount() {
  const status = useAuthStore((state) => state.status);
  const accountId = useAuthStore((state) => state.claims?.sub);
  return { accountId, ready: status === "authenticated" && Boolean(accountId) };
}

/**
 * Sản phẩm chờ đánh giá. `scanAll` kéo thêm trang (tối đa `SCAN_PAGE_LIMIT`) cho nơi cần
 * tìm một entitlement cụ thể thay vì cho người dùng bấm "Tải thêm".
 */
export function usePendingReviews(options: { scanAll?: boolean; enabled?: boolean } = {}) {
  const { accountId, ready } = useAccount();
  const query = useCursorInfiniteList({
    queryKey: reviewKeys.pending(accountId),
    fetchPage: (cursor, signal) =>
      reviewApi.listPending({ limit: options.scanAll ? 50 : PAGE_SIZE, cursor }, signal),
    enabled: ready && (options.enabled ?? true),
    staleTime: 15_000,
    refetchOnMount: true,
    refetchOnWindowFocus: true,
  });

  const { hasNextPage, isFetchingNextPage, isError, fetchNextPage } = query;
  const pageCount = query.data?.pages.length ?? 0;
  useEffect(() => {
    if (options.scanAll && hasNextPage && !isFetchingNextPage && !isError && pageCount < SCAN_PAGE_LIMIT) {
      void fetchNextPage();
    }
  }, [options.scanAll, hasNextPage, isFetchingNextPage, isError, pageCount, fetchNextPage]);

  const items: PendingEligibility[] = query.data?.pages.flatMap((page) => page.items) ?? [];
  /** Đã xem hết danh sách (hoặc chạm giới hạn quét) — thiếu một item khi đó nghĩa là không có. */
  const scanned = !hasNextPage || pageCount >= SCAN_PAGE_LIMIT;
  return { ...query, items, scanned };
}

export function useMyReviews() {
  const { accountId, ready } = useAccount();
  return useCursorInfiniteList({
    queryKey: reviewKeys.mine(accountId),
    fetchPage: (cursor, signal) => reviewApi.listMine({ limit: PAGE_SIZE, cursor }, signal),
    enabled: ready,
    staleTime: 15_000,
    refetchOnMount: true,
    refetchOnWindowFocus: true,
  });
}

export function useMyReview(reviewId: string) {
  const { accountId, ready } = useAccount();
  return useQuery({
    queryKey: reviewKeys.detail(accountId, reviewId),
    queryFn: ({ signal }) => reviewApi.mine(reviewId, signal),
    enabled: ready && Boolean(reviewId),
    // Signed media URL của owner sống ngắn (url_expires_at); đọc lại khi quay lại trang.
    staleTime: 60_000,
    refetchOnWindowFocus: true,
  });
}

export function useEligibilityStatus(eligibilityId: string) {
  const { accountId, ready } = useAccount();
  return useQuery({
    queryKey: reviewKeys.eligibility(accountId, eligibilityId),
    queryFn: ({ signal }) => reviewApi.eligibilityStatus(eligibilityId, signal),
    enabled: ready && Boolean(eligibilityId),
    staleTime: 0,
    refetchOnMount: "always",
    retry: false,
  });
}

/** Sau khi tạo/sửa: danh sách chờ, danh sách của tôi và trang review công khai đều cũ. */
function useInvalidateReviews() {
  const client = useQueryClient();
  const accountId = useAuthStore((state) => state.claims?.sub);
  return async (saved?: OwnerReview) => {
    if (useAuthStore.getState().claims?.sub !== accountId) return;
    if (saved) client.setQueryData(reviewKeys.detail(accountId, saved.review_id), saved);
    await Promise.all([
      client.invalidateQueries({ queryKey: reviewKeys.pending(accountId) }),
      client.invalidateQueries({ queryKey: reviewKeys.mine(accountId) }),
      client.invalidateQueries({ queryKey: ["public-reviews"] }),
    ]);
  };
}

export function useCreateReview(onSuccess?: (saved: OwnerReview) => void) {
  const invalidate = useInvalidateReviews();
  const client = useQueryClient();
  const accountId = useAuthStore((state) => state.claims?.sub);
  return useMutation<OwnerReview, unknown, CreateReviewRequest, { accountId: string | undefined }>({
    mutationFn: createReviewWithRecovery,
    onMutate: () => ({ accountId }),
    // Run feedback before eligibility invalidation can remove the create form.
    onSuccess: (saved, _variables, context) => {
      if (context?.accountId === useAuthStore.getState().claims?.sub) onSuccess?.(saved);
    },
    onSettled: async (saved, _error, variables, context) => {
      if (context?.accountId !== useAuthStore.getState().claims?.sub) return;
      await Promise.all([
        invalidate(saved),
        client.invalidateQueries({ queryKey: reviewKeys.eligibility(accountId, variables.eligibility_id) }),
      ]);
    },
  });
}

export function usePatchReview(reviewId: string) {
  const invalidate = useInvalidateReviews();
  return useMutation<OwnerReview, unknown, PatchReviewRequest>({
    mutationFn: (body) => patchReviewWithRecovery(reviewId, body),
    onSettled: (saved) => invalidate(saved),
  });
}
