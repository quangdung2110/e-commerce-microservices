import axios from "axios";

// ─── Type Definitions ───────────────────────────────────────────

export interface CartItemResponse {
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

export interface CartResponse {
  items: { items: CartItemResponse[] }[];
  is_price_degraded: boolean;
  total_items: number;
  total_unique_skus: number;
  message?: string;
}

export interface AddItemRequest {
  sku_id: string;
  quantity: number;
}

export interface AddItemResponse {
  success: boolean;
  sku_id: string;
  quantity: number;
  updated_at: string;
  message: string;
}

export interface BatchDeleteRequest {
  sku_ids: string[];
}

export interface BatchDeleteResponse {
  success: boolean;
  deleted_count: number;
  message: string;
}

export interface UpdateItemResponse {
  success: boolean;
  sku_id: string;
  quantity: number;
  updated_at: string;
  message: string;
}

// ─── API Service ────────────────────────────────────────────────

const CART_BASE = "/api/v1/carts";

export const cartApi = {
  /** Get current user's cart */
  getCart: async (): Promise<CartResponse> => {
    const { data } = await axios.get<CartResponse>(`${CART_BASE}/mine`);
    return data;
  },

  /** Add item to cart */
  addItem: async (req: AddItemRequest): Promise<AddItemResponse> => {
    const { data } = await axios.post<AddItemResponse>(
      `${CART_BASE}/items`,
      req
    );
    return data;
  },

  /** Update item quantity */
  updateItem: async (
    skuId: string,
    quantity: number
  ): Promise<UpdateItemResponse> => {
    const { data } = await axios.put<UpdateItemResponse>(
      `${CART_BASE}/items/${skuId}`,
      { quantity }
    );
    return data;
  },

  /** Remove a single item from cart */
  removeItem: async (skuId: string): Promise<void> => {
    await axios.delete(`${CART_BASE}/items/${skuId}`);
  },

  /** Batch delete items from cart */
  batchDelete: async (req: BatchDeleteRequest): Promise<BatchDeleteResponse> => {
    const { data } = await axios.delete<BatchDeleteResponse>(
      `${CART_BASE}/items`,
      { data: req }
    );
    return data;
  },
};
