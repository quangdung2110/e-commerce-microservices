import Link from "next/link";
import { notFound } from "next/navigation";
import { storefrontServer, publicFailure } from "@/lib/api/storefront-server";
import { parseSearchParams, flattenCategories, searchQueryString, type PageSearchParams } from "@/lib/storefront-search";
import { CatalogFilters } from "./catalog-filters";
import { CatalogResults } from "./catalog-results";
import { PublicError } from "./public-error";

export async function CatalogPage({ raw, slug }: { raw: PageSearchParams; slug?: string }) {
  const params = parseSearchParams(raw, slug);
  const [categoryResult, searchResult] = await Promise.allSettled([storefrontServer.categories(), storefrontServer.search(params)]);
  const categories = categoryResult.status === "fulfilled" ? categoryResult.value.categories : [];
  const category = flattenCategories(categories).find((node) => node.slug === params.category || node.category_id === params.category);
  if (slug && categoryResult.status === "fulfilled" && !category) notFound();
  const facetResult = category ? await storefrontServer.facets(category.category_id).then((data) => ({ data, failure: null })).catch((error: unknown) => ({ data: null, failure: publicFailure(error) })) : null;
  const action = slug ? `/c/${encodeURIComponent(slug)}` : "/search";
  const key = `${action}?${searchQueryString(params)}`;
  return <main className="mx-auto w-full max-w-medium space-y-8 px-6 py-10 md:px-8 md:py-14">
    <nav aria-label="Đường dẫn" className="flex flex-wrap gap-2 text-caption text-muted-foreground"><Link href="/" className="focus-ring rounded-sm hover:underline">Trang chủ</Link><span aria-hidden>/</span><span>{category?.name ?? "Tìm kiếm"}</span></nav>
    <header className="space-y-3"><h1 className="font-serif text-headline-2 font-normal">{slug ? category?.name ?? "Danh mục sản phẩm" : params.q ? `Kết quả cho “${params.q}”` : "Khám phá sản phẩm"}</h1><p className="text-muted-foreground">Chọn sản phẩm và biến thể phù hợp với bạn.</p></header>
    {categoryResult.status === "rejected" && <PublicError failure={publicFailure(categoryResult.reason)} message="Chưa tải được danh mục. Bạn vẫn có thể tìm kiếm sản phẩm." />}
    {facetResult?.failure && <PublicError failure={facetResult.failure} message="Chưa tải được bộ lọc thuộc tính. Các bộ lọc giá và tình trạng hàng vẫn dùng được." />}
    <div className="grid items-start gap-8 lg:grid-cols-[260px_1fr]">
      <CatalogFilters key={`filters:${key}`} params={params} categories={categories} facets={searchResult.status === "fulfilled" ? searchResult.value.facets : facetResult?.data ?? null} action={action} fixedCategory={!!slug} />
      <CatalogResults key={`results:${key}`} instanceKey={crypto.randomUUID()} params={params} initial={searchResult.status === "fulfilled" ? searchResult.value : null} failure={searchResult.status === "rejected" ? publicFailure(searchResult.reason) : null} />
    </div>
  </main>;
}
