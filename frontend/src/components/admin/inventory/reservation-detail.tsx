"use client";

import { useState } from "react";
import Link from "next/link";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, LoaderCircle, PackageCheck } from "lucide-react";

import { DateTimeInput } from "@/components/admin/date-time-input";
import { FieldBlock, MutationResult } from "@/components/admin/inventory/inventory-shared";
import { IdChip } from "@/components/admin/id-chip";
import { PageHeader } from "@/components/admin/page-header";
import { StatusBadge } from "@/components/admin/status-badge";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
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
import { Skeleton } from "@/components/ui/skeleton";
import { useToast } from "@/components/ui/toast";
import { useCommand } from "@/hooks/use-command";
import { adminOrderKeys } from "@/hooks/use-admin-orders";
import {
  inventoryAdminApi,
  type AdminReservationLine,
  type PhysicalDispatchRequest,
  type StockMutationResponse,
} from "@/lib/api/inventory-admin";
import { inventoryErrorMessage } from "@/lib/inventory";
import type { EnumLabel } from "@/lib/labels/types";
import { isRetryable } from "@/lib/api/problem";

const RESERVATION_STATE_LABELS: Record<string, EnumLabel> = {
  UNKNOWN: { text: "Chưa xác định", tone: "default" },
  RESERVED: { text: "Đang giữ hàng", tone: "warning" },
  COMMITTED: { text: "Đã cam kết", tone: "info" },
  PARTIALLY_DISPATCHED: { text: "Đã xuất kho một phần", tone: "info" },
  DISPATCHED: { text: "Đã xuất kho", tone: "success" },
  RELEASED: { text: "Đã giải phóng", tone: "default" },
};

