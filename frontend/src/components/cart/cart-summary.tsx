import Link from "next/link";
import { ShoppingBag } from "lucide-react";
import { Button } from "@/components/ui/button";
import type { Cart } from "@/lib/api/cart";
import { cartSubtotal, formatCartMoney } from "@/lib/cart";

export function CartSummary({ cart, stale, locked = false }: { cart: Cart; stale: boolean; locked?: boolean }) {
  const subtotal = stale ? null : cartSubtotal(cart);
  const unavailable = cart.catalog_freshness === "CURRENT" ? cart.items.filter((item) => !item.is_available).length : 0;
  return <aside className="space-y-6 rounded-xl border border-border bg-surface-overlay p-6 lg:sticky lg:top-36 lg:self-start" aria-label="Tóm tắt giỏ hàng">
    <h2 className="font-serif text-headline-5 font-normal">Tóm tắt giỏ hàng</h2>
    <dl className="space-y-4 text-[15px]">
      <div className="flex justify-between gap-4"><dt>Biến thể trong giỏ</dt><dd className="tabular-nums">{cart.total_unique_skus}</dd></div>
      <div className="flex justify-between gap-4"><dt>Tổng số lượng</dt><dd className="tabular-nums">{cart.total_units}</dd></div>
      <div className="flex flex-wrap justify-between gap-3 border-t border-border pt-4"><dt>Tạm tính</dt><dd className="font-medium tabular-nums">{subtotal ? formatCartMoney(subtotal) : "Chưa tính được"}</dd></div>
    </dl>
    {unavailable > 0 && <p className="text-caption text-muted-foreground">{unavailable} biến thể ngưng bán không được tính vào tạm tính.</p>}
    {!subtotal && <p className="text-caption text-muted-foreground">Cần xác minh đầy đủ giá và tình trạng bán trước khi tính tạm tính.</p>}
    <p className="text-caption text-muted-foreground">Giá và tình trạng tồn kho sẽ được kiểm tra lại tại bước đặt hàng. Phiên bản hiện tại không tính phí vận chuyển.</p>
    <div className="space-y-3">
      {locked || stale ? <Button variant="clay" size="lg" className="w-full" disabled aria-describedby="checkout-availability"><ShoppingBag aria-hidden />Thanh toán</Button> : <Button variant="clay" size="lg" className="w-full" nativeButton={false} render={<Link href="/checkout" />}><ShoppingBag aria-hidden />Thanh toán</Button>}
      {(locked || stale) && <p id="checkout-availability" className="text-caption text-muted-foreground">Hoàn tất cập nhật và tải lại giỏ hàng trước khi đặt hàng.</p>}
    </div>
  </aside>;
}
