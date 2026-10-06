"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { ArrowLeft, LoaderCircle, RotateCw, Undo2 } from "lucide-react";

import { CancelOrderDialog } from "@/components/orders/cancel-order-dialog";
import { OrderLineCard } from "@/components/orders/order-line-card";
import { OperationStatus, OrderStatus, PaymentStatus } from "@/components/orders/order-status";
import { RefundCaseCard } from "@/components/orders/refund-case-card";
import { Alert } from "@/components/ui/alert";
import { Button, buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { ErrorState } from "@/components/ui/error-state";
import { Skeleton } from "@/components/ui/skeleton";
import { useToast } from "@/components/ui/toast";
import { useOrder, useOrderCommand, type OrderCommandResult } from "@/hooks/use-orders";
import { usePendingReviews } from "@/hooks/use-reviews";
import { formatDateTime, formatPhone, formatVND } from "@/lib/format";
import { addressText, paymentAttemptNumber } from "@/lib/order";
import { problemMessage } from "@/lib/api/problem";
import { cn } from "@/lib/utils";

export function OrderDetailContent({ orderId }: { orderId: string }) {
  const router = useRouter();
  const { addToast } = useToast();
  const order = useOrder(orderId);
  const command = useOrderCommand(orderId);
  const [cancelOpen, setCancelOpen] = useState(false);
  // Đơn hoàn thành: tìm các sản phẩm của đúng đơn này còn quyền đánh giá (Review ADR-012 có order_id).
  const pendingReviews = usePendingReviews({ scanAll: true, enabled: order.data?.order_status === "COMPLETED" });

  function handleResult(kind: "cancel" | "retry-payment", result: OrderCommandResult) {
    if (result.stillProcessing) {
      addToast({ type: "info", title: "Yêu cầu vẫn đang được xử lý", message: "Không cần gửi lại. Hãy tải lại đơn hàng sau ít phút." });
      setCancelOpen(false);
      return;
    }
    if (!result.operation || result.operation.status !== "SUCCEEDED") {
      const message = result.operation?.failure ? problemMessage({ problem: result.operation.failure }) : "Yêu cầu chưa thể hoàn tất. Vui lòng tải lại đơn hàng.";
      addToast({ type: result.operation?.status === "MANUAL_REVIEW_REQUIRED" ? "warning" : "error", title: message });
      return;
    }
    if (kind === "cancel") {
      setCancelOpen(false);
      addToast({ type: "success", title: "Yêu cầu huỷ đơn đã hoàn tất" });
      return;
    }
    const attempt = paymentAttemptNumber(result.operation.result_reference);
    if (!attempt) {
      addToast({ type: "error", title: "Chưa xác định được lượt thanh toán mới", message: "Hãy tải lại chi tiết đơn rồi thử mở thanh toán." });
      return;
    }
    router.push(`/orders/${orderId}/payment?attempt=${attempt}`);
  }

  function retryPayment() {
    const returnUrl = `${window.location.origin}/orders/${orderId}/payment/complete`;
    command.mutate({ kind: "retry-payment", returnUrl }, { onSuccess: (result) => handleResult("retry-payment", result) });
  }

  if (order.isPending) return <OrderDetailSkeleton />;
  if (order.isError || !order.data) {
    return <main className="mx-auto w-full max-w-small space-y-6 px-6 py-10 md:px-8 md:py-14"><BackLink /><ErrorState error={order.error} onRetry={() => void order.refetch()} /></main>;
  }

  const data = order.data;
  const reviewable = pendingReviews.items.filter((item) => item.order_id === orderId);
  const reviewHrefOf = (line: { product_id: string; sku_id: string }) => {
    const match = reviewable.find((item) => item.product_id === line.product_id && item.sku_id === line.sku_id);
    return match ? `/reviews/new?eligibility=${encodeURIComponent(match.eligibility_id)}` : undefined;
  };
  const currentOperation = command.data?.operation;

  return (
    <main className="mx-auto w-full max-w-small space-y-8 px-6 py-10 md:px-8 md:py-14">
      <BackLink />
      <header className="flex flex-col justify-between gap-5 sm:flex-row sm:items-start">
        <div className="space-y-2">
          <p className="font-mono text-caption text-muted-foreground">ĐƠN HÀNG #{data.order_id}</p>
          <h1 className="font-serif text-headline-2 font-normal">Chi tiết đơn hàng</h1>
          <p className="text-[15px] text-muted-foreground">Đặt lúc {formatDateTime(data.created_at)}</p>
        </div>
        <div className="flex flex-wrap gap-2"><OrderStatus status={data.order_status} /><PaymentStatus status={data.payment_status} /></div>
      </header>

      {reviewable.length > 0 ? <Alert variant="info" title="Đơn hàng đã hoàn thành">Bạn có {reviewable.length} sản phẩm trong đơn này đang chờ đánh giá. Chia sẻ trải nghiệm giúp người mua sau chọn đúng sản phẩm.</Alert> : null}
      {order.isError ? <Alert variant="warning">Đang hiển thị dữ liệu gần nhất. Hãy tải lại trước khi thực hiện thao tác.</Alert> : null}
      {command.isError ? <ErrorState error={command.error} /> : null}
      {command.isPending ? <Alert variant="info" title="Đang xử lý yêu cầu"><span className="inline-flex items-center gap-2"><LoaderCircle className="size-4 animate-spin" aria-hidden />Không đóng trang trong lúc hệ thống kiểm tra trạng thái.</span></Alert> : null}
      {command.data?.stillProcessing ? <Alert variant="info" title="Yêu cầu đang tiếp tục xử lý">Không gửi lại thao tác này. Bạn có thể rời trang và kiểm tra lại sau.</Alert> : null}
      {currentOperation && currentOperation.status !== "SUCCEEDED" ? <Alert variant={currentOperation.status === "MANUAL_REVIEW_REQUIRED" ? "warning" : "info"} title="Trạng thái thao tác"><OperationStatus status={currentOperation.status} /></Alert> : null}

      <section className="grid gap-6 lg:grid-cols-[1fr_320px]">
        <div className="space-y-6">
          {data.items.map((item, index) => (
            <Card key={item.order_item_id}>
              <CardHeader className="border-b border-border pb-4">
                <CardTitle>Kiện hàng {index + 1}</CardTitle>
                <p className="text-caption text-muted-foreground">Mã kho: <span className="font-mono">{item.warehouse_id}</span></p>
              </CardHeader>
              <CardContent>{item.lines.map((line) => <OrderLineCard key={line.order_line_id} line={line} reviewHref={reviewHrefOf(line)} />)}</CardContent>
            </Card>
          ))}

          <Card>
            <CardHeader><CardTitle>Địa chỉ nhận hàng</CardTitle></CardHeader>
            <CardContent className="space-y-1 text-[14px]">
              <p className="font-medium">{data.items[0].delivery_address.recipient_name}</p>
              <p>{formatPhone(data.items[0].delivery_address.recipient_phone)}</p>
              <p className="text-muted-foreground">{addressText(data.items[0].delivery_address)}</p>
            </CardContent>
          </Card>

          {data.refund_case ? <RefundCaseCard orderId={orderId} /> : null}
        </div>

        <aside className="space-y-6 lg:sticky lg:top-24 lg:self-start">
          <Card>
            <CardHeader><CardTitle>Thanh toán</CardTitle></CardHeader>
            <CardContent>
              <dl className="space-y-3 text-[14px]">
                <div className="flex justify-between gap-4"><dt className="text-muted-foreground">Tạm tính</dt><dd className="tabular-nums">{formatVND(data.total_amount.amount_minor)}</dd></div>
                <div className="flex justify-between gap-4"><dt className="text-muted-foreground">Giảm giá</dt><dd className="tabular-nums">−{formatVND(data.total_discount.amount_minor)}</dd></div>
                <div className="flex justify-between gap-4 border-t border-border pt-3"><dt className="font-medium">Thành tiền</dt><dd className="font-serif text-headline-5 font-normal tabular-nums">{formatVND(data.final_amount.amount_minor)}</dd></div>
              </dl>
            </CardContent>
            {(data.available_actions.can_retry_payment || data.available_actions.can_return || data.available_actions.can_cancel) ? (
              <CardFooter className="flex-col items-stretch">
                {data.available_actions.can_retry_payment ? <Button variant="clay" disabled={command.isPending || command.data?.stillProcessing} onClick={retryPayment}>{command.isPending ? <LoaderCircle className="animate-spin" aria-hidden /> : <RotateCw aria-hidden />}Thanh toán lại</Button> : null}
                {data.available_actions.can_return ? <Link href={`/orders/${orderId}/return`} className={cn(buttonVariants({ variant: "outline" }))}><Undo2 aria-hidden />Yêu cầu trả hàng</Link> : null}
                {data.available_actions.can_cancel ? <Button variant="destructive" disabled={command.isPending || command.data?.stillProcessing} onClick={() => { command.startNewIntent(); setCancelOpen(true); }}>Huỷ đơn hàng</Button> : null}
              </CardFooter>
            ) : null}
          </Card>

          <Button variant="outline" className="w-full" disabled={order.isFetching} onClick={() => void order.refetch()}>
            <RotateCw className={order.isFetching ? "animate-spin" : undefined} aria-hidden />
            {order.isFetching ? "Đang tải lại…" : "Cập nhật trạng thái"}
          </Button>
        </aside>
      </section>

      <CancelOrderDialog
        open={cancelOpen}
        onOpenChange={(open) => { setCancelOpen(open); if (open) command.startNewIntent(); }}
        orderId={orderId}
        pending={command.isPending}
        error={cancelOpen && command.isError ? command.error : null}
        onConfirm={() => command.mutate({ kind: "cancel" }, { onSuccess: (result) => handleResult("cancel", result) })}
      />
    </main>
  );
}

function BackLink() {
  return <Link href="/orders" className="focus-ring inline-flex min-h-11 items-center gap-2 rounded-lg text-[14px] text-muted-foreground hover:text-foreground"><ArrowLeft className="size-4" aria-hidden />Tất cả đơn hàng</Link>;
}

function OrderDetailSkeleton() {
  return (
    <main className="mx-auto w-full max-w-small space-y-8 px-6 py-10 md:px-8 md:py-14" aria-busy="true" aria-label="Đang tải chi tiết đơn hàng">
      <Skeleton className="h-5 w-36" /><div className="space-y-3"><Skeleton className="h-10 w-72" /><Skeleton className="h-5 w-48" /></div>
      <div className="grid gap-6 lg:grid-cols-[1fr_320px]"><div className="space-y-6"><Skeleton className="h-64 w-full rounded-xl" /><Skeleton className="h-40 w-full rounded-xl" /></div><Skeleton className="h-72 w-full rounded-xl" /></div>
    </main>
  );
}
