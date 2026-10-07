import Link from "next/link";
import { Star } from "lucide-react";
import type { SearchItem } from "@/lib/api/search";
import { formatVND } from "@/lib/format";
import { StorefrontImage } from "./storefront-image";

export function ProductCard({ product }: { product: SearchItem }) {
  return <Link href={`/products/${encodeURIComponent(product.product_slug)}?sku=${encodeURIComponent(product.sku_id)}`} className="focus-ring group flex min-w-0 flex-col gap-3 rounded-xl">
    <div className="relative aspect-square overflow-hidden rounded-xl bg-surface-2">
      <StorefrontImage src={product.thumbnail_url} alt={product.name} className="size-full object-contain" />
      {product.discount_percentage > 0 && <span className="absolute left-3 top-3 rounded-md bg-surface-overlay px-2 py-1 text-caption">Giảm {product.discount_percentage}%</span>}
    </div>
    <div className="space-y-2">
      <h3 className="line-clamp-2 min-h-12 text-[15px] font-medium group-hover:underline">{product.name}</h3>
      <p className="flex items-center gap-1 text-caption text-muted-foreground"><Star className="size-3" aria-hidden />{product.product_rating_count > 0 && product.product_rating_avg !== null ? `${product.product_rating_avg.toFixed(1)} (${product.product_rating_count})` : "Chưa có đánh giá"}</p>
      <p className="text-lg font-medium tabular-nums">Từ {formatVND(product.selling_price_vnd)}</p>
      {product.base_price_vnd > product.selling_price_vnd && <p className="text-caption text-muted-foreground line-through">{formatVND(product.base_price_vnd)}</p>}
      <p className={`text-caption ${product.is_in_stock ? "text-success" : "text-muted-foreground"}`}>{product.is_in_stock ? "Còn hàng" : "Chưa xác nhận còn hàng"}</p>
    </div>
  </Link>;
}
