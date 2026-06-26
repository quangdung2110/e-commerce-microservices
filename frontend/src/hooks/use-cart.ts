"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { cartApi, type CartItemResponse } from "@/services/cart";
import { useCartStore } from "@/store/useCartStore";

// ─── Keys ──────────────────────────────────────────────────────

export const cartKeys = {
  all: ["cart"] as const,
  items: () => [...cartKeys.all, "items"] as const,
};

// ─── Hooks ──────────────────────────────────────────────────────

/** Fetch cart data and sync to Zustand store */
export function useCart() {
  const queryClient = useQueryClient();
  const setItems = useCartStore((s) => s.setItems);
  const setLoading = useCartStore((s) => s.setLoading);
  const setError = useCartStore((s) => s.setError);

  return useQuery({
    queryKey: cartKeys.all,
    queryFn: async () => {
      setLoading(true);
      try {
        const response = await cartApi.getCart();
        const items: CartItemResponse[] =
          response.items?.flatMap((g) => g.items) || [];

        setItems(
          items.map((item) => ({
            sku_id: item.sku_id,
            product_id: item.product_id,
            product_name: item.product_name,
            variant_info: item.variant_info,
            image_url: item.image_url,
            quantity: item.quantity,
            unit_price_at_add: item.unit_price_at_add,
            current_price: item.current_price,
            is_available: item.is_available,
            added_at: item.added_at,
            updated_at: item.updated_at,
          })),
          response.is_price_degraded,
          response.total_items,
          response.total_unique_skus
        );

        return response;
      } catch (err: any) {
        const message =
          err?.response?.data?.message ||
          "Không thể tải giỏ hàng. Vui lòng thử lại.";
        setError(message);
        throw err;
      } finally {
        setLoading(false);
      }
    },
    staleTime: 30_000,
    refetchOnWindowFocus: true,
    refetchOnMount: "always",
  });
}

/** Add item to cart */
export function useAddToCart() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: cartApi.addItem,
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: cartKeys.all });
    },
  });
}

/** Update item quantity */
export function useUpdateCartItem() {
  const queryClient = useQueryClient();
  const updateItemQuantity = useCartStore((s) => s.updateItemQuantity);

  return useMutation({
    mutationFn: ({ skuId, quantity }: { skuId: string; quantity: number }) =>
      cartApi.updateItem(skuId, quantity),

    // Optimistic update
    onMutate: async ({ skuId, quantity }) => {
      await queryClient.cancelQueries({ queryKey: cartKeys.all });
      updateItemQuantity(skuId, quantity);
    },

    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: cartKeys.all });
    },

    onError: () => {
      // Rollback: refetch
      queryClient.invalidateQueries({ queryKey: cartKeys.all });
    },
  });
}

/** Remove item from cart */
export function useRemoveFromCart() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (skuId: string) => cartApi.removeItem(skuId),

    onMutate: async (skuId) => {
      await queryClient.cancelQueries({ queryKey: cartKeys.all });
      useCartStore.getState().removeItem(skuId);
    },

    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: cartKeys.all });
    },

    onError: () => {
      queryClient.invalidateQueries({ queryKey: cartKeys.all });
    },
  });
}

/** Batch delete items */
export function useBatchDeleteCartItems() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (skuIds: string[]) =>
      cartApi.batchDelete({ sku_ids: skuIds }),

    onMutate: async (skuIds) => {
      await queryClient.cancelQueries({ queryKey: cartKeys.all });
      skuIds.forEach((id) => useCartStore.getState().removeItem(id));
    },

    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: cartKeys.all });
    },

    onError: () => {
      queryClient.invalidateQueries({ queryKey: cartKeys.all });
    },
  });
}
