"use client";

import { Alert } from "@/components/ui/alert";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { ErrorState } from "@/components/ui/error-state";
import { Skeleton } from "@/components/ui/skeleton";
import { RefundStatus } from "@/components/orders/order-status";
import { useRefundCase } from "@/hooks/use-orders";
import { formatDateTime, formatVND } from "@/lib/format";

const FINALIZATION: Record<string, string> = {
  NOT_REQUIRED: "Không cần xử lý kho",
  NOT_REQUESTED: "Chưa gửi yêu cầu tới kho",
  PENDING: "Kho đang xử lý hàng trả",
  RECOVERY_REQUIRED: "Cần khôi phục xử lý kho",
  CONFLICT: "Thông tin kho cần được kiểm tra",
  COMPLETED: "Kho đã hoàn tất",
};

export function RefundCaseCard({ orderId }: { orderId: string }) {
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
