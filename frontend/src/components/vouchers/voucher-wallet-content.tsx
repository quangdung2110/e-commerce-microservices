"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { Ticket, RotateCw } from "lucide-react";
import { useVoucherWallet } from "@/hooks/use-voucher-wallet";
import type { WalletEntry, WalletFilter } from "@/lib/api/promotion";
import { describeDiscount } from "@/lib/promotion/discount";
import { formatDateTime, formatVND } from "@/lib/format";
import { Alert } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button, buttonVariants } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { ErrorState } from "@/components/ui/error-state";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";

const FILTERS = [
  { value: "ALL", label: "Tất cả" }, { value: "SAVED", label: "Chưa dùng" },
  { value: "USED", label: "Đã dùng" }, { value: "EXPIRED", label: "Hết hạn" },
] as const;

function WalletCard({ entry }: { entry: WalletEntry }) {
  const { wallet, effective_status: status } = entry;
  return <li className="min-w-0"><Card className="h-full"><CardContent className="space-y-4 pt-6">
    <div className="flex flex-wrap items-center justify-between gap-3">
      <Ticket className="size-5 text-clay-strong" aria-hidden />
      <Badge variant={status === "SAVED" ? "success" : status === "EXPIRED" ? "warning" : "default"}>{status === "SAVED" ? "Chưa dùng" : status === "USED" ? "Đã dùng" : "Hết hạn"}</Badge>
    </div>
    <div className="space-y-2">
      <h2 className="break-words font-serif text-headline-5 font-normal">{describeDiscount(entry.discount, formatVND)}</h2>
      <p className="break-all text-caption text-muted-foreground">{entry.code}</p>
    </div>
    <p className="text-[14px]">Đơn tối thiểu {formatVND(entry.min_order_amount.amount_minor)}</p>
    <p className="text-caption text-muted-foreground">Hạn dùng <time dateTime={wallet.expired_at}>{formatDateTime(wallet.expired_at)}</time></p>
    {!entry.is_enabled && status === "SAVED" && <Alert variant="warning">Voucher đang tạm ngừng áp dụng.</Alert>}
    {status === "USED" && <div className="space-y-2 text-[14px]">
      {wallet.used_at && <p>Đã dùng lúc {formatDateTime(wallet.used_at)}</p>}
      {wallet.discount_amount && <p>Đã giảm {formatVND(wallet.discount_amount.amount_minor)}</p>}
      {wallet.used_order_id && <Link className="focus-ring inline-flex min-h-11 items-center rounded-lg text-clay-strong underline" href={`/orders/${encodeURIComponent(wallet.used_order_id)}`}>Xem đơn #{wallet.used_order_id}</Link>}
    </div>}
    <details>
      <summary className="focus-ring min-h-11 cursor-pointer rounded-lg py-3 text-[14px] font-medium">Điều kiện và thông tin voucher</summary>
      <div className="space-y-3 pt-2 text-caption text-muted-foreground">
        <p className="break-words">{entry.conditions_summary}</p>
        <p>Lưu vào ví lúc {formatDateTime(wallet.saved_at)}</p>
        <p>Điều kiện của đơn hàng và lượt sử dụng còn lại được kiểm tra khi thanh toán. Lưu voucher chưa giữ lượt sử dụng.</p>
      </div>
    </details>
  </CardContent></Card></li>;
}

export function VoucherWalletContent({ status, invalidFilter = false }: { status?: WalletFilter; invalidFilter?: boolean }) {
  const router = useRouter();
  // Invalid URL is handled before mounting the query-owning child.
  return <main className="mx-auto w-full max-w-medium space-y-7 px-6 py-10 md:px-8 md:py-14">
    <header className="flex flex-wrap items-start justify-between gap-4">
      <div className="space-y-3"><h1 className="font-serif text-headline-2 font-normal">Ví voucher</h1>
        <p className="max-w-prose text-[15px] text-muted-foreground">Xem voucher bạn đã lưu hoặc được tặng, cùng lịch sử sử dụng. Không cần có sản phẩm trong giỏ để xem ví.</p></div>
      <Link href="/cart" className={buttonVariants({ variant: "outline" })}>Đến giỏ hàng</Link>
    </header>
    <Tabs value={status ?? "ALL"} onValueChange={value => router.replace(value === "ALL" ? "/vouchers" : `/vouchers?status=${value}`, { scroll: false })}>
      <TabsList aria-label="Lọc ví voucher">{FILTERS.map(filter => <TabsTrigger key={filter.value} value={filter.value}>{filter.label}</TabsTrigger>)}</TabsList>
    </Tabs>
    {invalidFilter ? <Alert variant="danger">Bộ lọc ví voucher không hợp lệ. <Link href="/vouchers" className="underline">Xem toàn bộ ví</Link></Alert> : <WalletResults key={status ?? "ALL"} status={status} />}
  </main>;
}

function WalletResults({ status }: { status?: WalletFilter }) {
  const wallet = useVoucherWallet(status);
  const items = wallet.data?.pages.flatMap(page => page.items) ?? [];
  return <section className="space-y-5" aria-label="Danh sách voucher">
    <div className="flex flex-wrap items-center justify-between gap-3">
      <p className="text-caption text-muted-foreground">Chọn voucher trong bước thanh toán để kiểm tra mức giảm cho đơn của bạn.</p>
      <Button variant="ghost" disabled={wallet.isFetching} onClick={() => void wallet.refetch()}><RotateCw aria-hidden />Tải lại ví</Button>
    </div>
    {wallet.isError && <ErrorState error={wallet.error} onRetry={() => void wallet.refetch()} />}
    {wallet.isPending ? <div aria-busy="true" aria-label="Đang tải ví voucher" className="grid gap-5 sm:grid-cols-2">{[0, 1].map(id => <Skeleton key={id} className="h-64 rounded-xl" />)}</div>
      : !wallet.isError && items.length === 0 ? <EmptyState icon={Ticket} title={status ? "Chưa có voucher ở mục này" : "Ví voucher của bạn đang trống"} description="Voucher đã lưu hoặc được tặng sẽ xuất hiện ở đây. Bạn có thể lưu voucher trong bước thanh toán." action={<Link href="/search" className={buttonVariants({ variant: "outline" })}>Khám phá sản phẩm</Link>} />
      : items.length > 0 ? <><ul className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">{items.map(entry => <WalletCard key={entry.wallet.wallet_id} entry={entry} />)}</ul>
        {wallet.hasNextPage && <Button variant="outline" disabled={wallet.isFetching} onClick={() => void wallet.fetchNextPage()}>{wallet.isFetchingNextPage ? "Đang tải thêm…" : "Tải thêm voucher"}</Button>}
      </> : null}
  </section>;
}
