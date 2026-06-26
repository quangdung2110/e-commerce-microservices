"use client";

import { useState } from "react";
import { AlertTriangle, TrendingDown, TrendingUp, X } from "lucide-react";
import { usePriceChanges } from "@/hooks/use-price-change";
import { formatVND } from "@/lib/format";

// ─── Component ──────────────────────────────────────────────────

/**
 * Banner that shows price change notifications when user F5/refreshes the cart page.
 * Detects when current_price differs from unit_price_at_add.
 */
export function PriceChangeBanner() {
  const { hasPriceChanges, decreasedItems, increasedItems, totalChanged } =
    usePriceChanges();
  const [dismissed, setDismissed] = useState(false);

  if (!hasPriceChanges || dismissed) return null;

  // Build a summary message
  const parts: string[] = [];
  if (decreasedItems.length > 0) {
    const names = decreasedItems.map((i) => i.productName).slice(0, 2);
    const suffix =
      decreasedItems.length > 2
        ? ` và ${decreasedItems.length - 2} sản phẩm khác`
        : "";
    parts.push(`"${names.join('", "')}"${suffix} đã giảm giá`);
  }
  if (increasedItems.length > 0) {
    const names = increasedItems.map((i) => i.productName).slice(0, 2);
    const suffix =
      increasedItems.length > 2
        ? ` và ${increasedItems.length - 2} sản phẩm khác`
        : "";
    parts.push(`"${names.join('", "')}"${suffix} đã tăng giá`);
  }

  const message = parts.join("; ");

  return (
    <div className="rounded-2xl border border-amber-500/20 bg-amber-500/10 p-4 relative shadow-[0_0_0_1px_rgba(245,158,11,0.05)_inset]">
      {/* Close button */}
      <button
        className="absolute top-3 right-3 text-white/50 hover:text-white transition-colors"
        onClick={() => setDismissed(true)}
        aria-label="Đóng thông báo"
      >
        <X className="h-4 w-4" />
      </button>

      <div className="flex items-start gap-3 pr-8">
        <div className="h-8 w-8 rounded-full bg-amber-500/20 flex items-center justify-center flex-shrink-0">
          <AlertTriangle className="h-4 w-4 text-amber-500" />
        </div>
        <div className="space-y-2 min-w-0">
          <p className="text-sm font-bold text-amber-400 font-mono uppercase tracking-widest">
            Giá sản phẩm đã thay đổi
          </p>
          <p className="text-sm text-amber-500/80 font-mono">
            {message}. Giá hiện tại đã được cập nhật trong giỏ hàng.
          </p>

          {/* Per-item breakdown */}
          {decreasedItems.length > 0 && (
            <div className="flex flex-wrap gap-2 pt-1">
              {decreasedItems.map((item) => (
                <div
                  key={item.skuId}
                  className="inline-flex items-center gap-1.5 text-[10px] uppercase font-bold tracking-widest font-mono bg-forest/20 border border-forest/30 text-forest-bright px-2.5 py-1 rounded-full"
                >
                  <TrendingDown className="h-3 w-3" />
                  <span className="truncate max-w-[120px]">
                    {item.productName}
                  </span>
                  <span>
                    -{item.percentage}% ({formatVND(item.oldPrice)} →{" "}
                    {formatVND(item.newPrice)})
                  </span>
                </div>
              ))}
            </div>
          )}

          {increasedItems.length > 0 && (
            <div className="flex flex-wrap gap-2 pt-1">
              {increasedItems.map((item) => (
                <div
                  key={item.skuId}
                  className="inline-flex items-center gap-1.5 text-[10px] uppercase font-bold tracking-widest font-mono bg-amber-500/20 border border-amber-500/30 text-amber-400 px-2.5 py-1 rounded-full"
                >
                  <TrendingUp className="h-3 w-3" />
                  <span className="truncate max-w-[120px]">
                    {item.productName}
                  </span>
                  <span>
                    +{item.percentage}% ({formatVND(item.oldPrice)} →{" "}
                    {formatVND(item.newPrice)})
                  </span>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
