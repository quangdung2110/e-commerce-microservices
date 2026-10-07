"use client";

import { useEffect } from "react";
import { LoaderCircle } from "lucide-react";

import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

export function StickySaveBar({
  isDirty,
  isSaving,
  onSave,
  onDiscard,
  className,
}: {
  isDirty: boolean;
  isSaving?: boolean;
  onSave: () => void;
  onDiscard?: () => void;
  className?: string;
}) {
  useEffect(() => {
    if (!isDirty) return;
    const warn = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [isDirty]);

  if (!isDirty) return null;

  return (
    <div
      role="status"
      className={cn(
        "sticky bottom-4 z-20 flex flex-col gap-3 rounded-xl border border-border-strong bg-surface-overlay p-4 shadow-md sm:flex-row sm:items-center sm:justify-between",
        className
      )}
    >
      <p className="text-[14px] font-medium">Bạn có thay đổi chưa lưu.</p>
      <div className="flex justify-end gap-3">
        {onDiscard ? (
          <Button type="button" variant="secondary" size="sm" onClick={onDiscard}>
            Bỏ thay đổi
          </Button>
        ) : null}
        <Button type="button" size="sm" disabled={isSaving} onClick={onSave}>
          {isSaving ? <LoaderCircle className="animate-spin" aria-hidden /> : null}
          {isSaving ? "Đang lưu" : "Lưu thay đổi"}
        </Button>
      </div>
    </div>
  );
}
