import { CatalogPage } from "@/components/catalog/catalog-page";
import type { PageSearchParams } from "@/lib/storefront-search";
import { storefrontServer } from "@/lib/api/storefront-server";
import { flattenCategories } from "@/lib/storefront-search";
export async function generateMetadata({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  const tree = await storefrontServer.categories().catch(() => null);
  return { title: tree ? flattenCategories(tree.categories).find((node) => node.slug === slug)?.name ?? "Danh mục sản phẩm" : "Danh mục sản phẩm" };
}
export default async function CategoryPage({ params, searchParams }: { params: Promise<{ slug: string }>; searchParams: Promise<PageSearchParams> }) {
  return <CatalogPage slug={(await params).slug} raw={await searchParams} />;
}
