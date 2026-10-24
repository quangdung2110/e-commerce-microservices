import collection from "@/content/storefront-collection.json";
import type { CategoryNode, SearchItem } from "@/lib/api/search";

/** Editorial placements; never filter canonical Search totals, facets or direct URLs. */
export function navigationCategories(categories: CategoryNode[]) {
  return collection.navigationCategorySlugs.flatMap((slug) => categories.filter((category) => category.slug === slug));
}

export function featuredProducts(items: SearchItem[]) {
  const bySlug = new Map(items.map((item) => [item.product_slug, item]));
  return collection.products.flatMap(({ slug }) => { const item = bySlug.get(slug); return item ? [item] : []; });
}

export function isShowcaseIllustration(url: string) { return url.includes("/showcase-v1/"); }
