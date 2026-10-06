import Link from "next/link";
import { Package, PenLine } from "lucide-react";

import { StorefrontImage } from "@/components/catalog/storefront-image";
import { FulfillmentStatus } from "@/components/orders/order-status";
import { buttonVariants } from "@/components/ui/button";
import type { OrderLine } from "@/lib/api/order";
import { formatDateTime, formatVND } from "@/lib/format";
import { cn } from "@/lib/utils";

/** `reviewHref` chỉ có khi dòng này còn quyền đánh giá (entitlement PENDING của đơn). */
export function OrderLineCard({ line, reviewHref }: { line: OrderLine; reviewHref?: string }) {
  const attributes = Object.values(line.snapshot_attributes);
  return (
    <article className="grid gap-4 border-b border-border py-5 first:pt-0 last:border-0 last:pb-0 sm:grid-cols-[88px_1fr_auto] sm:items-center">
      <div className="aspect-square overflow-hidden rounded-xl bg-surface-2">
        {line.snapshot_image_url ? (
          <StorefrontImage src={line.snapshot_image_url} alt={line.snapshot_name} className="size-full object-cover" />
        ) : (
          <div className="grid size-full place-content-center text-muted-foreground">
            <Package className="size-7" aria-hidden />
            <span className="sr-only">Ảnh sản phẩm chưa khả dụng</span>
          </div>
        )}
      </div>
      <div className="min-w-0 space-y-1.5">
        <h3 className="text-[15px] font-medium">{line.snapshot_name}</h3>
        {attributes.length ? <p className="text-caption text-muted-foreground">{attributes.join(" · ")}</p> : null}
        <p className="text-caption text-muted-foreground">Số lượng: {line.quantity}</p>
        <div className="flex flex-wrap items-center gap-2">
          <FulfillmentStatus status={line.fulfillment_status} />
          {line.delivered_at ? <span className="text-caption text-muted-foreground">{formatDateTime(line.delivered_at)}</span> : null}
        </div>
        {reviewHref ? (
          <Link href={reviewHref} className={cn(buttonVariants({ variant: "outline", size: "sm" }), "mt-1")}>
            <PenLine aria-hidden />
            Đánh giá sản phẩm
          </Link>
        ) : null}
      </div>
      <p className="text-left font-medium tabular-nums sm:text-right">
        {formatVND(line.unit_price.amount_minor * line.quantity)}
      </p>
    </article>
  );
}
