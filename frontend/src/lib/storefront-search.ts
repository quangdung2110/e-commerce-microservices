import type { CategoryNode, SearchParams } from "@/lib/api/search";

export type PageSearchParams = Record<string, string | string[] | undefined>;
export const SEARCH_SORTS = [
  ["relevance", "Phù hợp nhất"], ["newest", "Mới nhất"],
  ["price_asc", "Giá tăng dần"], ["price_desc", "Giá giảm dần"],
  ["rating", "Đánh giá cao nhất"],
] as const;

/** Lặp lại filter, giữ nguyên opaque token; không dùng cú pháp filters[code] của v1. */
export function searchQueryString(params: SearchParams): string {
  const query = new URLSearchParams();
  for (const [key, raw] of Object.entries(params)) {
    for (const value of Array.isArray(raw) ? raw : [raw]) {
      if (value !== undefined && value !== null && value !== "") query.append(key, String(value));
    }
  }
  return query.toString();
}

export function firstParam(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

function price(value: string | undefined): number | undefined {
  if (!value || !/^\d+$/.test(value)) return undefined;
  const number = Number(value);
  return Number.isSafeInteger(number) ? number : undefined;
}

/** Cursor chỉ nằm trong React Query và luôn reset khi URL filter đổi. */
export function parseSearchParams(raw: PageSearchParams, category?: string): SearchParams {
  const sort = firstParam(raw.sort);
  return {
    q: firstParam(raw.q)?.trim().slice(0, 256) || undefined,
    category: category ?? firstParam(raw.category)?.slice(0, 180),
    filter: (typeof raw.filter === "string" ? [raw.filter] : raw.filter ?? []).filter(Boolean).slice(0, 50),
    price_min: price(firstParam(raw.price_min)),
    price_max: price(firstParam(raw.price_max)),
    in_stock: firstParam(raw.in_stock) !== "false",
    has_promotion: firstParam(raw.has_promotion) === "true" ? true : undefined,
    sort: SEARCH_SORTS.some(([value]) => value === sort) ? sort as SearchParams["sort"] : "relevance",
    size: 20,
  };
}

export function flattenCategories(nodes: CategoryNode[], depth = 0): (CategoryNode & { depth: number })[] {
  return nodes.flatMap((node) => [{ ...node, depth }, ...flattenCategories(node.children, depth + 1)]);
}
