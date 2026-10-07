"use client";

import { FormEvent, useState } from "react";
import { usePathname, useRouter } from "next/navigation";
import { ClipboardList, LoaderCircle, Search } from "lucide-react";

import { OrderCard, OrderCardSkeleton } from "@/components/orders/order-card";
import { Button, buttonVariants } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { ErrorState } from "@/components/ui/error-state";
import { Input } from "@/components/ui/input";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useMyOrders } from "@/hooks/use-orders";
import type { BuyerTab } from "@/lib/api/order";
import { cn } from "@/lib/utils";
import Link from "next/link";

const TABS: Array<{ value: "ALL" | BuyerTab; label: string }> = [
  { value: "ALL", label: "Tất cả" },
  { value: "TO_PAY", label: "Chờ thanh toán" },
  { value: "TO_SHIP", label: "Chờ giao" },
  { value: "TO_RECEIVE", label: "Đang giao" },
  { value: "COMPLETED", label: "Hoàn thành" },
  { value: "CANCELLED", label: "Đã huỷ" },
  { value: "RETURN_REFUND", label: "Trả hàng & hoàn tiền" },
];

export function OrdersPageContent({ initialTab, initialQuery }: { initialTab?: BuyerTab; initialQuery: string }) {
  const router = useRouter();
  const pathname = usePathname();
  const [searchText, setSearchText] = useState(initialQuery);
  const orders = useMyOrders(initialTab, initialQuery);
  const items = orders.data?.pages.flatMap((page) => page.items) ?? [];
  const total = orders.data?.pages[0]?.total_orders ?? 0;

  function navigate(tab: BuyerTab | undefined, query: string) {
    const params = new URLSearchParams();
    if (tab) params.set("tab", tab);
    if (query.trim()) params.set("q", query.trim());
    router.replace(`${pathname}${params.size ? `?${params}` : ""}`, { scroll: false });
  }

  function submitSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    navigate(initialTab, searchText);
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

      <form onSubmit={submitSearch} className="flex flex-col gap-3 rounded-xl bg-surface-2 p-4 sm:flex-row">
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
      </form>

      {orders.isError ? <ErrorState error={orders.error} onRetry={() => void orders.refetch()} /> : null}

      {orders.isPending ? (
        <div className="space-y-5" aria-busy="true" aria-label="Đang tải đơn hàng">
          {[0, 1, 2].map((item) => <OrderCardSkeleton key={item} />)}
        </div>
      ) : orders.isError && !orders.data ? null : items.length === 0 ? (
        <EmptyState
          icon={ClipboardList}
          title={initialQuery || initialTab ? "Không tìm thấy đơn hàng phù hợp" : "Bạn chưa có đơn hàng nào"}
          description={initialQuery || initialTab ? "Hãy đổi từ khoá hoặc chọn trạng thái khác." : "Sau khi đặt hàng, trạng thái đơn sẽ xuất hiện tại đây."}
          action={initialQuery || initialTab
            ? <Button variant="outline" onClick={() => { setSearchText(""); navigate(undefined, ""); }}>Xem tất cả đơn</Button>
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
