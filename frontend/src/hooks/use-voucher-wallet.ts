"use client";

import { useCursorInfiniteList } from "@/hooks/use-cursor-list";
import { promotionApi, type WalletFilter } from "@/lib/api/promotion";
import { useAuthStore } from "@/store/useAuthStore";

export const walletKeys = {
  all: (accountId: string | undefined) => ["voucher-wallet", accountId] as const,
  list: (accountId: string | undefined, status?: WalletFilter) => ["voucher-wallet", accountId, { status }] as const,
};

export function useVoucherWallet(status?: WalletFilter) {
  const accountId = useAuthStore(state => state.claims?.sub);
  const authStatus = useAuthStore(state => state.status);
  return useCursorInfiniteList({
    queryKey: walletKeys.list(accountId, status),
    fetchPage: (cursor, signal) => promotionApi.listMyWallet({ status, cursor, limit: 20 }, signal),
    enabled: authStatus === "authenticated" && Boolean(accountId),
    staleTime: 0,
    gcTime: 0,
    refetchOnMount: true,
    refetchOnWindowFocus: true,
  });
}
