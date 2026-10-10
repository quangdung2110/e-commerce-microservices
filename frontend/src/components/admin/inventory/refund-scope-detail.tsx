"use client";

import { useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { DetailSkeleton } from "@/components/admin/inventory/inventory-shared";
import {
  ReturnCommandForm,
  type ReturnAction,
} from "@/components/admin/inventory/return-command-form";
import { PageHeader } from "@/components/admin/page-header";
import { StatusBadge } from "@/components/admin/status-badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { ErrorState } from "@/components/ui/error-state";
import { inventoryAdminApi } from "@/lib/api/inventory-admin";
import {
  canReceiveReturn,
  remainingReturnQuantity,
} from "@/lib/inventory-workflows";
import { REFUND_SCOPE_LABELS } from "@/lib/labels/inventory";

export function RefundScopeDetail({ scopeId }: { scopeId: string }) {
  const [busy, setBusy] = useState(false);
  const [action, setAction] = useState<ReturnAction | null>(null);
  const scope = useQuery({
    queryKey: ["inventory", "refund-scope", scopeId],
    queryFn: ({ signal }) => inventoryAdminApi.getRefundScope(scopeId, signal),
  });
  if (scope.isPending) return <DetailSkeleton />;
  if (!scope.data)
    return (
      <ErrorState error={scope.error} onRetry={() => void scope.refetch()} />
    );
  const data = scope.data;
  return (
    <div className="space-y-8">
      <PageHeader
        title="Nhận hàng trả"
        description={`Đơn ${data.order_id} · Yêu cầu ${data.refund_scope_id}`}
        action={<StatusBadge value={data.state} labels={REFUND_SCOPE_LABELS} />}
      />
      {scope.isError ? (
        <ErrorState error={scope.error} onRetry={() => void scope.refetch()} />
      ) : null}
      <div className="flex flex-wrap gap-3">
        <Button
          variant="outline"
          nativeButton={false}
          render={
            <Link href={`/admin/orders/${encodeURIComponent(data.order_id)}`} />
          }
        >
          Xem đơn hàng
        </Button>
        <Button
          variant="outline"
          nativeButton={false}
          render={<Link href="/admin/inventory/operations?view=inspection" />}
        >
          Hàng đợi kiểm định
        </Button>
        <Button
          variant="outline"
          nativeButton={false}
          render={<Link href="/admin/inventory/operations?view=quarantine" />}
        >
          Hàng cách ly
        </Button>
      </div>
      <div className="grid gap-5 lg:grid-cols-2">
        {data.lines.map((line) => (
          <Card key={line.order_line_id}>
            <CardHeader>
              <CardTitle>Dòng {line.order_line_id}</CardTitle>
            </CardHeader>
            <CardContent className="space-y-5">
              <p className="break-all text-[14px]">SKU: {line.sku_id}</p>
              <dl className="grid grid-cols-2 gap-3 text-[14px]">
                <dt>Cần nhận</dt>
                <dd>{line.expected_quantity}</dd>
                <dt>Đã nhận</dt>
                <dd>{line.received_quantity}</dd>
                <dt>Kiểm định đạt</dt>
                <dd>{line.passed_quantity}</dd>
                <dt>Không đạt</dt>
                <dd>{line.failed_quantity}</dd>
              </dl>
              <Button
                variant="outline"
                disabled={
                  !canReceiveReturn(data) ||
                  remainingReturnQuantity(line) === 0 ||
                  scope.isError
                }
                onClick={() =>
                  setAction({
                    kind: "receipt",
                    refund_scope_id: data.refund_scope_id,
                    order_line_id: line.order_line_id,
                    actual_sku_id: line.sku_id,
                    max: remainingReturnQuantity(line),
                  })
                }
              >
                Nhận hàng vào cách ly
              </Button>
            </CardContent>
          </Card>
        ))}
      </div>
      <Dialog
        open={action !== null}
        onOpenChange={(open) => {
          if (!open && !busy) setAction(null);
        }}
        disablePointerDismissal={busy}
      >
        <DialogContent showCloseButton={!busy}>
          <DialogHeader>
            <DialogTitle>Nhận hàng trả</DialogTitle>
            <DialogDescription>
              Chỉ nhận đúng SKU của dòng đơn, trong số lượng còn thiếu.
            </DialogDescription>
          </DialogHeader>
          <div className="mt-6">
            {action ? (
              <ReturnCommandForm action={action} onBusyChange={setBusy} />
            ) : null}
          </div>
        </DialogContent>
      </Dialog>
    </div>
  );
}
