"use client";

import Link from "next/link";
import { CircleCheck, Clock3, RotateCw, TriangleAlert } from "lucide-react";

import { Button, buttonVariants } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { ErrorState } from "@/components/ui/error-state";
import { Skeleton } from "@/components/ui/skeleton";
import { useOrder } from "@/hooks/use-orders";
import { cn } from "@/lib/utils";

export function PaymentCompleteContent({ orderId }: { orderId: string }) {
  const order = useOrder(orderId, { pollPayment: true });

  if (order.isPending) {
    return <main className="mx-auto w-full max-w-narrow space-y-8 px-6 py-16"><Skeleton className="mx-auto size-12 rounded-full" /><Skeleton className="mx-auto h-10 w-72" /><Skeleton className="mx-auto h-24 w-full rounded-xl" /></main>;
  }
  if (order.isError || !order.data) {
    return <main className="mx-auto w-full max-w-narrow space-y-6 px-6 py-16"><ErrorState error={order.error} onRetry={() => void order.refetch()} /></main>;
  }

  const paid = order.data.payment_status === "PAID";
  const failed = order.data.payment_status === "FAILED";

  return (
    <main className="mx-auto w-full max-w-narrow px-6 py-16 md:py-24">
      <Card>
        <CardContent className="flex flex-col items-center gap-5 py-8 text-center">
          {paid ? <CircleCheck className="size-12 text-success" aria-hidden /> : failed ? <TriangleAlert className="size-12 text-danger" aria-hidden /> : <Clock3 className="size-12 text-info" aria-hidden />}
          <div className="space-y-2">
            <h1 className="font-serif text-headline-3 font-normal">{paid ? "Thanh toán thành công" : failed ? "Thanh toán chưa thành công" : "Đang xác nhận thanh toán"}</h1>
            <p className="mx-auto max-w-prose-narrow text-[15px] text-muted-foreground">
              {paid ? "Đơn hàng đã được thanh toán và sẽ chuyển sang bước chuẩn bị hàng." : failed ? "Giao dịch chưa hoàn tất. Nếu đơn còn đủ điều kiện, bạn có thể tạo lượt thanh toán mới trong chi tiết đơn." : "Stripe đã nhận yêu cầu. Hệ thống đang chờ kết quả xác thực từ webhook; bạn không cần thanh toán lại lúc này."}
            </p>
          </div>
          <p className="font-mono text-caption text-muted-foreground">Đơn #{orderId}</p>
          <div className="flex flex-col gap-3 sm:flex-row">
            <Link href={`/orders/${orderId}`} className={cn(buttonVariants({ variant: paid ? "default" : "outline" }))}>Xem chi tiết đơn</Link>
            {!paid && !failed ? <Button variant="outline" disabled={order.isFetching} onClick={() => void order.refetch()}><RotateCw className={order.isFetching ? "animate-spin" : undefined} aria-hidden />{order.isFetching ? "Đang kiểm tra…" : "Kiểm tra lại"}</Button> : null}
          </div>
        </CardContent>
      </Card>
    </main>
  );
}
