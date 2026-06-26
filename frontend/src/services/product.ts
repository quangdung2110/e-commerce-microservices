import axios from "axios";

// ─── Type Definitions ───────────────────────────────────────────

export interface ProductSearchItem {
  product_id: string;
  sku_id: string;
  name: string;
  thumbnail_url: string;
  original_price: number;
  best_price: number;
  discount_percentage: number;
  product_rating_avg: number;
  is_in_stock: boolean;
  /** For PDP: all SKUs for this product if detail query */
  matching_skus?: MatchingSku[];
}

export interface MatchingSku {
  sku_id: string;
  sku_code?: string;
  best_price: number;
  original_price?: number;
  promotional_price?: number;
  variant_attributes: Record<string, string>;
  sku_status?: string;
  image_url?: string;
  display_quantity?: number;
  is_available?: boolean;
}

export interface ProductSearchResponse {
  items: ProductSearchItem[];
  total: number;
  next_cursor: string | null;
  facets?: any;
}

export interface ProductDetailData {
  /** The main product item (first result) */
  product: ProductSearchItem | null;
  /** All SKUs for this product, flattened */
  skus: MatchingSku[];
  /** Unique media URLs across all SKUs */
  media: { url: string; thumbnail_url?: string; media_type?: string }[];
}

export interface CategoryNode {
  category_id: string;
  name: string;
  slug: string;
  parent_id: string | null;
  level: number;
  is_leaf: boolean;
  path?: string;
  product_count?: number;
}

export interface CategoryTreeResponse {
  categories: CategoryNode[];
}

export interface SearchParams {
  q?: string;
  category?: string;
  filters?: Record<string, string[]>;
  price_min?: number;
  price_max?: number;
  in_stock?: boolean;
  has_promotion?: boolean;
  sort?: string;
  cursor?: string;
  size?: number;
}

// ─── API Service ────────────────────────────────────────────────

const SEARCH_BASE = "/api/v1/search";

export const productApi = {
  /** Search products with filters & cursor pagination */
  search: async (params: SearchParams): Promise<ProductSearchResponse> => {
    const queryParams = new URLSearchParams();
    if (params.q) queryParams.set("q", params.q);
    if (params.category) queryParams.set("category", params.category);
    if (params.price_min !== undefined && params.price_min !== null)
      queryParams.set("price_min", String(params.price_min));
    if (params.price_max !== undefined && params.price_max !== null)
      queryParams.set("price_max", String(params.price_max));
    if (params.in_stock !== undefined) queryParams.set("in_stock", String(params.in_stock));
    if (params.has_promotion) queryParams.set("has_promotion", "true");
    if (params.sort) queryParams.set("sort", params.sort);
    if (params.cursor) queryParams.set("cursor", params.cursor);
    if (params.size) queryParams.set("size", String(params.size));

    if (params.filters) {
      Object.entries(params.filters).forEach(([key, values]) => {
        values.forEach((v) => queryParams.append(`filters[${key}]`, v));
      });
    }

    const { data } = await axios.get<ProductSearchResponse>(
      `${SEARCH_BASE}/products?${queryParams.toString()}`
    );
    return data;
  },

  /** Get product detail (all SKUs for a product) */
  getByProductId: async (productId: string): Promise<ProductDetailData> => {
    // Fetch all SKUs belonging to this product from search
    const { data } = await axios.get<ProductSearchResponse>(
      `${SEARCH_BASE}/products`,
      {
        params: {
          filters: { product_id: [productId] },
          size: 100,
        },
      }
    );

    if (!data.items || data.items.length === 0) {
      return { product: null, skus: [], media: [] };
    }

    const firstItem = data.items[0];
    const allSkus =
      data.items.flatMap((item) => item.matching_skus || []) || [];

    // Collect unique media
    const mediaMap = new Map<string, { url: string; thumbnail_url?: string; media_type?: string }>();

    return {
      product: firstItem,
      skus: allSkus.length > 0 ? allSkus : [],
      media: Array.from(mediaMap.values()),
    };
  },

  /** Get product by slug (for PDP routing) */
  getBySlug: async (slug: string): Promise<ProductSearchResponse> => {
    const { data } = await axios.get<ProductSearchResponse>(
      `${SEARCH_BASE}/products`,
      { params: { q: slug, size: 1 } }
    );
    return data;
  },

  /** Get related products by category */
  getRelated: async (
    categoryId: string,
    excludeProductId: string,
    size: number = 10
  ): Promise<ProductSearchResponse> => {
    const { data } = await axios.get<ProductSearchResponse>(
      `${SEARCH_BASE}/products`,
      {
        params: {
          category: categoryId,
          size,
          ...(excludeProductId
            ? { filters: { "NOT product_id": [excludeProductId] } }
            : {}),
        },
      }
    );
    return data;
  },

  /** Get category tree */
  getCategories: async (): Promise<CategoryTreeResponse> => {
    const { data } = await axios.get<CategoryTreeResponse>(
      `${SEARCH_BASE}/categories`
    );
    return data;
  },
};
