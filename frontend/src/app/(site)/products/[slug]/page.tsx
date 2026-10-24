import { notFound } from "next/navigation";
import { storefrontServer, publicFailure } from "@/lib/api/storefront-server";
import { firstParam, type PageSearchParams } from "@/lib/storefront-search";
import { ProductDetailPageContent } from "@/components/product/product-detail-page-content";
import { PublicError } from "@/components/catalog/public-error";
import { pdpQuantity } from "@/lib/product-detail";
export async function generateMetadata({ params }: { params: Promise<{ slug: string }> }) {
  const product = await storefrontServer.product((await params).slug).catch(() => null);
  return { title: product?.name ?? "Sản phẩm", description: product?.description?.slice(0, 160) ?? "Thông tin sản phẩm tại TechNova." };
}
export default async function ProductPage({ params, searchParams }: { params: Promise<{ slug: string }>; searchParams: Promise<PageSearchParams> }) {
  const { slug } = await params;
  const result = await storefrontServer.product(slug).then((product) => ({ product, failure: null })).catch((error: unknown) => ({ product: null, failure: publicFailure(error) }));
  if (result.failure) return <main className="mx-auto w-full max-w-medium space-y-6 px-6 py-12"><h1 className="font-serif text-headline-2">Thông tin sản phẩm</h1><PublicError failure={result.failure} /></main>;
  if (!result.product) notFound();
  const query = await searchParams;
  const preferredSku = firstParam(query.sku);
  const preferredQuantity = pdpQuantity(firstParam(query.quantity));
  return <ProductDetailPageContent key={`${slug}:${preferredSku ?? ""}:${preferredQuantity}`} product={result.product} preferredSku={preferredSku} preferredQuantity={preferredQuantity} />;
}
