"use client";

import { FormEvent, useState } from "react";
import { usePathname, useRouter } from "next/navigation";
import { ClipboardList, LoaderCircle, Search } from "lucide-react";

import { OrderCard, OrderCardSkeleton } from "@/components/orders/order-card";
import { Button, buttonVariants } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { ErrorState } from "@/components/ui/error-state";
import { Input } from "@/components/ui/input";
import { Alert } from "@/components/ui/alert";
import { NativeSelect } from "@/components/ui/native-select";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useMyOrders } from "@/hooks/use-orders";
import type { BuyerTab, OrderStatus, PaymentStatus } from "@/lib/api/order";
import { cn } from "@/lib/utils";
import Link from "next/link";
import { ALL_ORDER_HISTORY_FROM, buyerOrderQuery, orderDateTimeInput, orderDateTimeIso, parseBuyerOrderSearch, type BuyerOrderFilters, type OrderSearchValues } from "@/lib/buyer-order-filters";
import { ORDER_STATUS_LABELS, PAYMENT_STATUS_LABELS } from "@/lib/labels/order";

const TABS: Array<{ value: "ALL" | BuyerTab; label: string }> = [
  { value: "ALL", label: "Tất cả" },
  { value: "TO_PAY", label: "Chờ thanh toán" },
  { value: "TO_SHIP", label: "Chờ giao" },
  { value: "TO_RECEIVE", label: "Đang giao" },
  { value: "COMPLETED", label: "Hoàn thành" },
  { value: "CANCELLED", label: "Đã huỷ" },
  { value: "RETURN_REFUND", label: "Trả hàng & hoàn tiền" },
];

