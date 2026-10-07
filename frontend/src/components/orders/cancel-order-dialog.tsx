"use client";

import { LoaderCircle } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { ErrorState } from "@/components/ui/error-state";

export function CancelOrderDialog({
  open,
  onOpenChange,
  orderId,
  pending,
  error,
  onConfirm,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  orderId: string;
  pending: boolean;
  error: unknown;
  onConfirm: () => void;
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Huỷ đơn #{orderId}?</DialogTitle>
          <DialogDescription>
            Yêu cầu chỉ được tiếp nhận khi đơn chưa thanh toán. Hệ thống sẽ kiểm tra giao dịch và giải phóng phần hàng đang giữ trước khi huỷ hoàn tất.
          </DialogDescription>
        </DialogHeader>

        {error ? <ErrorState error={error} className="mt-5" /> : null}

        <DialogFooter>
          <DialogClose render={<Button type="button" variant="secondary" disabled={pending} />}>
            Giữ đơn hàng
          </DialogClose>
          <Button type="button" variant="destructive" disabled={pending} onClick={onConfirm}>
            {pending ? <LoaderCircle className="animate-spin" aria-hidden /> : null}
            {pending ? "Đang gửi yêu cầu…" : "Xác nhận huỷ đơn"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
