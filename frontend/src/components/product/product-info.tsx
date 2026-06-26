"use client";

import { Star, ChevronDown } from "lucide-react";
import { Skeleton } from "@/components/ui/skeleton";
import { Badge } from "@/components/ui/badge";
import { Separator } from "@/components/ui/separator";
import { formatVND } from "@/lib/format";
import { VariantPicker } from "./variant-picker";
import { QuantitySelector } from "./quantity-selector";
import { AddToCartButton } from "./add-to-cart-button";
import { usePDPStore } from "@/store/usePDPStore";
import { useProductDetail } from "@/hooks/use-product-detail";

// ─── Loading Skeleton ───────────────────────────────────────────

function ProductInfoSkeleton() {
  return (
    <div className="space-y-6">
      <div className="space-y-3">
        <Skeleton className="h-8 w-full" />
        <Skeleton className="h-8 w-3/4" />
      </div>
      <div className="flex items-center gap-2">
        <Skeleton className="h-4 w-24" />
        <Skeleton className="h-4 w-16" />
      </div>
      <Skeleton className="h-10 w-40" />
      <Separator />
      <div className="space-y-3">
        <Skeleton className="h-4 w-20" />
        <div className="flex gap-2">
          <Skeleton className="h-10 w-20 rounded-xl" />
          <Skeleton className="h-10 w-20 rounded-xl" />
          <Skeleton className="h-10 w-20 rounded-xl" />
        </div>
      </div>
      <Separator />
      <Skeleton className="h-14 w-full rounded-xl" />
    </div>
  );
}

// ─── Component ──────────────────────────────────────────────────

interface ProductInfoProps {
  productId: string;
}

export function ProductInfo({ productId }: ProductInfoProps) {
  const { data: detail, isLoading } = useProductDetail(productId);

  const selectedSku = usePDPStore((s) => s.getSelectedSku());
  const getDiscountPercentage = usePDPStore((s) => s.getDiscountPercentage);

  if (isLoading) return <ProductInfoSkeleton />;

  if (!detail?.product) {
    return (
      <div className="flex flex-col items-center justify-center py-12 text-center">
        <p className="text-lg font-semibold text-destructive">
          Không tìm thấy sản phẩm
        </p>
        <p className="text-sm text-muted-foreground mt-2">
          Sản phẩm này hiện không khả dụng hoặc đã bị xóa.
        </p>
      </div>
    );
  }

  const product = detail.product;
  const sku = selectedSku || detail.skus[0];
  const displayPrice = sku?.best_price ?? product.best_price;
  const originalPrice = sku?.original_price ?? product.original_price;
  const discount = calcDiscount(originalPrice, displayPrice);

  return (
    <div className="space-y-6">
      {/* Product Name */}
      <h1 className="text-2xl md:text-3xl font-black tracking-tight text-white/90 leading-tight">
        {product.name}
      </h1>

      {/* Rating */}
      <div className="flex items-center gap-3">
        <div className="flex items-center gap-1.5">
          <Star className="h-4 w-4 fill-forest-bright text-forest-bright drop-shadow-[0_0_5px_rgba(40,200,64,0.5)]" />
          <span className="text-sm font-mono font-bold text-white/90">
            {product.product_rating_avg.toFixed(1)}
          </span>
        </div>
        <span className="text-xs font-mono text-white/40">
          | Đã bán: {product.product_rating_avg > 0 ? "N" : "0"}
        </span>

        {/* Stock status */}
        {sku && (
          <Badge
            variant={(sku as any).display_quantity > 0 ? "default" : "secondary"}
            className={`ml-auto text-[10px] font-mono ${
              (sku as any).display_quantity > 0 
                ? "bg-forest-bright/10 text-forest-bright border-forest-bright/30" 
                : "bg-white/10 text-white/50 border-white/10"
            }`}
          >
            {(sku as any).display_quantity > 0 ? "Còn hàng" : "Hết hàng"}
          </Badge>
        )}
      </div>

      <Separator className="bg-white/10" />

      {/* Price */}
      <div className="space-y-1 bg-[#0a140f] border border-forest/20 p-5 rounded-2xl relative overflow-hidden group">
        <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_top_right,_var(--tw-gradient-stops))] from-forest/10 via-transparent to-transparent opacity-50 group-hover:opacity-100 transition-opacity duration-500" />
        <div className="flex items-baseline gap-3 relative z-10">
          <span className="text-3xl md:text-4xl font-mono font-black text-white">
            {formatVND(displayPrice)}
          </span>
          {discount > 0 && (
            <>
              <span className="text-lg font-mono text-white/40 line-through">
                {formatVND(originalPrice)}
              </span>
              <Badge
                className="text-sm px-3 py-1 font-mono font-bold bg-white text-black hover:bg-white"
              >
                -{discount}%
              </Badge>
            </>
          )}
        </div>
        {(sku as any)?.has_promotion && (
          <p className="text-xs text-forest-bright font-mono mt-2 relative z-10 flex items-center gap-1.5">
            <span className="inline-block w-1.5 h-1.5 rounded-full bg-forest-bright animate-ping"></span>
            Giá khuyến mãi - Ưu đãi có thời hạn
          </p>
        )}
      </div>

      <Separator className="bg-white/10" />

      {/* Variant Picker */}
      <VariantPicker />

      {/* Quantity */}
      <QuantitySelector />

      {/* Add to Cart */}
      <AddToCartButton />
    </div>
  );
}

function calcDiscount(original: number, best: number): number {
  if (original <= 0) return 0;
  return Math.round(((original - best) / original) * 100);
}
