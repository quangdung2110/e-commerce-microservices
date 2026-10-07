"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { LoaderCircle, RotateCw, Trash2 } from "lucide-react";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { ErrorState } from "@/components/ui/error-state";
import { Skeleton } from "@/components/ui/skeleton";
import { useToast } from "@/components/ui/toast";
import { useCart, useCartCommand, type CartCommand } from "@/hooks/use-cart";
import { isRetryable, retryAfterSeconds } from "@/lib/api/problem";
import { useAuthStore } from "@/store/useAuthStore";
import { CartItemCard, CartItemSkeleton } from "./cart-item";
import { CartSummary } from "./cart-summary";
import { PriceChangeBanner } from "./price-change-banner";
import { EmptyCart } from "./empty-cart";

function CommandError({ error, retry, unresolved = false }: { error: unknown; retry?: () => void; unresolved?: boolean }) {
  const [deadline] = useState(() => Date.now() + (retryAfterSeconds(error) ?? 0) * 1000);
  const [now, setNow] = useState(() => Date.now());
  const remaining = Math.max(0, Math.ceil((deadline - now) / 1000));
  useEffect(() => {
    if (!remaining) return;
    const timer = window.setInterval(() => setNow(Date.now()), 250);
    return () => window.clearInterval(timer);
  }, [remaining]);
  return <div className="space-y-3">
    <ErrorState error={error} />
    {retry && <>
      {unresolved && <p className="text-caption text-muted-foreground">Kết quả thao tác trước chưa được xác nhận. Thử lại cùng thao tác để tránh áp dụng hai lần.</p>}
      <Button variant="outline" disabled={remaining > 0} onClick={retry}>{remaining > 0 ? `Thử lại sau ${remaining} giây` : unresolved ? "Thử lại thao tác" : "Thử tải lại"}</Button>
    </>}
  </div>;
}

export function CartLoading() {
  return <div className="grid gap-8 lg:grid-cols-3" aria-busy="true" aria-label="Đang tải giỏ hàng">
    <div className="space-y-4 lg:col-span-2">{[0, 1, 2].map((index) => <CartItemSkeleton key={index} />)}</div>
    <div className="space-y-6 rounded-xl border border-border bg-surface-overlay p-6"><Skeleton className="h-7 w-40" /><Skeleton className="h-5 w-full" /><Skeleton className="h-5 w-3/4" /><Skeleton className="h-12 w-full" /></div>
  </div>;
}

export function CartPageContent() {
  const accountId = useAuthStore((state) => state.claims?.sub);
  return <div className="mx-auto w-full max-w-main space-y-8 px-6 py-10 md:px-8 md:py-14">
    <header className="space-y-3">
      <Link href="/search?in_stock=false" className="focus-ring inline-flex min-h-11 items-center text-[14px] text-muted-foreground hover:text-foreground">Tiếp tục mua sắm</Link>
      <h1 className="font-serif text-headline-2 font-normal">Giỏ hàng của bạn</h1>
      <p className="text-[15px] text-muted-foreground">Kiểm tra sản phẩm, số lượng và giá trước khi mua.</p>
    </header>
    <CartContents key={accountId} />
  </div>;
}

function CartContents() {
  const cart = useCart();
  const command = useCartCommand();
  const { addToast } = useToast();
  const [selected, setSelected] = useState<string[]>([]);
  const uncertain = command.isError && isRetryable(command.error);
  const locked = command.isPending || uncertain;
  const disabled = locked || cart.isFetching || cart.isError;
  const items = cart.data?.items ?? [];
  const selectedIds = selected.filter((id) => items.some((item) => item.sku_id === id));
  const allSelected = items.length > 0 && selectedIds.length === items.length;

  function send(intent: CartCommand) {
    command.mutate(intent, {
      onSuccess: () => {
        if (intent.kind === "remove") {
          setSelected((previous) => previous.filter((id) => !intent.body.sku_ids.includes(id)));
          addToast({ type: "success", title: "Đã xóa sản phẩm khỏi giỏ" });
        } else {
          addToast({ type: "success", title: "Đã cập nhật số lượng" });
        }
      },
    });
  }

  return <div className="space-y-6">
    {command.isError && <CommandError key={command.submittedAt} error={command.error} unresolved retry={uncertain && command.variables ? () => send(command.variables!) : undefined} />}
    {command.isPending && <p role="status" className="flex items-center gap-2 text-[14px] text-muted-foreground"><LoaderCircle className="size-4 animate-spin" aria-hidden />Đang cập nhật giỏ hàng…</p>}
    {cart.isError && <CommandError key={cart.errorUpdatedAt} error={cart.error} retry={() => { void cart.refetch(); }} />}
    {cart.isPending ? <CartLoading /> : !cart.data ? null : <>
      {cart.data.catalog_freshness === "DEGRADED" && <Alert variant="warning" title="Thông tin sản phẩm đang gián đoạn">
        <p>Giá hiện tại và tình trạng bán chưa được xác minh. Giá lúc thêm chỉ để tham khảo; bạn vẫn có thể giảm số lượng hoặc xóa sản phẩm.</p>
      </Alert>}
      {cart.isError && <Alert variant="warning">Đang hiển thị lần tải thành công trước. Tải lại giỏ hàng trước khi tiếp tục thay đổi.</Alert>}
      {!cart.isError && <PriceChangeBanner cart={cart.data} />}
      {items.length === 0 ? <EmptyCart /> : <div className="grid gap-8 lg:grid-cols-3">
        <section className="space-y-4 lg:col-span-2" aria-label="Sản phẩm trong giỏ">
          <h2 className="sr-only">Sản phẩm trong giỏ</h2>
          <div className="flex flex-wrap items-center justify-between gap-3">
            <label className="flex min-h-11 items-center gap-3 text-[14px]">
              <Checkbox checked={allSelected} indeterminate={selectedIds.length > 0 && !allSelected} disabled={disabled} onCheckedChange={(checked) => setSelected(checked ? items.map((item) => item.sku_id) : [])} />
              Chọn tất cả ({items.length} biến thể)
            </label>
            <Button variant="outline" disabled={disabled || selectedIds.length === 0} onClick={() => send({ kind: "remove", body: { sku_ids: selectedIds } })}><Trash2 aria-hidden />Xóa đã chọn ({selectedIds.length})</Button>
          </div>
          {items.map((item) => <CartItemCard key={item.sku_id} item={item} freshness={cart.data.catalog_freshness} disabled={disabled} selected={selectedIds.includes(item.sku_id)}
            onSelect={(checked) => setSelected((previous) => checked ? [...new Set([...previous, item.sku_id])] : previous.filter((id) => id !== item.sku_id))}
            onAdjust={(delta) => send({ kind: "adjust", body: { sku_id: item.sku_id, delta } })}
            onRemove={() => send({ kind: "remove", body: { sku_ids: [item.sku_id] } })} />)}
        </section>
        <CartSummary cart={cart.data} stale={cart.isError} locked={locked || cart.isFetching} />
      </div>}
    </>}
    {cart.data && !cart.isError && <Button variant="outline" disabled={command.isPending || cart.isFetching} onClick={() => { void cart.refetch(); }}><RotateCw aria-hidden />{cart.isFetching ? "Đang tải lại…" : "Tải lại giá và giỏ hàng"}</Button>}
  </div>;
}
