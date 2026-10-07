import type { SearchItem } from "@/lib/api/search";
import { ProductCard } from "./product-card";
import { Skeleton } from "@/components/ui/skeleton";
export function ProductGrid({ items }: { items: SearchItem[] }) {
  return <div className="grid grid-cols-2 gap-x-5 gap-y-8 md:grid-cols-3 xl:grid-cols-4">{items.map((item) => <ProductCard key={item.product_id} product={item} />)}</div>;
}
export function ProductGridSkeleton() {
  return <div aria-label="Đang tải sản phẩm" aria-busy className="grid grid-cols-2 gap-x-5 gap-y-8 md:grid-cols-3 xl:grid-cols-4">{Array.from({ length: 8 }, (_, i) => <div key={i} className="space-y-3"><Skeleton className="aspect-square w-full rounded-xl" /><Skeleton className="h-12 w-full" /><Skeleton className="h-6 w-2/3" /></div>)}</div>;
}
