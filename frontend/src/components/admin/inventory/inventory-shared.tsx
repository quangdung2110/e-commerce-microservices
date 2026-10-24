"use client";

import Link from "next/link";
import { CircleCheck, LoaderCircle } from "lucide-react";

import { IdChip } from "@/components/admin/id-chip";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import type { StockMutationResponse } from "@/lib/api/inventory-admin";
import { formatDateTime } from "@/lib/format";

/**
 * Đổi query của trang hiện tại mà không qua `router.replace`.
 *
 * Với trang được prerender tĩnh, `router.replace` tới cùng pathname nhưng khác query bị Next
 * kéo ngược về query của lần tải đầu khi người dùng mở thẳng URL có `?view=…`, nên bấm tab
 * không đổi gì. `history.replaceState` được Next đồng bộ với `useSearchParams`.
 */
export function replaceQuery(path: string, query: URLSearchParams) {
  const search = query.toString();
  window.history.replaceState(null, "", search ? `${path}?${search}` : path);
}

export function FieldBlock({
  id,
  label,
  description,
  children,
}: {
  id: string;
  label: string;
  description?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="space-y-2">
      <label htmlFor={id} className="text-[14px] font-medium text-foreground">
        {label}
      </label>
      {children}
      {description ? (
        <p id={`${id}-description`} className="text-caption text-muted-foreground">
          {description}
        </p>
      ) : null}
    </div>
  );
}

export function SubmitButton({
  pending,
  retry,
  retryAfterSeconds = 0,
  disabled,
  children,
}: {
  pending: boolean;
  retry?: boolean;
  retryAfterSeconds?: number;
  disabled?: boolean;
  children: React.ReactNode;
}) {
  return (
    <Button type="submit" disabled={pending || retryAfterSeconds > 0 || disabled}>
      {pending ? <LoaderCircle className="animate-spin" aria-hidden /> : null}
      {pending
        ? "Đang ghi nhận"
        : retryAfterSeconds > 0
          ? `Thử lại sau ${retryAfterSeconds} giây`
          : retry
            ? "Thử lại đúng lệnh"
            : children}
    </Button>
  );
}

export function MutationResult({ result }: { result: StockMutationResponse }) {
  return (
    <Alert variant="success" icon={CircleCheck} title={result.replayed ? "Lệnh đã được phát lại an toàn" : "Đã ghi nhận lệnh"}>
      <div className="mt-2 grid gap-3 sm:grid-cols-2">
        <ResultItem label="Mã lệnh">
          <IdChip value={result.command_id} />
        </ResultItem>
        <ResultItem label="Mã thao tác">
          <IdChip value={result.operation_id} />
        </ResultItem>
        {result.movement_id ? (
          <ResultItem label="Movement">
            <Button
              variant="outline"
              size="sm"
              nativeButton={false}
              render={<Link href={`/admin/inventory/movements/${encodeURIComponent(result.movement_id)}`} />}
            >
              Xem movement
            </Button>
          </ResultItem>
        ) : null}
        <ResultItem label="Ghi nhận lúc">
          <span>{formatDateTime(result.recorded_at)}</span>
        </ResultItem>
      </div>
    </Alert>
  );
}

export function CommandRecovery({ commandId }: { commandId?: string }) {
  return (
    <Alert variant="warning" title="Kết quả lệnh chưa rõ">
      <p>Giữ trang này để thử lại đúng lệnh. Thông tin đã gửi được giữ nguyên cho đến khi xác định được kết quả.</p>
      {commandId ? (
        <div className="mt-3 flex flex-wrap items-center gap-3">
          <IdChip value={commandId} />
          <Link className="underline" href={`/admin/inventory/commands/${encodeURIComponent(commandId)}`} target="_blank" rel="noopener noreferrer">
            Tra cứu kết quả lệnh (tab mới)
          </Link>
        </div>
      ) : null}
    </Alert>
  );
}

export function ResultItem({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="space-y-1">
      <p className="text-caption opacity-80">{label}</p>
      <div className="text-[14px] text-foreground">{children}</div>
    </div>
  );
}

export function DetailSkeleton() {
  return (
    <div className="space-y-8" aria-busy="true" aria-label="Đang tải dữ liệu kho">
      <div className="space-y-3">
        <Skeleton className="h-10 w-64" />
        <Skeleton className="h-4 w-full max-w-md" />
      </div>
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {Array.from({ length: 6 }).map((_, index) => (
          <Skeleton key={index} className="h-28 rounded-xl" />
        ))}
      </div>
    </div>
  );
}
