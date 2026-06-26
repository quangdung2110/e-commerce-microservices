import { create } from "zustand";

// ─── Types ──────────────────────────────────────────────────────

export interface CartItem {
  sku_id: string;
  product_id: string;
  product_name: string;
  variant_info?: string;
  image_url?: string;
  quantity: number;
  unit_price_at_add: number;
  current_price: number;
  is_available: boolean;
  added_at: string;
  updated_at: string;
}

export type PriceChangeType = "increased" | "decreased" | null;

// ─── Store ──────────────────────────────────────────────────────

interface CartStore {
  /** Raw cart items from API */
  items: CartItem[];
  /** Whether the cart is currently loading */
  isLoading: boolean;
  /** Whether price data is degraded (Product Service unavailable) */
  isPriceDegraded: boolean;
  /** Error message if any */
  error: string | null;
  /** Total number of items (sum of quantities) */
  totalItems: number;
  /** Total unique SKUs */
  totalUniqueSKUs: number;

  // Actions
  setItems: (
    items: CartItem[],
    isPriceDegraded: boolean,
    totalItems: number,
    totalUniqueSKUs: number
  ) => void;
  setLoading: (loading: boolean) => void;
  setError: (error: string | null) => void;
  updateItemQuantity: (skuId: string, quantity: number) => void;
  removeItem: (skuId: string) => void;
  clearCart: () => void;

  // Computed
  /** Get subtotal (sum of current_price * quantity for available items) */
  getSubtotal: () => number;
  /** Get count of unavailable items */
  getUnavailableCount: () => number;
  /** Check if a specific item has price changed */
  getItemPriceChange: (skuId: string) => PriceChangeType;
  /** Get all items with price changes */
  getChangedPriceItems: () => { skuId: string; oldPrice: number; newPrice: number; change: PriceChangeType }[];
  /** Get item by sku_id */
  getItem: (skuId: string) => CartItem | undefined;
}

export const useCartStore = create<CartStore>((set, get) => ({
  items: [],
  isLoading: false,
  isPriceDegraded: false,
  error: null,
  totalItems: 0,
  totalUniqueSKUs: 0,

  setItems: (items, isPriceDegraded, totalItems, totalUniqueSKUs) =>
    set({ items, isPriceDegraded, totalItems, totalUniqueSKUs, error: null }),

  setLoading: (isLoading) => set({ isLoading }),

  setError: (error) => set({ error }),

  updateItemQuantity: (skuId, quantity) =>
    set((state) => ({
      items: state.items.map((item) =>
        item.sku_id === skuId ? { ...item, quantity } : item
      ),
      totalItems: state.items.reduce(
        (sum, item) =>
          sum + (item.sku_id === skuId ? quantity : item.quantity),
        0
      ),
    })),

  removeItem: (skuId) =>
    set((state) => {
      const newItems = state.items.filter((item) => item.sku_id !== skuId);
      return {
        items: newItems,
        totalItems: newItems.reduce((sum, item) => sum + item.quantity, 0),
        totalUniqueSKUs: newItems.length,
      };
    }),

  clearCart: () =>
    set({
      items: [],
      totalItems: 0,
      totalUniqueSKUs: 0,
      error: null,
    }),

  getSubtotal: () => {
    return get().items
      .filter((item) => item.is_available)
      .reduce((sum, item) => sum + item.current_price * item.quantity, 0);
  },

  getUnavailableCount: () => {
    return get().items.filter((item) => !item.is_available).length;
  },

  getItemPriceChange: (skuId) => {
    const item = get().items.find((i) => i.sku_id === skuId);
    if (!item) return null;
    if (item.current_price > item.unit_price_at_add) return "increased";
    if (item.current_price < item.unit_price_at_add) return "decreased";
    return null;
  },

  getChangedPriceItems: () => {
    return get().items
      .filter((item) => item.current_price !== item.unit_price_at_add)
      .map((item) => ({
        skuId: item.sku_id,
        oldPrice: item.unit_price_at_add,
        newPrice: item.current_price,
        change: (item.current_price > item.unit_price_at_add
          ? "increased"
          : "decreased") as PriceChangeType,
      }));
  },

  getItem: (skuId) => get().items.find((i) => i.sku_id === skuId),
}));
