import { api } from "@/lib/api/client";
import type { components, operations } from "@/lib/api/generated/search";
import { searchQueryString } from "@/lib/storefront-search";

export type SearchItem = components["schemas"]["SearchProductItem"];
export type SearchPage = components["schemas"]["SearchPage"];
export type CategoryNode = components["schemas"]["CategoryNode"];
export type CategoryTree = components["schemas"]["CategoryTree"];
export type FacetSet = components["schemas"]["FacetSet"];
export type SuggestionList = components["schemas"]["SuggestionList"];
export type SearchParams = NonNullable<operations["searchProducts"]["parameters"]["query"]>;

const BASE = "/api/v2/search";
export const searchApi = {
  search: async (params: SearchParams, signal?: AbortSignal) =>
    (await api.get<SearchPage>(`${BASE}/products?${searchQueryString(params)}`, { signal })).data,
  categories: async (signal?: AbortSignal) =>
    (await api.get<CategoryTree>(`${BASE}/categories`, { signal })).data,
  facets: async (categoryId: string, signal?: AbortSignal) =>
    (await api.get<FacetSet>(`${BASE}/categories/${encodeURIComponent(categoryId)}/facets`, { signal })).data,
  suggestions: async (q: string, signal?: AbortSignal) =>
    (await api.get<SuggestionList>(`${BASE}/suggestions`, { params: { q, limit: 8 }, signal })).data,
};
