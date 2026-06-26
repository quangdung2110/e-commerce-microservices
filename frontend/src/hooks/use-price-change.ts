"use client";

import { useMemo } from "react";
import { useCartStore, type PriceChangeType } from "@/store/useCartStore";

// ─── Types ──────────────────────────────────────────────────────

export interface PriceChangeInfo {
  skuId: string;
  productName: string;
  oldPrice: number;
  newPrice: number;
  change: PriceChangeType;
  percentage: number;
}

// ─── Hook ───────────────────────────────────────────────────────

/**
 * Detect price changes between unit_price_at_add (snapshot) and current_price (live).
 * This is the key hook that powers the F5 price change notification feature.
 *
 * When user F5 / refreshes the cart page:
 * 1. Cart API is called → Product Service gRPC returns current_price
 * 2. This hook compares current_price vs unit_price_at_add
 * 3. If different → returns changed items info for banner display
 */
export function usePriceChanges() {
  const items = useCartStore((s) => s.items);

  const changedItems = useMemo<PriceChangeInfo[]>(() => {
    return items
      .filter((item) => item.current_price !== item.unit_price_at_add)
      .map((item) => {
        const oldPrice = item.unit_price_at_add;
        const newPrice = item.current_price;
        const change: PriceChangeType =
          newPrice > oldPrice ? "increased" : "decreased";
        const percentage =
          oldPrice > 0
            ? Math.round((Math.abs(newPrice - oldPrice) / oldPrice) * 100)
            : 0;

        return {
          skuId: item.sku_id,
          productName: item.product_name,
          oldPrice,
          newPrice,
          change,
          percentage,
        };
      });
  }, [items]);

  const hasPriceChanges = changedItems.length > 0;
  const decreasedItems = changedItems.filter((c) => c.change === "decreased");
  const increasedItems = changedItems.filter((c) => c.change === "increased");

  return {
    /** All items with price changes */
    changedItems,
    /** Whether any price changes exist */
    hasPriceChanges,
    /** Items whose price decreased (cheaper now) */
    decreasedItems,
    /** Items whose price increased (more expensive now) */
    increasedItems,
    /** Count of changed items */
    totalChanged: changedItems.length,
  };
}
