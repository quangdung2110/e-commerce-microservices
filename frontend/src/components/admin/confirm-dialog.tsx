"use client";

import { useId, useState } from "react";
import { LoaderCircle } from "lucide-react";

import { Button } from "@/components/ui/button";
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
import { Textarea } from "@/components/ui/textarea";

interface ConfirmDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description: string;
  confirmLabel: string;
  onConfirm: (reason: string) => void | Promise<void>;
  /** Giá trị phải gõ lại cho hành động không thể hoàn tác. */
  expectedText?: string;
  reasonRequired?: boolean;
  destructive?: boolean;
}

export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel,
  onConfirm,
  expectedText,
  reasonRequired = true,
  destructive = true,
}: ConfirmDialogProps) {
  const reasonId = useId();
  const confirmationId = useId();
  const [reason, setReason] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [error, setError] = useState<unknown>();
  const [isSubmitting, setIsSubmitting] = useState(false);

  function resetAndClose() {
    setReason("");
    setConfirmation("");
    setError(undefined);
    setIsSubmitting(false);
    onOpenChange(false);
  }

  const canConfirm =
    (!reasonRequired || reason.trim().length > 0) &&
    (!expectedText || confirmation === expectedText) &&
    !isSubmitting;

  async function submit() {
    if (!canConfirm) return;
    setError(undefined);
    setIsSubmitting(true);
    try {
      await onConfirm(reason.trim());
      resetAndClose();
    } catch (caught) {
      setError(caught);
      setIsSubmitting(false);
    }
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(nextOpen) => {
        if (nextOpen) onOpenChange(true);
        else resetAndClose();
      }}
      disablePointerDismissal={isSubmitting}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>

        <div className="mt-6 space-y-5">
          {error ? <ErrorState error={error} /> : null}
          <div className="space-y-2">
            <Label htmlFor={reasonId}>Lý do{reasonRequired ? " *" : ""}</Label>
            <Textarea
              id={reasonId}
              value={reason}
              required={reasonRequired}
              disabled={isSubmitting}
              placeholder="Nêu rõ lý do thực hiện hành động này"
              onChange={(event) => setReason(event.target.value)}
            />
          </div>

          {expectedText ? (
            <div className="space-y-2">
              <Label htmlFor={confirmationId}>
                Gõ <span className="font-mono">{expectedText}</span> để xác nhận
              </Label>
              <Input
                id={confirmationId}
                value={confirmation}
                disabled={isSubmitting}
                autoComplete="off"
                onChange={(event) => setConfirmation(event.target.value)}
              />
            </div>
          ) : null}
        </div>

        <DialogFooter>
          <Button
            type="button"
            variant="secondary"
            disabled={isSubmitting}
            onClick={resetAndClose}
          >
            Quay lại
          </Button>
          <Button
            type="button"
            variant={destructive ? "destructive" : "default"}
            disabled={!canConfirm}
            onClick={() => void submit()}
          >
            {isSubmitting ? <LoaderCircle className="animate-spin" aria-hidden /> : null}
            {isSubmitting ? "Đang xử lý" : confirmLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
