"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useCommand } from "@/hooks/use-command";
import { cartApi, type CartAdjustment, type CartDeletion, type CartMutation, type CartDeletionResult } from "@/lib/api/cart";
import { useAuthStore } from "@/store/useAuthStore";

export const cartKeys = {
  all: ["cart"] as const,
  mine: (accountId: string | undefined) => ["cart", accountId] as const,
};

/** One account-scoped source of truth for PDP, header and cart page. */
export function useCart() {
  const status = useAuthStore((state) => state.status);
  const accountId = useAuthStore((state) => state.claims?.sub);
  return useQuery({
    queryKey: cartKeys.mine(accountId),
    queryFn: cartApi.mine,
    enabled: status === "authenticated" && Boolean(accountId),
    staleTime: 30_000,
    refetchOnWindowFocus: true,
    refetchOnMount: "always",
  });
}

export type CartCommand =
  | { kind: "adjust"; body: CartAdjustment }
  | { kind: "remove"; body: CartDeletion };

/** The caller freezes the full intent while a retryable outcome is unresolved. */
export function useCartCommand() {
  const client = useQueryClient();
  const accountId = useAuthStore((state) => state.claims?.sub);
  return useCommand<CartMutation | CartDeletionResult, CartCommand>({
    mutationFn: (intent: CartCommand, { commandId }) =>
      intent.kind === "adjust"
        ? cartApi.adjust(intent.body, commandId)
        : cartApi.remove(intent.body, commandId),
    onSettled: async () => {
      // A timed-out write may have committed. A read is useful but only replaying
      // the original key resolves that intent; the caller must keep it locked.
      if (useAuthStore.getState().claims?.sub === accountId) {
        await client.invalidateQueries({ queryKey: cartKeys.mine(accountId) });
      }
    },
  });
}
