import Link from "next/link";
import { ArrowRight, Package } from "lucide-react";

import { StorefrontImage } from "@/components/catalog/storefront-image";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardFooter, CardHeader } from "@/components/ui/card";
import type { OrderCard as OrderCardData } from "@/lib/api/order";
import { formatDateTime, formatVND } from "@/lib/format";
import { OrderCardStatus } from "@/components/orders/order-status";

export function OrderCard({ order }: { order: OrderCardData }) {
  return (
    <Card className="animate-in fade-in duration-200">
      <CardHeader className="flex-row items-center justify-between gap-4 border-b border-border pb-4">
        <div className="min-w-0 space-y-1">
          <p className="font-mono text-[14px] font-medium">Đơn #{order.order_id}</p>
          <p className="text-caption text-muted-foreground">Đặt lúc {formatDateTime(order.created_at)}</p>
        </div>
        <OrderCardStatus status={order.ui_primary_status} />
      </CardHeader>

      <CardContent className="grid gap-5 sm:grid-cols-[96px_1fr_auto] sm:items-center">
        <div className="aspect-square overflow-hidden rounded-xl bg-surface-2">
          {order.primary_item_image_url ? (
            <StorefrontImage
              src={order.primary_item_image_url}
              alt={order.primary_item_name}
              className="size-full object-cover"
            />
          ) : (
            <div className="grid size-full place-content-center text-muted-foreground">
              <Package className="size-8" aria-hidden />
              <span className="sr-only">Ảnh sản phẩm chưa khả dụng</span>
            </div>
          )}
        </div>

        <div className="min-w-0 space-y-2">
          <h2 className="line-clamp-2 text-[15px] font-medium">{order.primary_item_name}</h2>
          <div className="flex flex-wrap items-center gap-2 text-caption text-muted-foreground">
            <Badge variant="default">TechNova</Badge>
            <span>{order.item_count} sản phẩm</span>
            {order.remaining_item_count > 0 ? <span>+{order.remaining_item_count} sản phẩm khác</span> : null}
          </div>
        </div>

        <div className="text-left sm:text-right">
          <p className="text-caption text-muted-foreground">Thành tiền</p>
          <p className="font-serif text-headline-5 font-normal tabular-nums">
            {formatVND(order.final_amount.amount_minor)}
          </p>
        </div>
      </CardContent>

      <CardFooter>
        <Link
          href={`/orders/${order.order_id}`}
          className="focus-ring inline-flex min-h-11 items-center gap-2 rounded-lg px-3 text-[14px] font-medium text-foreground hover:bg-accent"
        >
          Xem chi tiết
          <ArrowRight aria-hidden className="size-4" />
        </Link>
      </CardFooter>
    </Card>
  );
}

export function OrderCardSkeleton() {
  return (
    <div className="space-y-5 rounded-xl border border-border bg-surface-overlay p-6" aria-hidden>
      <div className="flex justify-between gap-4">
        <div className="space-y-2"><div className="h-4 w-28 animate-pulse rounded-md bg-surface-3" /><div className="h-3 w-40 animate-pulse rounded-md bg-surface-3" /></div>
        <div className="h-6 w-28 animate-pulse rounded-md bg-surface-3" />
      </div>
      <div className="grid gap-5 sm:grid-cols-[96px_1fr_auto] sm:items-center">
        <div className="aspect-square animate-pulse rounded-xl bg-surface-3" />
        <div className="space-y-3"><div className="h-5 w-2/3 animate-pulse rounded-md bg-surface-3" /><div className="h-4 w-40 animate-pulse rounded-md bg-surface-3" /></div>
        <div className="h-7 w-32 animate-pulse rounded-md bg-surface-3" />
      </div>
    </div>
  );
}
