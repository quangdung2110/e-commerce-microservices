"use client";

import { Ticket } from "lucide-react";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { ErrorState } from "@/components/ui/error-state";
import { Skeleton } from "@/components/ui/skeleton";
import type { useCheckoutVouchers } from "@/hooks/use-checkout-vouchers";
import { PollTimeoutError } from "@/lib/api/operation";
import { retryAfterSeconds } from "@/lib/api/problem";
import { voucherDiscount, voucherReason } from "@/lib/checkout-voucher";
import { formatDateTime, formatVND } from "@/lib/format";

export function CheckoutVoucherTray({ vouchers, disabled, expired, appliedId, now, onApply, onRemove }: {
  vouchers: ReturnType<typeof useCheckoutVouchers>;
  disabled: boolean;
  expired: boolean;
  appliedId: string | null;
  now: number;
  onApply: (id: string) => void;
  onRemove: () => void;
}) {
  const { options, acquire, busy, unresolved } = vouchers;
  const readRetryAfter = retryAfterSeconds(options.error);
  const readDelay = readRetryAfter ? Math.max(0, Math.ceil((options.errorUpdatedAt + readRetryAfter * 1_000 - now) / 1_000)) : 0;
  const acquisitionPending = acquire.isPending || unresolved;
  const claimDelay = Math.max(0, Math.ceil((vouchers.claimRetryAt - now) / 1_000));
  const acquireDelay = Math.max(acquire.retryDelaySeconds, claimDelay);

  return <Card>
    <CardHeader><CardTitle><h2>Voucher cho đơn hàng</h2></CardTitle></CardHeader>
    <CardContent className="space-y-5">
      <p className="text-caption text-muted-foreground">Chọn một voucher. Mức giảm dự kiến sẽ được xác nhận lại trong báo giá mới; lưu hoặc nhận voucher chưa giữ lượt sử dụng.</p>
      {appliedId && <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg bg-surface-2 p-4">
        <p className="min-w-0 break-words text-[14px]">Đang áp dụng: {options.data?.items.find((item) => item.promotion_id === appliedId)?.name ?? "Voucher đã chọn"}</p>
        <Button variant="outline" disabled={disabled || busy || expired} onClick={onRemove}>Bỏ voucher</Button>
      </div>}
      {acquisitionPending && <Alert variant="info">{unresolved ? "Chưa xác nhận được kết quả lưu hoặc nhận voucher. Hãy tiếp tục kiểm tra cùng yêu cầu." : "Đang lưu hoặc nhận voucher. Báo giá sẽ được cập nhật sau khi hoàn tất."}</Alert>}
      {acquire.isError && <div className="space-y-3">
        {!(acquire.error instanceof PollTimeoutError) && <ErrorState error={acquire.error} />}
        {unresolved && <Button variant="outline" disabled={acquireDelay > 0 || disabled} onClick={() => acquire.variables && acquire.mutate(acquire.variables)}>{acquireDelay > 0 ? `Thử lại sau ${acquireDelay} giây` : "Tiếp tục kiểm tra voucher"}</Button>}
      </div>}
      {expired ? <Alert variant="warning">Tải báo giá mới để xem voucher còn áp dụng được.</Alert> : <>
        {options.isError && <div className="space-y-3">
          <ErrorState error={options.error} />
          <Button variant="outline" disabled={disabled || busy || options.isFetching || readDelay > 0} onClick={() => void options.refetch()}>{readDelay > 0 ? `Thử lại sau ${readDelay} giây` : "Tải lại voucher"}</Button>
        </div>}
        {options.isPending ? <div className="space-y-4" aria-label="Đang tải voucher" aria-busy="true">{[0, 1].map((index) => <div key={index} className="space-y-3 rounded-lg border border-border p-4"><Skeleton className="h-5 w-2/3" /><Skeleton className="h-4 w-1/2" /><Skeleton className="h-11 w-36" /></div>)}</div>
          : options.data && !options.isError && options.data.items.length === 0 ? <EmptyState icon={Ticket} title="Chưa có voucher cho đơn này" description="Bạn có thể tiếp tục đặt hàng hoặc tải lại danh sách sau." action={<Button variant="outline" disabled={disabled || busy || options.isFetching} onClick={() => void options.refetch()}>Tải lại voucher</Button>} />
          : !options.isError && options.data ? <ul className="space-y-4" aria-busy={options.isFetching}>{options.data.items.map((option) => <li key={option.promotion_id} className="min-w-0 space-y-3 rounded-lg border border-border p-4">
            <div className="space-y-1"><h3 className="break-words font-serif text-headline-5 font-normal">{option.name}</h3><p className="break-all text-caption text-muted-foreground">{option.code} · {option.source === "WALLET" ? "Đã có trong ví" : "Voucher công khai"}</p></div>
            <p className="text-[15px] font-medium">{voucherDiscount(option)}</p>
            {option.description && <p className="break-words text-[14px] text-muted-foreground">{option.description}</p>}
            <p className="text-caption text-muted-foreground">Đơn tối thiểu {formatVND(option.min_order_amount.amount_minor)}{option.valid_until ? ` · Hạn dùng ${formatDateTime(option.valid_until)}` : ""}</p>
            <p className="text-[14px] text-muted-foreground">{option.eligible ? `Dự kiến giảm ${formatVND(option.estimated_discount.amount_minor)}` : voucherReason(option.reason)}</p>
            <Button variant="outline" className="w-full sm:w-auto" disabled={disabled || busy || options.isFetching || !option.eligible || option.is_applied} onClick={() => {
              acquire.reset();
              if (option.required_action === "NONE") onApply(option.promotion_id);
              else acquire.mutate(option);
            }}>{option.is_applied ? "Đã áp dụng" : option.required_action === "SAVE" ? "Lưu và áp dụng" : option.required_action === "CLAIM" ? "Nhận và áp dụng" : "Áp dụng voucher"}</Button>
          </li>)}</ul> : null}
        {options.data && !options.isError && options.data.items.length > 0 && <Button variant="ghost" disabled={disabled || busy || options.isFetching} onClick={() => void options.refetch()}>Tải lại voucher</Button>}
      </>}
    </CardContent>
  </Card>;
}