export function ReservationDetail({ orderId }: { orderId: string }) {
  const queryClient = useQueryClient();
  const { addToast } = useToast();
  const [selectedLine, setSelectedLine] = useState<AdminReservationLine | null>(null);
  const [result, setResult] = useState<StockMutationResponse | null>(null);
  const [dispatchId, setDispatchId] = useState("");
  const [dispatchLineId, setDispatchLineId] = useState("");
  const [handedOverAt, setHandedOverAt] = useState<string>();
  const [handoverReference, setHandoverReference] = useState("");
  const [evidenceReference, setEvidenceReference] = useState("");

  const reservation = useQuery({
    queryKey: ["inventory", "reservation", orderId],
    queryFn: ({ signal }) => inventoryAdminApi.getReservation(orderId, signal),
  });

  const dispatch = useCommand<StockMutationResponse, PhysicalDispatchRequest>({
    mutationFn: (input, { commandId }) =>
      inventoryAdminApi.recordPhysicalDispatch(input, commandId),
    onSuccess: async (next) => {
      setResult(next);
      setSelectedLine(null);
      addToast({ type: "success", title: "Đã ghi nhận hàng rời kho" });
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["inventory", "reservation", orderId] }),
        queryClient.invalidateQueries({ queryKey: adminOrderKeys.detail(orderId) }),
        queryClient.invalidateQueries({ queryKey: adminOrderKeys.lists() }),
      ]);
    },
  });

  function openDispatch(line: AdminReservationLine) {
    dispatch.reset();
    dispatch.startNewIntent();
    setDispatchId("");
    setDispatchLineId("");
    setHandedOverAt(new Date().toISOString());
    setHandoverReference("");
    setEvidenceReference("");
    setSelectedLine(line);
  }

  function edited() {
    if (!dispatch.isError) return;
    dispatch.reset();
    dispatch.startNewIntent();
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!selectedLine || !handedOverAt) return;
    try {
      await dispatch.mutateAsync({
        dispatch_id: dispatchId.trim(),
        dispatch_line_id: dispatchLineId.trim(),
        order_id: orderId,
        order_line_id: selectedLine.order_line_id,
        warehouse_id: selectedLine.warehouse_id,
        sku_id: selectedLine.sku_id,
        quantity: selectedLine.quantity,
        handed_over_at: handedOverAt,
        handover_reference: handoverReference.trim(),
        evidence_reference: evidenceReference.trim(),
      });
    } catch {
      // React Query giữ lỗi để ErrorState hiển thị trong dialog.
    }
  }

  if (reservation.isPending) return <ReservationDetailSkeleton />;
  if (reservation.isError || !reservation.data) {
    return (
      <div className="space-y-6">
        <BackToOrder orderId={orderId} />
        <ErrorState
          error={reservation.error}
          message={inventoryErrorMessage(reservation.error)}
          onRetry={() => void reservation.refetch()}
        />
      </div>
    );
  }

  const data = reservation.data;
  const committedCount = data.lines.filter((line) => line.state === "COMMITTED").length;

  return (
    <div className="space-y-8">
      <BackToOrder orderId={orderId} />
      <PageHeader
        title={`Phân bổ đơn #${data.order_id}`}
        description={`Revision ${data.revision} · ${data.lines.length} dòng phân bổ. Mỗi lần xuất kho phải ghi nhận nguyên số lượng của đúng dòng.`}
        action={<StatusBadge value={data.state} labels={RESERVATION_STATE_LABELS} />}
      />

      {result ? <MutationResult result={result} /> : null}
      {committedCount > 0 ? (
        <Alert variant="warning" title={`${committedCount} dòng đang chờ xuất kho`}>
          Chỉ xác nhận sau khi hàng thực tế đã được bàn giao. Thao tác này giảm tồn vật lý và không thể dùng adjustment để đảo ngược.
        </Alert>
      ) : null}

      <div className="grid gap-4 lg:grid-cols-2">
        {data.lines.map((line) => (
          <Card key={line.order_line_id}>
            <CardHeader className="border-b border-border">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <CardTitle>Dòng #{line.order_line_id}</CardTitle>
                <StatusBadge value={line.state} labels={RESERVATION_STATE_LABELS} />
              </div>
            </CardHeader>
            <CardContent className="space-y-5">
              <dl className="grid gap-4 text-[14px] sm:grid-cols-2">
                <Fact label="Kho"><IdChip value={line.warehouse_id} label="Sao chép mã kho" /></Fact>
                <Fact label="SKU"><IdChip value={line.sku_id} label="Sao chép mã SKU" /></Fact>
                <Fact label="Số lượng"><span className="font-medium tabular-nums">{line.quantity.toLocaleString("vi-VN")}</span></Fact>
                <Fact label="Mã dòng đơn"><IdChip value={line.order_line_id} label="Sao chép mã dòng đơn" /></Fact>
              </dl>
              <Button className="w-full" disabled={line.state !== "COMMITTED"} onClick={() => openDispatch(line)}>
                <PackageCheck aria-hidden />
                {line.state === "COMMITTED" ? "Ghi nhận xuất kho" : "Không thể xuất kho"}
              </Button>
            </CardContent>
          </Card>
        ))}
      </div>

      <Dialog open={selectedLine !== null} onOpenChange={(open) => { if (!open && !dispatch.isPending) setSelectedLine(null); }} disablePointerDismissal={dispatch.isPending}>
        <DialogContent showCloseButton={!dispatch.isPending}>
          <form onSubmit={submit} onChange={edited}>
            <DialogHeader>
              <DialogTitle>Ghi nhận xuất kho dòng #{selectedLine?.order_line_id}</DialogTitle>
              <DialogDescription>
                Kho, SKU và số lượng được khoá theo phân bổ. Hãy nhập định danh phiếu và bằng chứng bàn giao thực tế.
              </DialogDescription>
            </DialogHeader>
            <div className="mt-6 space-y-5">
              <div className="grid gap-5 sm:grid-cols-2">
                <FieldBlock id="dispatch-id" label="Mã phiếu xuất *">
                  <Input id="dispatch-id" required maxLength={128} value={dispatchId} disabled={dispatch.isPending} onChange={(event) => setDispatchId(event.target.value)} />
                </FieldBlock>
                <FieldBlock id="dispatch-line-id" label="Mã dòng phiếu *">
                  <Input id="dispatch-line-id" required maxLength={128} value={dispatchLineId} disabled={dispatch.isPending} onChange={(event) => setDispatchLineId(event.target.value)} />
                </FieldBlock>
              </div>
              <FieldBlock id="dispatch-handed-over" label="Bàn giao lúc *" description="Hiển thị theo giờ Việt Nam.">
                <DateTimeInput id="dispatch-handed-over" required value={handedOverAt} disabled={dispatch.isPending} onValueChange={setHandedOverAt} />
              </FieldBlock>
              <FieldBlock id="dispatch-handover-reference" label="Mã bàn giao / vận đơn *">
                <Input id="dispatch-handover-reference" required maxLength={128} value={handoverReference} disabled={dispatch.isPending} onChange={(event) => setHandoverReference(event.target.value)} />
              </FieldBlock>
              <FieldBlock id="dispatch-evidence-reference" label="Tham chiếu bằng chứng *">
                <Input id="dispatch-evidence-reference" required maxLength={512} value={evidenceReference} disabled={dispatch.isPending} onChange={(event) => setEvidenceReference(event.target.value)} />
              </FieldBlock>
              {dispatch.isError ? (
                <ErrorState error={dispatch.error} message={inventoryErrorMessage(dispatch.error)} />
              ) : null}
            </div>
            <DialogFooter>
              <Button type="button" variant="secondary" disabled={dispatch.isPending} onClick={() => setSelectedLine(null)}>Quay lại</Button>
              <Button type="submit" disabled={dispatch.isPending || dispatch.retryDelaySeconds > 0 || !handedOverAt || (dispatch.isError && !isRetryable(dispatch.error))}>
                {dispatch.isPending ? <LoaderCircle className="animate-spin" aria-hidden /> : null}
                {dispatch.isPending ? "Đang ghi nhận" : dispatch.retryDelaySeconds > 0 ? `Thử lại sau ${dispatch.retryDelaySeconds} giây` : dispatch.isError && isRetryable(dispatch.error) ? "Thử lại đúng lệnh" : "Xác nhận xuất kho"}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function BackToOrder({ orderId }: { orderId: string }) {
  return <Button nativeButton={false} variant="ghost" size="sm" render={<Link href={`/admin/orders/${encodeURIComponent(orderId)}`} />}><ArrowLeft aria-hidden />Chi tiết đơn hàng</Button>;
}

function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return <div className="space-y-1"><dt className="text-caption text-muted-foreground">{label}</dt><dd>{children}</dd></div>;
}

function ReservationDetailSkeleton() {
  return (
    <div className="space-y-8" aria-busy="true" aria-label="Đang tải phân bổ kho">
      <Skeleton className="h-9 w-40" />
      <div className="space-y-3"><Skeleton className="h-10 w-72" /><Skeleton className="h-4 w-96 max-w-full" /></div>
      <div className="grid gap-4 lg:grid-cols-2"><Skeleton className="h-72 rounded-xl" /><Skeleton className="h-72 rounded-xl" /></div>
    </div>
  );
}
