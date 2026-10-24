"use client";

import Link from "next/link";
import { CircleCheck, Clock3, RotateCw, TriangleAlert } from "lucide-react";

import { Button, buttonVariants } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { ErrorState } from "@/components/ui/error-state";
import { Skeleton } from "@/components/ui/skeleton";
import { useOrder } from "@/hooks/use-orders";
import type { OrderDetail } from "@/lib/api/order";
import { cn } from "@/lib/utils";

export function PaymentCompleteContent({ orderId }: { orderId: string }) {
  const order = useOrder(orderId, { pollPayment: true });

  if (order.isPending) {
    return <main className="mx-auto w-full max-w-narrow space-y-8 px-6 py-16"><Skeleton className="mx-auto size-12 rounded-full" /><Skeleton className="mx-auto h-10 w-72" /><Skeleton className="mx-auto h-24 w-full rounded-xl" /></main>;
  }
  if (order.isError || !order.data) {
    return <main className="mx-auto w-full max-w-narrow space-y-6 px-6 py-16"><ErrorState error={order.error} onRetry={() => void order.refetch()} /></main>;
  }

  const result = paymentResult(order.data);

  return (
    <main className="mx-auto w-full max-w-narrow px-6 py-16 md:py-24">
      <Card>
        <CardContent className="flex flex-col items-center gap-5 py-8 text-center">
          {result.tone === "success" ? <CircleCheck className="size-12 text-success" aria-hidden /> : result.tone === "warning" ? <TriangleAlert className="size-12 text-danger" aria-hidden /> : <Clock3 className="size-12 text-info" aria-hidden />}
          <div className="space-y-2">
            <h1 className="font-serif text-headline-3 font-normal">{result.title}</h1>
            <p className="mx-auto max-w-prose-narrow text-[15px] text-muted-foreground">
              {result.description}
            </p>
          </div>
          <p className="font-mono text-caption text-muted-foreground">Đơn #{orderId}</p>
          <div className="flex flex-col gap-3 sm:flex-row">
            <Link href={`/orders/${orderId}`} className={cn(buttonVariants({ variant: result.tone === "success" ? "default" : "outline" }))}>Xem chi tiết đơn</Link>
            {result.refresh ? <Button variant="outline" disabled={order.isFetching} onClick={() => void order.refetch()}><RotateCw className={order.isFetching ? "animate-spin" : undefined} aria-hidden />{order.isFetching ? "Đang kiểm tra…" : "Kiểm tra lại"}</Button> : null}
          </div>
        </CardContent>
      </Card>
    </main>
  );
}

function paymentResult(order: OrderDetail): { title: string; description: string; tone: "success" | "warning" | "pending"; refresh?: boolean } {
  if (order.order_status === "FINANCIAL_RECONCILIATION_REQUIRED") return {
    title: "Thanh toán đang được đối soát", tone: "warning",
    description: "Đơn hàng đang được kiểm tra. Hãy xem chi tiết đơn để theo dõi kết quả.",
  };
  if (order.payment_status === "REFUNDED" || order.order_status === "REFUNDED") return {
    title: "Đã hoàn tiền", tone: "success", description: "Đơn hàng đã được hoàn tiền. Xem chi tiết đơn để kiểm tra thông tin hoàn tiền.",
  };
  if (order.payment_status === "REFUNDING") return {
    title: "Đang hoàn tiền", tone: "pending", refresh: true,
    description: "Yêu cầu hoàn tiền đang được xử lý. Hãy xem chi tiết đơn để theo dõi tiến độ.",
  };
  if (order.payment_status === "REFUND_FAILED") return {
    title: "Hoàn tiền chưa hoàn tất", tone: "warning", description: "Hãy xem chi tiết đơn để kiểm tra trạng thái xử lý hoàn tiền.",
  };
  if (order.order_status === "CANCELLED") return order.payment_status === "PAID" ? {
    title: "Đơn hàng cần kiểm tra thanh toán", tone: "warning",
    description: "Trạng thái đơn và thanh toán cần được kiểm tra. Hãy xem chi tiết đơn; không thanh toán lại lúc này.",
  } : {
    title: order.cancel_reason === "PAYMENT_TIMEOUT" ? "Đơn hàng đã hết hạn thanh toán" : "Đơn hàng đã hủy", tone: "warning",
    description: "Đơn hàng đã đóng. Không tiếp tục thanh toán cho đơn này.",
  };
  if (order.order_status === "CANCELLING") return {
    title: "Đang hủy đơn hàng", tone: "pending", refresh: true,
    description: "Yêu cầu hủy đang được xử lý. Không thanh toán lại lúc này.",
  };
  if (order.payment_status === "PAID") return {
    title: "Thanh toán thành công", tone: "success",
    description: "Thanh toán đã được xác nhận. Xem chi tiết đơn để theo dõi xử lý và giao hàng.",
  };
  if (order.payment_status === "FAILED") return {
    title: "Thanh toán chưa thành công", tone: "warning",
    description: "Giao dịch chưa hoàn tất. Nếu đơn còn đủ điều kiện, bạn có thể tạo lượt thanh toán mới trong chi tiết đơn.",
  };
  return {
    title: "Đang xác nhận thanh toán", tone: "pending", refresh: true,
    description: "Kết quả thanh toán chưa được xác nhận. Hệ thống đang kiểm tra trạng thái đơn hàng; bạn không cần thanh toán lại lúc này.",
  };
}
