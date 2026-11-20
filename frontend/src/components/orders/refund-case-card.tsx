"use client";

import { Alert } from "@/components/ui/alert";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { ErrorState } from "@/components/ui/error-state";
import { Skeleton } from "@/components/ui/skeleton";
import { RefundStatus } from "@/components/orders/order-status";
import { useRefundCase } from "@/hooks/use-orders";
import type { OrderLine } from "@/lib/api/order";
import { StorefrontImage } from "@/components/catalog/storefront-image";
import { formatDateTime, formatVND } from "@/lib/format";

const FINALIZATION: Record<string, string> = {
  NOT_REQUIRED: "Không cần xử lý kho",
  NOT_REQUESTED: "Chưa gửi yêu cầu tới kho",
  PENDING: "Kho đang xử lý hàng trả",
  RECOVERY_REQUIRED: "Cần khôi phục xử lý kho",
  CONFLICT: "Thông tin kho cần được kiểm tra",
  COMPLETED: "Kho đã hoàn tất",
};

export function RefundCaseCard({ orderId, orderLines = [] }: { orderId: string; orderLines?: OrderLine[] }) {
  const refund = useRefundCase(orderId, true);

  return (
    <Card>
      <CardHeader>
        <CardTitle>Trả hàng và hoàn tiền</CardTitle>
      </CardHeader>
      <CardContent className="space-y-5">
        {refund.isPending ? (
          <div className="space-y-3" aria-busy="true" aria-label="Đang tải trạng thái hoàn tiền">
            <Skeleton className="h-6 w-44" /><Skeleton className="h-5 w-2/3" /><Skeleton className="h-5 w-1/2" />
          </div>
        ) : refund.isError ? (
          <ErrorState error={refund.error} onRetry={() => void refund.refetch()} message="Chưa tải được tiến trình hoàn tiền. Thông tin đơn hàng khác vẫn dùng được." />
        ) : refund.data ? (
          <>
            <div className="flex flex-wrap items-center justify-between gap-4">
              <div className="space-y-1">
                <p className="text-caption text-muted-foreground">Số tiền dự kiến</p>
                <p className="font-serif text-headline-4 font-normal tabular-nums">{formatVND(refund.data.amount.amount_minor)}</p>
              </div>
              <RefundStatus status={refund.data.status} />
            </div>
            <dl className="grid gap-4 rounded-xl bg-surface-2 p-4 text-[14px] sm:grid-cols-2">
              <div><dt className="text-caption text-muted-foreground">Bắt đầu</dt><dd>{formatDateTime(refund.data.started_at)}</dd></div>
              <div><dt className="text-caption text-muted-foreground">Cập nhật</dt><dd>{formatDateTime(refund.data.updated_at)}</dd></div>
              <div><dt className="text-caption text-muted-foreground">Sản phẩm trả</dt><dd>{refund.data.lines.length} dòng sản phẩm</dd></div>
              <div><dt className="text-caption text-muted-foreground">Xử lý tại kho</dt><dd>{FINALIZATION[refund.data.scope_finalization?.status ?? ""] ?? "Đang cập nhật"}</dd></div>
            </dl>
            <section className="space-y-3" aria-label="Chi tiết sản phẩm trả và tiền hoàn">
              <h3 className="font-medium">Sản phẩm trong yêu cầu trả hàng</h3>
              <ul className="divide-y divide-border rounded-xl border border-border px-4">{refund.data.lines.map(line => {
                const snapshot = orderLines.find(item => item.order_line_id === line.order_line_id);
                return <li key={line.order_line_id} className="space-y-3 py-4">
                  <div className="flex gap-3">
                  {snapshot?.snapshot_image_url && <div className="size-16 shrink-0 overflow-hidden rounded-lg bg-surface-2"><StorefrontImage src={snapshot.snapshot_image_url} alt={snapshot.snapshot_name} className="size-full object-cover" /></div>}
                  <div className="min-w-0 space-y-1"><p className="break-words font-medium">{snapshot?.snapshot_name ?? `Dòng sản phẩm #${line.order_line_id}`}</p>
                    {snapshot && Object.keys(snapshot.snapshot_attributes).length > 0 && <p className="text-caption text-muted-foreground">{Object.values(snapshot.snapshot_attributes).join(" · ")}</p>}
                    <p className="text-caption text-muted-foreground">Số lượng trả: {line.expected_quantity}</p>
                  </div>
                  </div>
                  <dl className="space-y-2 text-[14px]">
                    <div className="flex flex-wrap justify-between gap-2"><dt>Giá sản phẩm ({line.expected_quantity} × {formatVND(line.unit_price_snapshot.amount_minor)})</dt><dd className="tabular-nums">{formatVND(line.expected_quantity * line.unit_price_snapshot.amount_minor)}</dd></div>
                    <div className="flex flex-wrap justify-between gap-2"><dt>Giảm giá được phân bổ</dt><dd className="tabular-nums">−{formatVND(line.discount_share_snapshot.amount_minor)}</dd></div>
                    <div className="flex flex-wrap justify-between gap-2 font-medium"><dt>Số tiền hoàn của sản phẩm</dt><dd className="tabular-nums">{formatVND(line.net_amount_snapshot.amount_minor)}</dd></div>
                  </dl>
                </li>;
              })}</ul>
              <p className="text-caption text-muted-foreground">Tiền hoàn được tính sau phần giảm giá đã phân bổ cho từng sản phẩm.</p>
            </section>
            {refund.data.status === "MANUAL_VERIFICATION_REQUIRED" || refund.data.scope_finalization?.status === "RECOVERY_REQUIRED" || refund.data.scope_finalization?.status === "CONFLICT" ? (
              <Alert variant="warning" title="Hồ sơ đang được kiểm tra">
                Bạn chưa cần gửi lại yêu cầu. Bộ phận vận hành sẽ tiếp tục từ hồ sơ hiện có.
              </Alert>
            ) : null}
          </>
        ) : null}
      </CardContent>
    </Card>
  );
}
