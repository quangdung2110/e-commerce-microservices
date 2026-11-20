"use client";

import { useState } from "react";
import { LoaderCircle } from "lucide-react";

import { DateTimeInput } from "@/components/admin/date-time-input";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
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
import { useToast } from "@/components/ui/toast";
import { useAdminDeliveryConfirmation } from "@/hooks/use-admin-orders";
import type { OrderLine } from "@/lib/api/order";
import { formatDateTime } from "@/lib/format";

interface DeliveryConfirmationDialogProps {
  orderId: string;
  /** Các dòng đang DISPATCHED (kho đã xuất, chưa xác nhận giao). */
  lines: OrderLine[];
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/**
 * Admin xác nhận các dòng đã xuất kho đã tới khách (Order ADR-021). Một mã tham chiếu
 * (mã vận đơn/biên nhận) và một thời điểm giao dùng chung cho các dòng được chọn.
 */
export function DeliveryConfirmationDialog({
  orderId,
  lines,
  open,
  onOpenChange,
}: DeliveryConfirmationDialogProps) {
  const { addToast } = useToast();
  const confirm = useAdminDeliveryConfirmation(orderId);
  const [selected, setSelected] = useState<Set<string>>(() => new Set(lines.map((line) => line.order_line_id)));
  const [reference, setReference] = useState("");
  const [deliveredAt, setDeliveredAt] = useState<string | undefined>(() => new Date().toISOString());

  function toggle(lineId: string, checked: boolean) {
    setSelected((current) => {
      const next = new Set(current);
      if (checked) next.add(lineId);
      else next.delete(lineId);
      return next;
    });
  }

  const canSubmit = selected.size > 0 && reference.trim().length > 0 && deliveredAt !== undefined && !confirm.isPending;

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!canSubmit || deliveredAt === undefined) return;
    try {
      const result = await confirm.mutateAsync({
        lines: lines
          .filter((line) => selected.has(line.order_line_id))
          .map((line) => ({
            order_line_id: line.order_line_id,
            delivered_at: deliveredAt,
            delivery_reference: reference.trim(),
          })),
      });
      addToast({
        type: "success",
        title: result.order_completed ? "Đơn hàng đã hoàn tất" : "Đã xác nhận giao hàng",
        message: result.order_completed
          ? "Mọi dòng hàng đã được giao; người mua có thể đánh giá sản phẩm."
          : "Các dòng còn lại cần được kho xuất và xác nhận giao riêng.",
      });
      onOpenChange(false);
    } catch {
      // React Query giữ lỗi để ErrorState hiển thị trong dialog.
    }
  }

  return (
    <Dialog open={open} onOpenChange={(next) => { if (!confirm.isPending) onOpenChange(next); }} disablePointerDismissal={confirm.isPending}>
      <DialogContent showCloseButton={!confirm.isPending}>
        <form onSubmit={submit}>
          <DialogHeader>
            <DialogTitle>Xác nhận đã giao hàng</DialogTitle>
            <DialogDescription>
              Chỉ xác nhận khi hàng đã tới tay người nhận. Dòng cuối cùng được xác nhận sẽ hoàn tất đơn hàng và mở quyền đánh giá cho người mua.
            </DialogDescription>
          </DialogHeader>
          <div className="mt-6 space-y-5">
            <fieldset className="space-y-2">
              <legend className="text-[14px] font-medium">Dòng hàng đã xuất kho</legend>
              <div className="divide-y divide-border rounded-xl border border-border">
                {lines.map((line) => (
                  <label key={line.order_line_id} className="flex min-h-11 items-start gap-3 p-3 text-[14px]">
                    <Checkbox
                      checked={selected.has(line.order_line_id)}
                      disabled={confirm.isPending}
                      onCheckedChange={(checked) => toggle(line.order_line_id, checked === true)}
                    />
                    <span className="min-w-0">
                      <span className="block font-medium">{line.snapshot_name}</span>
                      <span className="block text-caption text-muted-foreground">
                        Số lượng {line.quantity} · mã dòng {line.order_line_id}
                      </span>
                    </span>
                  </label>
                ))}
              </div>
              <p className="text-caption text-muted-foreground">
                Dòng chưa được kho xuất không xuất hiện ở đây và chưa thể xác nhận.
              </p>
            </fieldset>
            <div className="space-y-2">
              <Label htmlFor="delivery-reference">Mã vận đơn / biên nhận *</Label>
              <Input
                id="delivery-reference"
                value={reference}
                required
                maxLength={128}
                autoComplete="off"
                disabled={confirm.isPending}
                onChange={(event) => setReference(event.target.value)}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="delivery-delivered-at">Thời điểm khách nhận hàng *</Label>
              <DateTimeInput
                id="delivery-delivered-at"
                value={deliveredAt}
                disabled={confirm.isPending}
                onValueChange={setDeliveredAt}
              />
              {deliveredAt ? (
                <p className="text-caption text-muted-foreground">{formatDateTime(deliveredAt)}</p>
              ) : null}
            </div>
            {confirm.isError ? <ErrorState error={confirm.error} /> : null}
            {confirm.isSuccess && !confirm.data.order_completed ? (
              <Alert variant="info" title="Đã ghi nhận">
                Đơn hàng vẫn chờ các dòng còn lại.
              </Alert>
            ) : null}
          </div>
          <DialogFooter>
            <Button type="button" variant="secondary" disabled={confirm.isPending} onClick={() => onOpenChange(false)}>
              Quay lại
            </Button>
            <Button type="submit" disabled={!canSubmit}>
              {confirm.isPending ? <LoaderCircle className="animate-spin" aria-hidden /> : null}
              {confirm.isPending ? "Đang xác nhận" : "Xác nhận đã giao"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
