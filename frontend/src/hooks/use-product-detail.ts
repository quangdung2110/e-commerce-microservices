"use client";

import { useQuery } from "@tanstack/react-query";
import { productApi, type ProductDetailData } from "@/services/product";
import { usePDPStore, type SkuVariant } from "@/store/usePDPStore";

// ─── Keys ──────────────────────────────────────────────────────

export const productKeys = {
  all: ["products"] as const,
  detail: (productId: string) => [...productKeys.all, "detail", productId] as const,
  related: (categoryId: string, excludeId: string) =>
    [...productKeys.all, "related", categoryId, excludeId] as const,
  search: (params: Record<string, any>) =>
    [...productKeys.all, "search", params] as const,
  categories: () => [...productKeys.all, "categories"] as const,
};

// ─── Hooks ──────────────────────────────────────────────────────

/** Fetch product detail by product ID */
export function useProductDetail(productId: string | null) {
  const setProduct = usePDPStore((s) => s.setProduct);

  return useQuery({
    queryKey: productKeys.detail(productId ?? ""),
    queryFn: async () => {
      if (!productId) throw new Error("Product ID is required");
      const detail: ProductDetailData =
        await productApi.getByProductId(productId);

      if (detail.product && detail.skus.length > 0) {
        // Map to SkuVariant for PDP store
        const variants: SkuVariant[] = detail.skus.map((sku) => ({
          sku_id: sku.sku_id,
          sku_code: sku.sku_code,
          attributes: sku.variant_attributes || {},
          original_price: sku.original_price ?? detail.product!.original_price,
          promotional_price: sku.promotional_price ?? null,
          best_price: sku.best_price,
          has_promotion: sku.best_price < (sku.original_price ?? detail.product!.original_price),
          image_url: sku.image_url,
          display_quantity: sku.display_quantity ?? 0,
          is_available: sku.is_available ?? (sku.sku_status === "ACTIVE"),
          sku_status: sku.sku_status ?? "ACTIVE",
        }));

        setProduct(productId, variants);
      }

      return detail;
    },
    enabled: !!productId,
    staleTime: 60_000,
    gcTime: 300_000,
  });
}

/** Fetch related products by category */
export function useRelatedProducts(
  categoryId: string | null,
  excludeProductId: string | null
) {
  return useQuery({
    queryKey: productKeys.related(categoryId ?? "", excludeProductId ?? ""),
    queryFn: () =>
      productApi.getRelated(categoryId ?? "", excludeProductId ?? ""),
    enabled: !!categoryId,
    staleTime: 60_000,
  });
}

/** Fetch category tree */
export function useCategories() {
  return useQuery({
    queryKey: productKeys.categories(),
    queryFn: () => productApi.getCategories(),
    staleTime: 300_000, // 5 minutes
    gcTime: 600_000, // 10 minutes
  });
}
