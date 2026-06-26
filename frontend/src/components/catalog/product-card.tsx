import Link from "next/link";
import { Star, ShoppingCart } from "lucide-react";
import { Badge } from "@/components/ui/badge";

export interface Product {
  product_id: string;
  sku_id: string;
  name: string;
  thumbnail_url: string;
  original_price: number;
  best_price: number;
  discount_percentage: number;
  product_rating_avg: number;
  is_in_stock: boolean;
}

/** Convert product name to URL slug */
function toSlug(name: string): string {
  return name
    .toLowerCase()
    .replace(/[^\w\s-]/g, "")
    .replace(/\s+/g, "-")
    .replace(/-+/g, "-")
    .trim();
}

export function ProductCard({ product }: { product: Product }) {
  const slug = toSlug(product.name);

  return (
    <Link
      href={`/products/${slug}`}
      className="group relative flex flex-col bg-[#0c0c0f] rounded-2xl border border-white/10 hover:border-forest/50 overflow-hidden hover:-translate-y-1 shadow-[0_0_0_1px_rgba(255,255,255,0.04)_inset] hover:shadow-[0_10px_40px_-10px_rgba(40,200,64,0.15)] transition-all duration-300"
    >
      {/* Badges */}
      <div className="absolute top-3 left-3 z-10 flex flex-col gap-2">
        {product.discount_percentage > 0 && (
          <Badge className="bg-destructive hover:bg-destructive text-white font-mono font-bold shadow-md">
            -{product.discount_percentage}%
          </Badge>
        )}
        {!product.is_in_stock && (
          <Badge variant="secondary" className="font-mono font-bold shadow-md backdrop-blur-md bg-white/10 text-white/50 border border-white/10">
            Hết hàng
          </Badge>
        )}
      </div>

      {/* Image */}
      <div className="relative aspect-[4/3] bg-black overflow-hidden border-b border-white/5">
        <div className="absolute inset-0 bg-gradient-to-t from-black/80 via-transparent to-transparent z-10" />
        {/* Placeholder image representation */}
        <div className="w-full h-full flex items-center justify-center bg-[radial-gradient(ellipse_at_center,_var(--tw-gradient-stops))] from-white/[0.05] to-transparent group-hover:scale-105 transition-transform duration-700">
          <span className="text-white/10 font-black text-6xl group-hover:text-forest-bright/20 transition-colors duration-500">T</span>
        </div>
        
        {/* Quick Add Button (Hover) */}
        <div className="absolute bottom-3 left-0 w-full px-3 translate-y-10 opacity-0 group-hover:translate-y-0 group-hover:opacity-100 transition-all duration-300 z-20">
          <button 
            disabled={!product.is_in_stock}
            className="w-full py-2.5 bg-forest-bright hover:bg-forest text-black hover:text-white font-bold rounded-xl flex items-center justify-center gap-2 shadow-[0_0_15px_rgba(40,200,64,0.3)] disabled:opacity-50 disabled:cursor-not-allowed transition-all duration-300 active:scale-95"
          >
            <ShoppingCart className="h-4 w-4" />
            {product.is_in_stock ? "Thêm vào giỏ" : "Hết hàng"}
          </button>
        </div>
      </div>

      {/* Content */}
      <div className="p-5 flex flex-col flex-1 relative z-20">
        <h3 className="font-semibold text-white/90 line-clamp-2 min-h-[44px] group-hover:text-forest-bright transition-colors duration-300">
          {product.name}
        </h3>
        
        <div className="flex items-center gap-1.5 mt-3 mb-4">
          <Star className="h-3.5 w-3.5 fill-forest-bright text-forest-bright drop-shadow-[0_0_5px_rgba(40,200,64,0.5)]" />
          <span className="text-xs font-mono font-bold text-white/70">{product.product_rating_avg.toFixed(1)}</span>
        </div>

        <div className="mt-auto">
          <div className="flex items-baseline gap-2">
            <span className="text-lg font-mono font-black text-white">
              {new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND' }).format(product.best_price)}
            </span>
          </div>
          {product.original_price > product.best_price && (
            <div className="text-xs text-white/40 line-through mt-0.5 font-mono">
              {new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND' }).format(product.original_price)}
            </div>
          )}
        </div>
      </div>
    </Link>
  );
}
