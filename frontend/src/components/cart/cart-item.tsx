"use client";

import { useState } from "react";
import { Trash2, Minus, Plus, AlertTriangle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { formatVND, calcDiscount } from "@/lib/format";
import { type CartItem } from "@/store/useCartStore";
import { usePriceChanges } from "@/hooks/use-price-change";

// ─── Types ──────────────────────────────────────────────────────

interface CartItemCardProps {
  item: CartItem;
  onUpdateQuantity: (skuId: string, quantity: number) => void;
  onRemove: (skuId: string) => void;
  isUpdating?: boolean;
}

// ─── Loading Skeleton ───────────────────────────────────────────

export function CartItemSkeleton() {
  return (
    <div className="flex gap-4 p-4 rounded-2xl border border-white/10 bg-[#0c0c0f]">
      <Skeleton className="h-20 w-20 rounded-xl flex-shrink-0 bg-white/5" />
      <div className="flex-1 space-y-2">
        <Skeleton className="h-4 w-3/4 bg-white/5" />
        <Skeleton className="h-3 w-1/3 bg-white/5" />
        <div className="flex items-center justify-between pt-2">
          <Skeleton className="h-4 w-24 bg-white/5" />
          <Skeleton className="h-8 w-24 rounded-lg bg-white/5" />
        </div>
      </div>
    </div>
  );
}

// ─── Component ──────────────────────────────────────────────────

export function CartItemCard({
  item,
  onUpdateQuantity,
  onRemove,
  isUpdating,
}: CartItemCardProps) {
  const [isConfirmingDelete, setIsConfirmingDelete] = useState(false);
  const discount = calcDiscount(item.unit_price_at_add, item.current_price);
  const hasPriceChanged = item.current_price !== item.unit_price_at_add;

  // Check price change direction
  const { changedItems } = usePriceChanges();
  const priceChange = changedItems.find((c) => c.skuId === item.sku_id);

  const handleQuantityChange = (delta: number) => {
    const newQty = item.quantity + delta;
    if (newQty < 1 || newQty > 100) return;
    onUpdateQuantity(item.sku_id, newQty);
  };

  const handleRemove = () => {
    if (isConfirmingDelete) {
      onRemove(item.sku_id);
      setIsConfirmingDelete(false);
    } else {
      setIsConfirmingDelete(true);
      // Auto-reset confirmation after 3 seconds
      setTimeout(() => setIsConfirmingDelete(false), 3000);
    }
  };

  return (
    <div
      className={`group relative flex gap-4 p-4 rounded-2xl border transition-all duration-300 shadow-[0_0_0_1px_rgba(255,255,255,0.04)_inset] ${
        !item.is_available
          ? "border-destructive/20 bg-destructive/5 opacity-70"
          : hasPriceChanged
          ? "border-amber-500/30 bg-amber-500/10"
          : "border-white/10 hover:border-white/30 hover:shadow-[0_4px_20px_-10px_rgba(40,200,64,0.1)] bg-[#0c0c0f]"
      }`}
    >
      {/* Unavailable overlay badge */}
      {!item.is_available && (
        <div className="absolute top-3 right-3 z-10">
          <Badge variant="destructive" className="font-bold">
            <AlertTriangle className="h-3 w-3 mr-1" />
            Ngưng bán
          </Badge>
        </div>
      )}

      {/* Image */}
      <div className="h-20 w-20 rounded-xl bg-white/5 overflow-hidden flex-shrink-0 border border-white/5 relative">
        <div className="absolute inset-0 bg-[radial-gradient(circle_at_center,_var(--tw-gradient-stops))] from-white/10 to-transparent" />
        {item.image_url ? (
          <img
            src={item.image_url}
            alt={item.product_name}
            className="w-full h-full object-cover relative z-10 mix-blend-screen"
            loading="lazy"
          />
        ) : (
          <div className="w-full h-full flex items-center justify-center text-white/20 font-black text-2xl relative z-10 font-mono">
            {item.product_name.charAt(0)}
          </div>
        )}
      </div>

      {/* Content */}
      <div className="flex-1 min-w-0">
        {/* Product Name & Variant */}
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0">
            <h3
              className={`font-bold text-sm leading-tight line-clamp-2 ${
                !item.is_available
                  ? "text-white/30"
                  : "text-white/90"
              }`}
            >
              {item.product_name}
            </h3>
            {item.variant_info && (
              <p className="text-xs text-white/50 mt-1 font-mono uppercase tracking-wide">
                {item.variant_info}
              </p>
            )}
          </div>

          {/* Delete button */}
          <Button
            variant="ghost"
            size="icon-xs"
            className={`flex-shrink-0 rounded-full ${
              isConfirmingDelete
                ? "bg-destructive/10 text-destructive hover:bg-destructive/20"
                : "opacity-0 group-hover:opacity-100 text-white/30 hover:text-destructive hover:bg-destructive/10"
            }`}
            onClick={handleRemove}
            title="Xóa sản phẩm"
          >
            <Trash2 className="h-3.5 w-3.5" />
          </Button>
        </div>

        {/* Price */}
        <div className="flex items-baseline gap-2 mt-2">
          <span className="text-base font-black text-forest-bright tabular-nums font-mono">
            {formatVND(item.current_price)}
            {isUpdating && (
              <span className="ml-1 inline-block h-3 w-3 animate-spin rounded-full border-2 border-forest-bright border-t-transparent" />
            )}
          </span>
          {hasPriceChanged && (
            <span className="text-xs text-white/40 line-through font-mono">
              {formatVND(item.unit_price_at_add)}
            </span>
          )}
          {priceChange?.change === "decreased" && discount > 0 && (
            <Badge
              variant="outline"
              className="text-[10px] border-forest text-forest-bright bg-forest/10 font-bold uppercase tracking-widest font-mono"
            >
              -{discount}%
            </Badge>
          )}
          {priceChange?.change === "increased" && (
            <Badge
              variant="outline"
              className="text-[10px] border-amber-500/50 text-amber-400 bg-amber-500/10 font-bold uppercase tracking-widest font-mono"
            >
              Đã tăng
            </Badge>
          )}
        </div>

        {/* Quantity controls */}
        <div className="flex items-center justify-between mt-4">
          {item.is_available ? (
            <div className="flex items-center gap-0 border border-white/20 rounded-xl overflow-hidden bg-black/50">
              <button
                className="h-8 w-8 flex items-center justify-center text-white/70 hover:bg-white/10 hover:text-white transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
                onClick={() => handleQuantityChange(-1)}
                disabled={item.quantity <= 1 || isUpdating}
                aria-label="Giảm số lượng"
              >
                <Minus className="h-3 w-3" />
              </button>
              <span className="h-8 min-w-[2.5rem] flex items-center justify-center text-sm font-bold tabular-nums border-x border-white/20 text-white font-mono bg-white/5">
                {item.quantity}
              </span>
              <button
                className="h-8 w-8 flex items-center justify-center text-white/70 hover:bg-white/10 hover:text-white transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
                onClick={() => handleQuantityChange(1)}
                disabled={item.quantity >= 100 || isUpdating}
                aria-label="Tăng số lượng"
              >
                <Plus className="h-3 w-3" />
              </button>
            </div>
          ) : (
            <Button
              variant="destructive"
              size="xs"
              onClick={handleRemove}
            >
              {isConfirmingDelete ? "Xác nhận xóa?" : "Xóa khỏi giỏ"}
            </Button>
          )}

          {/* Subtotal */}
          <div className="text-right">
            <p className="text-sm font-black text-white/90 tabular-nums font-mono">
              {formatVND(item.current_price * item.quantity)}
            </p>
            {item.quantity > 1 && (
              <p className="text-[10px] text-white/40 font-mono">
                {formatVND(item.current_price)} / cái
              </p>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
