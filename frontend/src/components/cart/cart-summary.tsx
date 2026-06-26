"use client";

import { ShoppingBag, AlertTriangle, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import { formatVND } from "@/lib/format";
import { useCartStore } from "@/store/useCartStore";

// ─── Component ─────────────────────────────────────────────────

interface CartSummaryProps {
  isLoading?: boolean;
  onCheckout?: () => void;
}

export function CartSummary({ isLoading, onCheckout }: CartSummaryProps) {
  const items = useCartStore((s) => s.items);
  const isPriceDegraded = useCartStore((s) => s.isPriceDegraded);
  const getSubtotal = useCartStore((s) => s.getSubtotal);
  const getUnavailableCount = useCartStore((s) => s.getUnavailableCount);

  const subtotal = getSubtotal();
  const unavailableCount = getUnavailableCount();
  const availableItems = items.filter((i) => i.is_available).length;
  const canCheckout = availableItems > 0 && !isLoading;

  return (
    <div className="rounded-2xl border border-white/10 bg-[#0c0c0f] shadow-[0_0_0_1px_rgba(255,255,255,0.04)_inset] p-6 space-y-4 sticky top-28 group">
      <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_bottom_right,_var(--tw-gradient-stops))] from-forest/5 via-transparent to-transparent opacity-0 group-hover:opacity-100 transition-opacity duration-500" />
      <h3 className="font-bold text-lg text-white/90 uppercase tracking-widest font-mono relative z-10">Đơn hàng</h3>

      {/* Price degraded warning */}
      {isPriceDegraded && (
        <div className="flex items-start gap-2 text-xs text-amber-500 bg-amber-500/10 border border-amber-500/20 p-3 rounded-xl relative z-10">
          <AlertTriangle className="h-4 w-4 flex-shrink-0 mt-0.5" />
          <p className="font-mono tracking-tight">
            Hệ thống đang quá tải, giá sản phẩm có thể chưa được cập nhật mới
            nhất.
          </p>
        </div>
      )}

      {/* Summary rows */}
      <div className="space-y-3 text-sm relative z-10">
        <div className="flex items-center justify-between text-white/50 font-mono">
          <span>
            Tạm tính ({availableItems} SP)
          </span>
          <span className="font-bold text-white/90 tabular-nums">
            {formatVND(subtotal)}
          </span>
        </div>

        {unavailableCount > 0 && (
          <div className="flex items-center justify-between text-destructive text-xs font-mono">
            <span className="flex items-center gap-1">
              <AlertTriangle className="h-3 w-3" />
              {unavailableCount} sản phẩm ngưng bán
            </span>
            <span className="line-through">
              -{formatVND(
                items
                  .filter((i) => !i.is_available)
                  .reduce((s, i) => s + i.current_price * i.quantity, 0)
              )}
            </span>
          </div>
        )}

        <Separator className="my-3 bg-white/10" />

        <div className="flex items-center justify-between">
          <span className="font-bold text-white/70 font-mono uppercase tracking-wide text-xs">Phí vận chuyển</span>
          <span className="text-xs text-white/30 font-mono italic">
            Tính tại bước thanh toán
          </span>
        </div>
      </div>

      <Separator className="bg-white/10 relative z-10" />

      {/* Total */}
      <div className="flex items-center justify-between relative z-10">
        <span className="font-black text-lg text-white uppercase tracking-widest font-mono">Tổng cộng</span>
        <span className="font-black text-2xl text-forest-bright tabular-nums drop-shadow-[0_0_10px_rgba(40,200,64,0.3)]">
          {formatVND(subtotal)}
        </span>
      </div>

      {/* Checkout button */}
      <div className="relative z-10 pt-2">
        <Button
          variant="secondary"
          className="w-full h-14 rounded-2xl font-black text-base gap-2 uppercase tracking-widest font-mono shadow-[0_0_20px_rgba(40,200,64,0.3)] hover:shadow-[0_0_30px_rgba(40,200,64,0.6)] group/btn relative overflow-hidden"
          size="lg"
          disabled={!canCheckout}
          onClick={onCheckout}
        >
          <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_center,_var(--tw-gradient-stops))] from-white/20 to-transparent opacity-0 group-hover/btn:opacity-100 transition-opacity duration-300" />
          <div className="relative flex items-center gap-2 z-10">
            {isLoading ? (
              <>
                <Loader2 className="h-5 w-5 animate-spin" />
                Đang xử lý...
              </>
            ) : (
              <>
                <ShoppingBag className="h-5 w-5 group-hover/btn:scale-110 transition-transform duration-300" />
                Thanh toán
              </>
            )}
          </div>
        </Button>
      </div>

      {!canCheckout && !isLoading && (
        <p className="text-xs text-white/30 text-center font-mono relative z-10">
          {availableItems === 0
            ? "Không có sản phẩm khả dụng"
            : "Vui lòng kiểm tra lại giỏ hàng"}
        </p>
      )}
    </div>
  );
}
