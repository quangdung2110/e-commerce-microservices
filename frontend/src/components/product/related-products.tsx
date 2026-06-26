"use client";

import { ChevronLeft, ChevronRight } from "lucide-react";
import { useRelatedProducts } from "@/hooks/use-product-detail";
import { ProductCard } from "@/components/catalog/product-card";
import { Skeleton } from "@/components/ui/skeleton";

// ─── Loading Skeleton ───────────────────────────────────────────

function RelatedSkeleton() {
  return (
    <div className="flex gap-4 overflow-x-auto pb-4">
      {Array.from({ length: 5 }).map((_, i) => (
        <div key={i} className="flex-shrink-0 w-56 space-y-3">
          <Skeleton className="aspect-[4/3] rounded-2xl bg-white/5" />
          <Skeleton className="h-4 w-3/4 bg-white/5" />
          <Skeleton className="h-4 w-1/2 bg-white/5" />
        </div>
      ))}
    </div>
  );
}

// ─── Component ──────────────────────────────────────────────────

interface RelatedProductsProps {
  categoryId: string | null;
  excludeProductId: string;
  productName?: string;
}

export function RelatedProducts({
  categoryId,
  excludeProductId,
  productName,
}: RelatedProductsProps) {
  const { data, isLoading, isError } = useRelatedProducts(
    categoryId,
    excludeProductId
  );

  if (isLoading) return <RelatedSkeleton />;
  if (isError || !data || !data.items || data.items.length === 0) return null;

  const related = data.items.filter(
    (item) => item.product_id !== excludeProductId
  );
  if (related.length === 0) return null;

  return (
    <div className="space-y-6">
      {/* Section header */}
      <div className="flex items-center justify-between">
        <h2 className="text-2xl font-black tracking-widest uppercase text-white font-mono">
          Sản phẩm liên quan
        </h2>
        <div className="flex items-center gap-2">
          <button
            className="h-8 w-8 rounded-full border border-white/20 flex items-center justify-center text-white/70 hover:text-white hover:bg-white/10 transition-colors"
            aria-label="Cuộn trái"
            onClick={() => {
              const el = document.getElementById("related-scroll");
              if (el) el.scrollBy({ left: -300, behavior: "smooth" });
            }}
          >
            <ChevronLeft className="h-4 w-4" />
          </button>
          <button
            className="h-8 w-8 rounded-full border border-white/20 flex items-center justify-center text-white/70 hover:text-white hover:bg-white/10 transition-colors"
            aria-label="Cuộn phải"
            onClick={() => {
              const el = document.getElementById("related-scroll");
              if (el) el.scrollBy({ left: 300, behavior: "smooth" });
            }}
          >
            <ChevronRight className="h-4 w-4" />
          </button>
        </div>
      </div>

      {/* Horizontal scroll */}
      <div
        id="related-scroll"
        className="flex gap-4 overflow-x-auto pb-4 scroll-smooth scrollbar-none snap-x snap-mandatory"
      >
        {related.map((product) => (
          <div
            key={product.sku_id}
            className="flex-shrink-0 w-56 snap-start"
          >
            <ProductCard product={product as any} />
          </div>
        ))}
      </div>
    </div>
  );
}
