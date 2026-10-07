import type { Metadata } from "next";
import { CatalogPage } from "@/components/catalog/catalog-page";
import type { PageSearchParams } from "@/lib/storefront-search";
export const metadata: Metadata = { title: "Tìm kiếm sản phẩm" };
export default async function SearchPage({ searchParams }: { searchParams: Promise<PageSearchParams> }) {
  return <CatalogPage raw={await searchParams} />;
}
