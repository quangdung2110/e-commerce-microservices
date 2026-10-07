import Link from "next/link";
import { notFound } from "next/navigation";
import { storefrontServer, publicFailure } from "@/lib/api/storefront-server";
import { firstParam, type PageSearchParams } from "@/lib/storefront-search";
import type { ReviewParams } from "@/lib/api/review";
import { PublicError } from "@/components/catalog/public-error";
import { ReviewFilters } from "@/components/reviews/review-filters";
import { ReviewList } from "@/components/reviews/review-list";
export async function generateMetadata({ params }: { params: Promise<{ slug: string }> }) {
  const product = await storefrontServer.product((await params).slug).catch(() => null);
  return { title: product ? `Đánh giá ${product.name}` : "Đánh giá sản phẩm" };
}
export default async function ReviewsPage({ params, searchParams }: { params: Promise<{ slug: string }>; searchParams: Promise<PageSearchParams> }) {
  const { slug } = await params;
  const raw = await searchParams;
  const productResult = await storefrontServer.product(slug).then((product) => ({ product, failure: null })).catch((error: unknown) => ({ product: null, failure: publicFailure(error) }));
  if (productResult.failure) return <main className="mx-auto w-full max-w-medium space-y-6 px-6 py-12"><h1 className="font-serif text-headline-2">Đánh giá sản phẩm</h1><PublicError failure={productResult.failure} /></main>;
  if (!productResult.product) notFound();
  const product = productResult.product;
  const rating = Number(firstParam(raw.rating));
  const sku = firstParam(raw.sku_id);
  const filters: ReviewParams = { rating: Number.isInteger(rating) && rating >= 1 && rating <= 5 ? rating : undefined, sku_id: product.skus.some((item) => item.sku_id === sku) ? sku : undefined, has_media: firstParam(raw.has_media) === "true" ? true : undefined, limit: 20 };
  const [list, aggregate] = await Promise.allSettled([storefrontServer.reviews(product.product_id, filters), storefrontServer.aggregate(product.product_id)]);
  const key = JSON.stringify(filters);
  return <main className="mx-auto w-full max-w-small space-y-8 px-6 py-10 md:px-8 md:py-14">
    <Link href={`/products/${encodeURIComponent(slug)}`} className="focus-ring inline-flex min-h-11 items-center rounded-lg text-caption underline">Về sản phẩm</Link>
    <header className="space-y-3"><h1 className="font-serif text-headline-2">Đánh giá {product.name}</h1>{aggregate.status === "fulfilled" ? <p className="text-muted-foreground">{aggregate.value.total_reviews ? `${aggregate.value.average_rating.toFixed(1)}/5 · ${aggregate.value.total_reviews} đánh giá · ${aggregate.value.total_with_media} có ảnh/video` : "Chưa có đánh giá cho sản phẩm này."}</p> : <PublicError failure={publicFailure(aggregate.reason)} message="Chưa tải được điểm tổng hợp. Bạn vẫn có thể đọc các đánh giá bên dưới." />}</header>
    <ReviewFilters key={`filters:${key}`} product={product} params={filters} />
    <ReviewList key={`reviews:${key}`} instanceKey={crypto.randomUUID()} productId={product.product_id} slug={slug} params={filters} initial={list.status === "fulfilled" ? list.value : null} failure={list.status === "rejected" ? publicFailure(list.reason) : null} />
  </main>;
}
