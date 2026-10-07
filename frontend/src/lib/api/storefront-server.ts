import "server-only";
import { cache } from "react";
import { serverGet, serverGetOrNull, ServerApiError } from "@/lib/api/server";
import type { CategoryTree, FacetSet, SearchPage, SearchParams } from "@/lib/api/search";
import type { ProductDetail } from "@/lib/api/product-detail";
import type { ReviewAggregate, ReviewPage, ReviewParams } from "@/lib/api/review";
import type { Problem } from "@/lib/api/problem";

export interface PublicApiFailure { status: number; problem: Problem | null }
/** Dữ liệu lỗi thuần để đi qua ranh giới Server/Client; không serialize Error/detail. */
export function publicFailure(error: unknown): PublicApiFailure {
  return error instanceof ServerApiError ? {
    status: error.status,
    problem: error.problem ? { ...error.problem, detail: "", errors: undefined } : null,
  } : { status: 503, problem: null };
}

export const storefrontServer = {
  search: (query: SearchParams) => serverGet<SearchPage>("/api/v2/search/products", { query, revalidate: 0 }),
  categories: () => serverGet<CategoryTree>("/api/v2/search/categories", { revalidate: 60 }),
  facets: (id: string) => serverGet<FacetSet>(`/api/v2/search/categories/${encodeURIComponent(id)}/facets`, { revalidate: 0 }),
  // BFF trả no-store khi enrichment degrade; không được ép cache ISR cho snapshot này.
  product: cache((slug: string) => serverGetOrNull<ProductDetail>(`/api/v1/storefront/products/${encodeURIComponent(slug)}`, { revalidate: 0 })),
  // Media Review là signed URL ngắn hạn; không shared-cache response chứa URL này.
  reviews: (id: string, query: ReviewParams) => serverGet<ReviewPage>(`/api/v1/reviews/products/${encodeURIComponent(id)}`, { query, revalidate: 0 }),
  aggregate: (id: string) => serverGet<ReviewAggregate>(`/api/v1/reviews/products/${encodeURIComponent(id)}/aggregate`, { revalidate: 0 }),
};