export function OrdersPageContent({ initialTab, initialQuery, initialFilters = {}, allHistory = false, filterErrors = [] }: { initialTab?: BuyerTab; initialQuery: string; initialFilters?: BuyerOrderFilters; allHistory?: boolean; filterErrors?: string[] }) {
  const router = useRouter();
  const pathname = usePathname();
  const [searchText, setSearchText] = useState(initialQuery);
  const [period, setPeriod] = useState(allHistory ? "all" : initialFilters.created_from || initialFilters.created_to ? "custom" : "recent");
  const [formErrors, setFormErrors] = useState<string[]>([]);
  const orders = useMyOrders(initialTab, initialQuery, initialFilters, filterErrors.length === 0);
  const items = orders.data?.pages.flatMap((page) => page.items) ?? [];
  const total = orders.data?.pages[0]?.total_orders ?? 0;

  function navigate(tab: BuyerTab | undefined, query: string, filters = initialFilters, history = allHistory) {
    const params = buyerOrderQuery(tab, query, filters, history);
    router.replace(`${pathname}${params.size ? `?${params}` : ""}`, { scroll: false });
  }

  function submitSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const raw: OrderSearchValues = { q: searchText, tab: initialTab };
    for (const name of ["order_status", "payment_status"]) {
      const values = data.getAll(name).map(String).filter(Boolean);
      if (values.length) raw[name] = values;
    }
    for (const name of ["min_amount_minor", "max_amount_minor"]) raw[name] = String(data.get(name) ?? "");
    if (period === "all") raw.history = "all";
    if (period === "custom") {
      const from = String(data.get("created_from") ?? ""), to = String(data.get("created_to") ?? "");
      if (!from && !to) { setFormErrors(["Hãy chọn ít nhất một mốc thời gian."]); return; }
      raw.created_from = from ? orderDateTimeIso(from) ?? from : undefined;
      raw.created_to = to ? orderDateTimeIso(to) ?? to : undefined;
    }
    const parsed = parseBuyerOrderSearch(raw);
    setFormErrors(parsed.errors);
    if (parsed.errors.length === 0) navigate(parsed.tab, parsed.q, parsed.filters, parsed.allHistory);
  }

  return (
    <main className="mx-auto w-full max-w-small space-y-8 px-6 py-10 md:px-8 md:py-14">
      <header className="space-y-2">
        <h1 className="font-serif text-headline-2 font-normal">Đơn hàng của tôi</h1>
        <p className="max-w-prose-narrow text-[15px] text-muted-foreground">
          Theo dõi giao hàng, thanh toán, trả hàng và hoàn tiền tại một nơi.
        </p>
      </header>

      <Tabs value={initialTab ?? "ALL"} onValueChange={(value) => navigate(value === "ALL" ? undefined : value as BuyerTab, initialQuery)}>
        <TabsList aria-label="Lọc đơn hàng theo trạng thái">
          {TABS.map((tab) => <TabsTrigger key={tab.value} value={tab.value}>{tab.label}</TabsTrigger>)}
        </TabsList>
      </Tabs>

      <form onSubmit={submitSearch} noValidate className="space-y-5 rounded-xl bg-surface-2 p-4" aria-label="Tìm và lọc đơn hàng">
        <div className="flex flex-col gap-3 sm:flex-row">
        <label htmlFor="order-search" className="sr-only">Tìm theo tên sản phẩm</label>
        <div className="relative flex-1">
          <Search className="pointer-events-none absolute left-3.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden />
          <Input
            id="order-search"
            value={searchText}
            onChange={(event) => setSearchText(event.target.value)}
            placeholder="Tìm theo tên sản phẩm"
            maxLength={200}
            className="pl-10"
          />
        </div>
        <Button type="submit" variant="outline">Tìm đơn hàng</Button>
        {initialQuery ? <Button type="button" variant="ghost" onClick={() => { setSearchText(""); navigate(initialTab, ""); }}>Xoá tìm kiếm</Button> : null}
        </div>
        <div className="grid gap-4 sm:grid-cols-2">
          <div className="space-y-2"><label htmlFor="order-period">Thời gian đặt hàng</label><NativeSelect id="order-period" value={period} onChange={event => setPeriod(event.target.value)}><option value="recent">90 ngày gần nhất</option><option value="all">Toàn bộ lịch sử</option><option value="custom">Chọn khoảng thời gian</option></NativeSelect></div>
          {period === "custom" && <div className="grid gap-3 sm:grid-cols-2"><div className="space-y-2"><label htmlFor="order-from">Từ ngày (giờ Việt Nam)</label><Input id="order-from" name="created_from" type="datetime-local" defaultValue={orderDateTimeInput(initialFilters.created_from)} /></div><div className="space-y-2"><label htmlFor="order-to">Đến ngày (giờ Việt Nam)</label><Input id="order-to" name="created_to" type="datetime-local" defaultValue={orderDateTimeInput(initialFilters.created_to)} /></div></div>}
        </div>
        <details open={Boolean(initialFilters.order_status?.length || initialFilters.payment_status?.length || initialFilters.min_amount_minor !== undefined || initialFilters.max_amount_minor !== undefined) || undefined}>
          <summary className="focus-ring min-h-11 cursor-pointer rounded-lg py-3 font-medium">Bộ lọc nâng cao</summary>
          <div className="grid gap-5 pt-3 sm:grid-cols-2">
            <fieldset className="space-y-2"><legend className="mb-2 font-medium">Trạng thái đơn hàng</legend>{Object.entries(ORDER_STATUS_LABELS).map(([value, label]) => <label key={value} className="flex min-h-11 items-center gap-2 text-[14px]"><input type="checkbox" name="order_status" value={value} defaultChecked={initialFilters.order_status?.includes(value as OrderStatus)} className="focus-ring size-4 accent-clay" />{label.text}</label>)}</fieldset>
            <fieldset className="space-y-2"><legend className="mb-2 font-medium">Trạng thái thanh toán</legend>{Object.entries(PAYMENT_STATUS_LABELS).map(([value, label]) => <label key={value} className="flex min-h-11 items-center gap-2 text-[14px]"><input type="checkbox" name="payment_status" value={value} defaultChecked={initialFilters.payment_status?.includes(value as PaymentStatus)} className="focus-ring size-4 accent-clay" />{label.text}</label>)}</fieldset>
            <div className="space-y-2"><label htmlFor="order-min">Thành tiền tối thiểu (₫)</label><Input id="order-min" name="min_amount_minor" type="number" min={0} step={1} max={Number.MAX_SAFE_INTEGER} defaultValue={initialFilters.min_amount_minor} /></div>
            <div className="space-y-2"><label htmlFor="order-max">Thành tiền tối đa (₫)</label><Input id="order-max" name="max_amount_minor" type="number" min={0} step={1} max={Number.MAX_SAFE_INTEGER} defaultValue={initialFilters.max_amount_minor} /></div>
          </div>
        </details>
        {[...filterErrors, ...formErrors].length > 0 && <Alert variant="danger"><ul>{[...new Set([...filterErrors, ...formErrors])].map(error => <li key={error}>{error}</li>)}</ul></Alert>}
        <div className="flex flex-wrap gap-3"><Button type="submit" variant="outline">Áp dụng bộ lọc</Button><Link href="/orders?history=all" className="focus-ring inline-flex min-h-11 items-center rounded-lg px-3 text-[14px] underline">Xóa bộ lọc, xem toàn bộ lịch sử</Link></div>
      </form>

      {orders.isError ? <ErrorState error={orders.error} onRetry={() => void orders.refetch()} /> : null}

      {filterErrors.length ? null : orders.isPending ? (
        <div className="space-y-5" aria-busy="true" aria-label="Đang tải đơn hàng">
          {[0, 1, 2].map((item) => <OrderCardSkeleton key={item} />)}
        </div>
      ) : orders.isError && !orders.data ? null : items.length === 0 ? (
        <EmptyState
          icon={ClipboardList}
          title={initialQuery || initialTab || Object.keys(initialFilters).length ? "Không tìm thấy đơn hàng phù hợp" : "Chưa có đơn hàng trong 90 ngày gần nhất"}
          description="Hãy đổi bộ lọc hoặc xem toàn bộ lịch sử để tìm đơn hàng cũ."
          action={initialQuery || initialTab || !allHistory
            ? <Button variant="outline" onClick={() => { setSearchText(""); navigate(undefined, "", { created_from: ALL_ORDER_HISTORY_FROM }, true); }}>Xem tất cả đơn</Button>
            : <Link href="/search" className={cn(buttonVariants({ variant: "outline", size: "sm" }))}>Tiếp tục mua sắm</Link>}
        />
      ) : (
        <section className="space-y-5" aria-label={`${total} đơn hàng`}>
          <p className="text-caption text-muted-foreground">{total} đơn hàng</p>
          {items.map((order) => <OrderCard key={order.order_id} order={order} />)}
          {orders.hasNextPage ? (
            <div className="flex justify-center pt-3">
              <Button variant="outline" disabled={orders.isFetchingNextPage} onClick={() => void orders.fetchNextPage()}>
                {orders.isFetchingNextPage ? <LoaderCircle className="animate-spin" aria-hidden /> : null}
                {orders.isFetchingNextPage ? "Đang tải thêm…" : "Tải thêm đơn hàng"}
              </Button>
            </div>
          ) : null}
        </section>
      )}
    </main>
  );
}
