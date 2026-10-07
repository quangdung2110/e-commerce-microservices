"use client";

import { useState } from "react";
import Link from "next/link";
import { ArrowLeft, LoaderCircle, PackageCheck, RotateCcw } from "lucide-react";

import { IdChip } from "@/components/admin/id-chip";
import { PageHeader } from "@/components/admin/page-header";
import { StatusBadge } from "@/components/admin/status-badge";
import { OrderLineCard } from "@/components/orders/order-line-card";
import { OperationStatus, OrderStatus, PaymentStatus } from "@/components/orders/order-status";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { ErrorState } from "@/components/ui/error-state";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Textarea } from "@/components/ui/textarea";
import { useToast } from "@/components/ui/toast";
import {
  useAdminOrder,
  useAdminOrderCommand,
  type AdminOrderCommandResult,
} from "@/hooks/use-admin-orders";
import { problemMessage } from "@/lib/api/problem";
import { formatDateTime, formatPhone, formatVND } from "@/lib/format";
import { REFUND_STATUS_LABELS } from "@/lib/labels/order";
import {
  addressText,
  canAdminCancelOrder,
  canAdminRefundBeforeDispatch,
} from "@/lib/order";

type AdminAction = "cancel" | "refund";

export function AdminOrderDetail({ orderId }: { orderId: string }) {
  const { addToast } = useToast();
  const order = useAdminOrder(orderId);
  const command = useAdminOrderCommand(orderId);
  const [action, setAction] = useState<AdminAction | null>(null);
  const [confirmation, setConfirmation] = useState("");
  const [reasonCode, setReasonCode] = useState("");
  const [reasonDetails, setReasonDetails] = useState("");

  function openAction(next: AdminAction) {
    command.reset();
    command.startNewIntent();
    setConfirmation("");
    setReasonCode("");
    setReasonDetails("");
    setAction(next);
  }

  function closeAction() {
    if (command.isPending) return;
    setAction(null);
  }

  function acceptResult(kind: AdminAction, result: AdminOrderCommandResult) {
    if (result.stillProcessing) {
      addToast({
        type: "info",
        title: "Lệnh vẫn đang được xử lý",
        message: "Không cần gửi lại. Hãy tải lại đơn hàng sau ít phút.",
      });
      setAction(null);
      return;
    }
    if (result.operation?.status === "SUCCEEDED") {
      addToast({
        type: "success",
        title: kind === "cancel" ? "Đã hoàn tất huỷ đơn" : "Đã hoàn tất hoàn tiền trước xuất kho",
      });
      setAction(null);
    }
  }

  async function submitAction(event: React.FormEvent) {
    event.preventDefault();
    if (!action || confirmation !== orderId) return;
    try {
      const result = await command.mutateAsync(
        action === "cancel"
          ? { kind: "cancel" }
          : {
              kind: "refund",
              body: {
                reason_code: reasonCode.trim(),
                reason_details: reasonDetails.trim() || null,
              },
            }
      );
      acceptResult(action, result);
    } catch {
      // React Query giữ lỗi để ErrorState hiển thị trong dialog.
    }
  }

  if (order.isPending) return <AdminOrderDetailSkeleton />;
  if (order.isError || !order.data) {
    return (
      <div className="space-y-6">
        <BackToOrders />
        <ErrorState error={order.error} onRetry={() => void order.refetch()} />
      </div>
    );
  }

  const data = order.data;
  const canCancel = canAdminCancelOrder(data);
  const canRefund = canAdminRefundBeforeDispatch(data);
  const operation = command.data?.operation;
  const actionFailure = operation?.failure
    ? problemMessage({ problem: operation.failure })
    : operation && operation.status !== "SUCCEEDED" && operation.status !== "ACCEPTED" && operation.status !== "RUNNING"
      ? "Lệnh không thể hoàn tất ở trạng thái hiện tại."
      : null;

  return (
    <div className="space-y-8">
      <BackToOrders />
      <PageHeader
        title={`Đơn hàng #${data.order_id}`}
        description={`Đặt lúc ${formatDateTime(data.created_at)} · cập nhật ${formatDateTime(data.updated_at)}`}
        action={
          <Button nativeButton={false} variant="outline" render={<Link href={`/admin/inventory/reservations/${encodeURIComponent(data.order_id)}`} />}>
            <PackageCheck aria-hidden />
            Xem phân bổ kho
          </Button>
        }
      />

      <div className="flex flex-wrap gap-2">
        <OrderStatus status={data.order_status} />
        <PaymentStatus status={data.payment_status} />
        {data.refund_case ? (
          <StatusBadge value={data.refund_case.status} labels={REFUND_STATUS_LABELS} />
        ) : null}
      </div>

      {command.isPending ? (
        <Alert variant="info" title="Đang xử lý lệnh">
          <span className="inline-flex items-center gap-2">
            <LoaderCircle className="size-4 animate-spin" aria-hidden />
            Hệ thống đang theo dõi operation bền vững của Order Service.
          </span>
        </Alert>
      ) : null}
      {command.data?.stillProcessing ? (
        <Alert variant="info" title="Lệnh vẫn đang chạy">
          Không gửi lại với ý định mới. Bạn có thể rời trang và kiểm tra lại sau.
        </Alert>
      ) : null}

      <section className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_340px]">
        <div className="space-y-6">
          {data.items.map((item, index) => (
            <Card key={item.order_item_id}>
              <CardHeader className="border-b border-border">
                <CardTitle>Kiện hàng {index + 1}</CardTitle>
                <div className="flex flex-wrap gap-2 text-caption text-muted-foreground">
                  <span>Mã kiện:</span><IdChip value={item.order_item_id} label="Sao chép mã kiện" />
                  <span className="ml-2">Kho:</span><IdChip value={item.warehouse_id} label="Sao chép mã kho" />
                </div>
              </CardHeader>
              <CardContent>
                {item.lines.map((line) => (
                  <div key={line.order_line_id}>
                    <OrderLineCard line={line} />
                    <div className="-mt-3 mb-5 flex flex-wrap gap-2 text-caption text-muted-foreground last:mb-0">
                      <span>Dòng:</span><IdChip value={line.order_line_id} label="Sao chép mã dòng đơn" />
                      <span className="ml-2">SKU:</span><IdChip value={line.sku_id} label="Sao chép mã SKU" />
                    </div>
                  </div>
                ))}
              </CardContent>
            </Card>
          ))}

          <Card>
            <CardHeader><CardTitle>Thông tin giao hàng</CardTitle></CardHeader>
            <CardContent className="grid gap-5 text-[14px] sm:grid-cols-2">
              <Info label="Người nhận">{data.items[0].delivery_address.recipient_name}</Info>
              <Info label="Số điện thoại">{formatPhone(data.items[0].delivery_address.recipient_phone)}</Info>
              <Info label="Địa chỉ" className="sm:col-span-2">{addressText(data.items[0].delivery_address)}</Info>
            </CardContent>
          </Card>
        </div>

        <aside className="space-y-6 xl:sticky xl:top-24 xl:self-start">
          <Card>
            <CardHeader><CardTitle>Tổng quan</CardTitle></CardHeader>
            <CardContent className="space-y-4">
              <Info label="Người mua"><IdChip value={data.buyer_id} label="Sao chép mã người mua" /></Info>
              <MoneyRow label="Tạm tính" amount={data.total_amount.amount_minor} />
              <MoneyRow label="Giảm giá" amount={-data.total_discount.amount_minor} />
              <div className="flex items-baseline justify-between gap-4 border-t border-border pt-4">
                <span className="font-medium">Thành tiền</span>
                <span className="font-serif text-headline-5 tabular-nums">{formatVND(data.final_amount.amount_minor)}</span>
              </div>
              <Info label="Hạn thanh toán">{formatDateTime(data.payment.payment_deadline_at)}</Info>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>Thao tác vận hành</CardTitle>
              <p className="text-caption text-muted-foreground">Quyền cuối cùng vẫn được Order và Inventory kiểm tra lại khi nhận lệnh.</p>
            </CardHeader>
            <CardContent className="space-y-3">
              <Button className="w-full" variant="destructive" disabled={!canCancel || command.isPending} onClick={() => openAction("cancel")}>
                Huỷ đơn chưa thanh toán
              </Button>
              <Button className="w-full" variant="outline" disabled={!canRefund || command.isPending} onClick={() => openAction("refund")}>
                <RotateCcw aria-hidden />
                Hoàn tiền trước xuất kho
              </Button>
              {!canCancel && !canRefund ? (
                <p className="text-caption text-muted-foreground">
                  Đơn hiện không có thao tác vòng đời tự động phù hợp. Nếu đã có dòng rời kho, cần xử lý theo quy trình sự cố thủ công.
                </p>
              ) : null}
            </CardContent>
            <CardFooter>
              <Button nativeButton={false} variant="secondary" render={<Link href={`/admin/inventory/reservations/${encodeURIComponent(data.order_id)}`} />}>
                Vận hành kho
              </Button>
            </CardFooter>
          </Card>
        </aside>
      </section>

      <Dialog open={action !== null} onOpenChange={(open) => { if (!open) closeAction(); }} disablePointerDismissal={command.isPending}>
        <DialogContent showCloseButton={!command.isPending}>
          <form onSubmit={submitAction}>
            <DialogHeader>
              <DialogTitle>{action === "cancel" ? "Huỷ đơn chưa thanh toán" : "Hoàn tiền trước xuất kho"}</DialogTitle>
              <DialogDescription>
                {action === "cancel"
                  ? "Order Service sẽ huỷ đơn và giải phóng các tài nguyên đang giữ."
                  : "Inventory phải giải phóng nguyên tử toàn bộ phân bổ chưa xuất kho trước khi Payment hoàn tiền."}
              </DialogDescription>
            </DialogHeader>
            <div className="mt-6 space-y-5">
              {action === "refund" ? (
                <>
                  <div className="space-y-2">
                    <Label htmlFor="admin-refund-reason-code">Mã lý do *</Label>
                    <Input id="admin-refund-reason-code" value={reasonCode} required minLength={1} maxLength={100} placeholder="Ví dụ: CUSTOMER_SUPPORT" disabled={command.isPending} onChange={(event) => setReasonCode(event.target.value)} />
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="admin-refund-reason-details">Chi tiết</Label>
                    <Textarea id="admin-refund-reason-details" value={reasonDetails} maxLength={1000} disabled={command.isPending} onChange={(event) => setReasonDetails(event.target.value)} />
                  </div>
                </>
              ) : null}
              <div className="space-y-2">
                <Label htmlFor="admin-order-confirmation">Gõ mã đơn <span className="font-mono">{orderId}</span> để xác nhận</Label>
                <Input id="admin-order-confirmation" value={confirmation} autoComplete="off" disabled={command.isPending} onChange={(event) => setConfirmation(event.target.value)} />
              </div>
              {command.isError ? <ErrorState error={command.error} /> : null}
              {actionFailure ? (
                <Alert variant={operation?.status === "MANUAL_REVIEW_REQUIRED" ? "warning" : "danger"} title="Lệnh chưa hoàn tất">
                  <div className="space-y-2"><p>{actionFailure}</p>{operation ? <OperationStatus status={operation.status} /> : null}</div>
                </Alert>
              ) : null}
            </div>
            <DialogFooter>
              <Button type="button" variant="secondary" disabled={command.isPending} onClick={closeAction}>Quay lại</Button>
              <Button type="submit" variant={action === "cancel" ? "destructive" : "default"} disabled={command.isPending || confirmation !== orderId || (action === "refund" && !reasonCode.trim())}>
                {command.isPending ? <LoaderCircle className="animate-spin" aria-hidden /> : null}
                {command.isPending ? "Đang xử lý" : "Xác nhận"}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function BackToOrders() {
  return <Button nativeButton={false} variant="ghost" size="sm" render={<Link href="/admin/orders" />}><ArrowLeft aria-hidden />Danh sách đơn hàng</Button>;
}

function Info({ label, children, className }: { label: string; children: React.ReactNode; className?: string }) {
  return <div className={className}><p className="text-caption text-muted-foreground">{label}</p><div className="mt-1">{children}</div></div>;
}

function MoneyRow({ label, amount }: { label: string; amount: number }) {
  return <div className="flex justify-between gap-4 text-[14px]"><span className="text-muted-foreground">{label}</span><span className="tabular-nums">{formatVND(amount)}</span></div>;
}

function AdminOrderDetailSkeleton() {
  return (
    <div className="space-y-8" aria-busy="true" aria-label="Đang tải chi tiết đơn hàng">
      <Skeleton className="h-9 w-40" />
      <div className="space-y-3"><Skeleton className="h-10 w-72" /><Skeleton className="h-4 w-96 max-w-full" /></div>
      <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_340px]"><Skeleton className="h-96 rounded-xl" /><Skeleton className="h-80 rounded-xl" /></div>
    </div>
  );
}
