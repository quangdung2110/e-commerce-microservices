"use client";

import { Minus, Plus } from "lucide-react";
import { cn } from "@/lib/utils";
import { usePDPStore } from "@/store/usePDPStore";

interface QuantitySelectorProps {
  className?: string;
}

export function QuantitySelector({ className }: QuantitySelectorProps) {
  const quantity = usePDPStore((s) => s.quantity);
  const setQuantity = usePDPStore((s) => s.setQuantity);
  const incrementQuantity = usePDPStore((s) => s.incrementQuantity);
  const decrementQuantity = usePDPStore((s) => s.decrementQuantity);
  const getMaxQuantity = usePDPStore((s) => s.getMaxQuantity);
  const selectedSku = usePDPStore((s) => s.getSelectedSku());

  const max = getMaxQuantity();
  const isOutOfStock = !selectedSku || selectedSku.display_quantity <= 0;

  if (isOutOfStock) return null;

  return (
    <div className={cn("space-y-2", className)}>
      <p className="text-xs font-bold text-white/50 uppercase tracking-widest font-mono">Số lượng</p>
      <div className="flex items-center gap-3">
        <div className="flex items-center gap-0 border border-white/20 rounded-xl overflow-hidden bg-[#0c0c0f]">
          <button
            className="h-10 w-10 flex items-center justify-center text-white/70 hover:text-white hover:bg-white/10 transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
            onClick={decrementQuantity}
            disabled={quantity <= 1}
            aria-label="Giảm số lượng"
          >
            <Minus className="h-4 w-4" />
          </button>
          <input
            type="text"
            value={quantity}
            onChange={(e) => {
              const val = parseInt(e.target.value, 10);
              if (!isNaN(val)) setQuantity(val);
            }}
            className="h-10 w-16 text-center text-sm font-bold tabular-nums text-white bg-white/5 border-x border-white/20 outline-none font-mono"
            aria-label="Số lượng"
          />
          <button
            className="h-10 w-10 flex items-center justify-center text-white/70 hover:text-white hover:bg-white/10 transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
            onClick={incrementQuantity}
            disabled={quantity >= max}
            aria-label="Tăng số lượng"
          >
            <Plus className="h-4 w-4" />
          </button>
        </div>

        {/* Stock info */}
        <span className="text-xs text-white/50 font-mono tracking-wide">
          {max > 0 ? (
            <>
              Còn{" "}
              <span className="font-bold text-forest-bright">
                {selectedSku?.display_quantity}
              </span>{" "}
              sản phẩm
            </>
          ) : (
            <span className="text-destructive font-bold uppercase tracking-widest">Hết hàng</span>
          )}
        </span>
      </div>
    </div>
  );
}
