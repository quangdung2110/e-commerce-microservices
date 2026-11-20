"use client";

import { useEffect, useMemo } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { cartKeys } from "@/hooks/use-cart";
import { cartApi } from "@/lib/api/cart";
import type { OrderDetail } from "@/lib/api/order";
import { useAuthStore } from "@/store/useAuthStore";

/** Read the shared cart after backend payment proof, allowing order.paid consumer lag. */
export function usePaidOrderCartSync(order: OrderDetail | undefined) {
  const client = useQueryClient();
  const accountId = useAuthStore((state) => state.claims?.sub);
  const status = useAuthStore((state) => state.status);
  const purchasedSkus = useMemo(
    () => new Set(order?.items.flatMap((item) => item.lines.map((line) => line.sku_id))),
    [order?.items],
  );

  useEffect(() => {
    if (status !== "authenticated" || !accountId || order?.buyer_id !== accountId || order.payment_status !== "PAID") return;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let reads = 0;
    const deadline = Date.now() + 30_000;
    const isCurrentAccount = () => useAuthStore.getState().claims?.sub === accountId;

    async function refresh() {
      if (stopped || !isCurrentAccount()) return;
      reads += 1;
      try {
        const cart = await client.fetchQuery({
          queryKey: cartKeys.mine(accountId),
          queryFn: async () => {
            if (!isCurrentAccount()) throw new Error("Cart account changed");
            const cart = await cartApi.mine();
            if (!isCurrentAccount()) throw new Error("Cart account changed");
            return cart;
          },
          staleTime: 0,
          retry: false,
        });
        if (stopped || !isCurrentAccount() || !cart.items.some((item) => purchasedSkus.has(item.sku_id))) return;
      } catch {
        // A failed read is not an empty cart. Keep the last snapshot and retry within the bound.
      }
      if (!stopped && isCurrentAccount() && reads < 15 && Date.now() < deadline) {
        timer = setTimeout(() => void refresh(), 2_000);
      }
    }

    void refresh();
    return () => { stopped = true; clearTimeout(timer); };
  }, [client, accountId, status, order?.order_id, order?.buyer_id, order?.payment_status, purchasedSkus]);
}
