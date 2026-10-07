import Link from "next/link";
import { ArrowRight, MessageSquareReply, PenLine } from "lucide-react";

import { StatusBadge } from "@/components/admin/status-badge";
import { ProductSnapshot } from "@/components/reviews/product-snapshot";
import { RatingStars } from "@/components/reviews/rating-stars";
import { buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardFooter } from "@/components/ui/card";
import type { OwnerReview, PendingEligibility } from "@/lib/api/review";
import { formatISODate } from "@/lib/format";
import { REVIEW_VISIBILITY_LABELS } from "@/lib/labels/review";
import { reviewExpiryNote } from "@/lib/review";
import { cn } from "@/lib/utils";

export function PendingReviewCard({ item }: { item: PendingEligibility }) {
  const expiry = reviewExpiryNote(item.expires_at);
  return (
    <Card className="animate-in fade-in duration-200">
      <CardContent className="space-y-4">
        <ProductSnapshot name={item.product_name} imageUrl={item.product_image_url} variantName={item.variant_name} />
        <p className="text-caption text-muted-foreground">
          <span className={cn(expiry.urgent && "font-medium text-warning")}>{expiry.text}</span>
          <span aria-hidden> · </span>
          <Link href={`/orders/${encodeURIComponent(item.order_id)}`} className="focus-ring rounded-md underline underline-offset-2 hover:text-foreground">
            Đơn #{item.order_id}
          </Link>
        </p>
      </CardContent>
      <CardFooter>
        <Link
          href={`/reviews/new?eligibility=${encodeURIComponent(item.eligibility_id)}`}
          className={cn(buttonVariants({ variant: "outline", size: "sm" }))}
        >
          <PenLine aria-hidden />
          Viết đánh giá
        </Link>
      </CardFooter>
    </Card>
  );
}

export function MyReviewCard({ review }: { review: OwnerReview }) {
  return (
    <Card className="animate-in fade-in duration-200">
      <CardContent className="space-y-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <ProductSnapshot
            name={review.product_name}
            imageUrl={review.product_image_url}
            variantName={review.variant_name}
            size="sm"
          />
          <StatusBadge value={review.visibility_status} labels={REVIEW_VISIBILITY_LABELS} />
        </div>

        <div className="flex flex-wrap items-center gap-3">
          <RatingStars value={review.rating} />
          <time dateTime={review.created_at} className="text-caption text-muted-foreground">
            {formatISODate(review.created_at)}
          </time>
        </div>

        {review.content ? (
          <p className="line-clamp-3 max-w-prose-narrow whitespace-pre-line break-words text-[15px]">{review.content}</p>
        ) : (
          <p className="text-[15px] text-muted-foreground">Không có nội dung, chỉ có điểm sao.</p>
        )}

        {review.has_media || review.admin_reply ? (
          <p className="flex flex-wrap items-center gap-x-4 gap-y-1 text-caption text-muted-foreground">
            {review.has_media ? <span>Có ảnh hoặc video</span> : null}
            {review.admin_reply ? (
              <span className="inline-flex items-center gap-1">
                <MessageSquareReply className="size-3" aria-hidden />
                Cửa hàng đã phản hồi
              </span>
            ) : null}
          </p>
        ) : null}
      </CardContent>
      <CardFooter>
        <Link
          href={`/reviews/${encodeURIComponent(review.review_id)}`}
          className="focus-ring inline-flex min-h-11 items-center gap-2 rounded-lg px-3 text-[14px] font-medium text-foreground hover:bg-accent"
        >
          Xem chi tiết
          <ArrowRight aria-hidden className="size-4" />
        </Link>
      </CardFooter>
    </Card>
  );
}

export function ReviewCardSkeleton() {
  return (
    <div className="space-y-4 rounded-xl border border-border bg-surface-overlay p-6" aria-hidden>
      <div className="flex items-center gap-4">
        <div className="size-20 animate-pulse rounded-xl bg-surface-3" />
        <div className="space-y-2">
          <div className="h-5 w-56 animate-pulse rounded-md bg-surface-3" />
          <div className="h-3 w-24 animate-pulse rounded-md bg-surface-3" />
        </div>
      </div>
      <div className="h-3 w-40 animate-pulse rounded-md bg-surface-3" />
    </div>
  );
}
