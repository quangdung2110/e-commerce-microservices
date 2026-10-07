"use client";
import Link from "next/link";
import { useCursorInfiniteList } from "@/hooks/use-cursor-list";
import { MessageSquare } from "lucide-react";
import { reviewApi, type ReviewPage, type ReviewParams } from "@/lib/api/review";
import type { PublicApiFailure } from "@/lib/api/storefront-server";
import { formatISODate } from "@/lib/format";
import { StorefrontImage } from "@/components/catalog/storefront-image";
import { Button, buttonVariants } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { ErrorState } from "@/components/ui/error-state";
import { Skeleton } from "@/components/ui/skeleton";
import { ReportReview } from "./report-review";

export function ReviewList({ productId, slug, params, initial, failure, instanceKey }: { productId: string; slug: string; params: ReviewParams; initial: ReviewPage | null; failure: PublicApiFailure | null; instanceKey: string }) {
  const query = useCursorInfiniteList({
    queryKey: ["public-reviews", productId, params, instanceKey],
    fetchPage: (cursor, signal) => reviewApi.list(productId, { ...params, cursor }, signal),
    initialPage: initial ?? undefined,
    enabled: !!initial, refetchOnMount: false,
    staleTime: 0, gcTime: 0, refetchOnWindowFocus: true,
  });
  const reviews = [...new Map(query.data?.pages.flatMap((page) => page.items).map((review) => [review.review_id, review]) ?? []).values()];
  const error = query.error ?? (!query.data ? failure : null);
  if (!query.data && query.isFetching) return <div aria-busy aria-label="Đang tải đánh giá" className="space-y-6">{[0, 1, 2].map((i) => <div key={i} className="space-y-3"><Skeleton className="h-6 w-1/3" /><Skeleton className="h-24 w-full" /></div>)}</div>;
  return <div className="space-y-6" aria-busy={query.isFetching}>
    {error && <ErrorState error={error} onRetry={() => { if (query.isFetchNextPageError) void query.fetchNextPage(); else void query.refetch(); }} />}
    {query.data && (reviews.length ? <div className="divide-y divide-border">{reviews.map((review) => <article key={review.review_id} className="space-y-4 py-6">
      <header className="flex flex-wrap justify-between gap-2"><h2 className="text-[15px] font-medium">{review.author_name} · {review.rating}/5 sao</h2><time dateTime={review.created_at} className="text-caption text-muted-foreground">{formatISODate(review.created_at)}</time></header>
      {review.variant_name && <p className="text-caption text-muted-foreground">{review.variant_name}</p>}
      {review.content && <p className="max-w-prose-narrow whitespace-pre-line break-words">{review.content}</p>}
      {review.media.length > 0 && <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">{review.media.toSorted((a, b) => a.sort_order - b.sort_order).map((media) => <div key={media.asset_id} className="aspect-square overflow-hidden rounded-xl border border-border bg-surface-2">{media.media_type === "VIDEO" ? <video src={media.url} controls preload="metadata" aria-label={`Video đánh giá của ${review.author_name}`} className="size-full object-contain" /> : <StorefrontImage src={media.url} alt={`Ảnh đánh giá của ${review.author_name}`} className="size-full object-contain" />}</div>)}</div>}
      {review.admin_reply && <aside className="space-y-2 rounded-xl border border-border bg-surface-2 p-6"><p className="font-medium">Phản hồi từ cửa hàng</p><p className="whitespace-pre-line break-words">{review.admin_reply.content}</p><time dateTime={review.admin_reply.updated_at} className="text-caption text-muted-foreground">{formatISODate(review.admin_reply.updated_at)}</time></aside>}
      <ReportReview reviewId={review.review_id} slug={slug} />
    </article>)}</div> : <EmptyState icon={MessageSquare} title="Chưa có đánh giá phù hợp" description="Thử bỏ bộ lọc hoặc quay lại trang sản phẩm." action={<Link href={`/products/${encodeURIComponent(slug)}`} className={buttonVariants({ variant: "outline" })}>Về sản phẩm</Link>} />)}
    {query.hasNextPage && <Button variant="outline" disabled={query.isFetching} onClick={() => void query.fetchNextPage()}>{query.isFetchingNextPage ? "Đang tải…" : "Tải thêm đánh giá"}</Button>}
    {reviews.some((review) => review.has_media) && <Button variant="ghost" disabled={query.isFetching} onClick={() => void query.refetch()}>Tải mới ảnh/video</Button>}
  </div>;
}
