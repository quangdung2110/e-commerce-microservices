"use client";

import React, { useEffect, useRef } from "react";
import { useInfiniteQuery } from "@tanstack/react-query";
import { Loader2 } from "lucide-react";
import { ProductCard, Product } from "./product-card";
import { useSearchStore } from "@/store/useSearchStore";

import axios from "axios";

const fetchProducts = async (params: { pageParam: string | null; queryKey: any }) => {
  const [_key, sort, category, inStock, hasPromotion, priceMin, priceMax, filters] = params.queryKey;
  
  const searchParams = new URLSearchParams();
  if (params.pageParam) searchParams.append("cursor", params.pageParam);
  if (sort) searchParams.append("sort", sort);
  if (category) searchParams.append("category", category);
  if (priceMin) searchParams.append("price_min", priceMin.toString());
  if (priceMax) searchParams.append("price_max", priceMax.toString());
  searchParams.append("in_stock", inStock ? "true" : "false");
  if (hasPromotion) searchParams.append("has_promotion", "true");
  
  Object.entries(filters as Record<string, string[]>).forEach(([key, values]) => {
    values.forEach(v => searchParams.append(`filters[${key}]`, v));
  });

  try {
    const response = await axios.get(`/api/v1/search/products?${searchParams.toString()}`);
    
    if (params.pageParam === null) {
      useSearchStore.getState().setTotal(response.data.total || 0);
      useSearchStore.getState().setFacets(response.data.facets || null);
    }
    
    return {
      items: response.data.items || [],
      next_cursor: response.data.next_cursor || null,
      total: response.data.total || 0,
    };
  } catch (error) {
    console.error("Failed to fetch products:", error);
    return { items: [], next_cursor: null, total: 0 };
  }
};

export function ProductGrid() {
  const store = useSearchStore();
  const loaderRef = useRef<HTMLDivElement>(null);

  const {
    data,
    fetchNextPage,
    hasNextPage,
    isFetchingNextPage,
    status,
  } = useInfiniteQuery({
    queryKey: [
      "products",
      store.sort,
      store.category,
      store.inStock,
      store.hasPromotion,
      store.priceMin,
      store.priceMax,
      store.filters,
    ],
    queryFn: fetchProducts,
    initialPageParam: null as string | null,
    getNextPageParam: (lastPage) => lastPage.next_cursor,
  });

  // Infinite Scroll Intersection Observer
  useEffect(() => {
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting && hasNextPage && !isFetchingNextPage) {
          fetchNextPage();
        }
      },
      { threshold: 0.1 }
    );

    if (loaderRef.current) {
      observer.observe(loaderRef.current);
    }

    return () => observer.disconnect();
  }, [hasNextPage, isFetchingNextPage, fetchNextPage]);

  if (status === "pending") {
    return (
      <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-6">
        {Array.from({ length: 8 }).map((_, i) => (
          <div key={i} className="flex flex-col gap-3">
            <div className="aspect-[4/3] bg-white/5 animate-pulse rounded-2xl" />
            <div className="h-4 bg-white/5 animate-pulse rounded w-3/4" />
            <div className="h-4 bg-white/5 animate-pulse rounded w-1/2" />
          </div>
        ))}
      </div>
    );
  }

  if (status === "error") {
    return (
      <div className="w-full py-12 text-center text-destructive">
        <p>Đã xảy ra lỗi khi tải dữ liệu sản phẩm.</p>
      </div>
    );
  }

  return (
    <div className="space-y-8">
      <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-6">
        {data.pages.map((page, pageIndex) => (
          <React.Fragment key={pageIndex}>
            {page.items.map((product: Product) => (
              <ProductCard key={product.sku_id} product={product} />
            ))}
          </React.Fragment>
        ))}
      </div>

      {/* Loader trigger */}
      <div
        ref={loaderRef}
        className="w-full flex items-center justify-center py-8"
      >
        {isFetchingNextPage && (
          <div className="flex items-center gap-2 text-white/50 font-mono">
            <Loader2 className="h-6 w-6 animate-spin text-forest-bright" />
            <span className="text-sm font-bold uppercase tracking-widest">Đang tải thêm...</span>
          </div>
        )}
        {!hasNextPage && data.pages.length > 0 && (
          <p className="text-sm font-mono text-white/30 uppercase tracking-widest">
            Đã hiển thị tất cả sản phẩm.
          </p>
        )}
      </div>
    </div>
  );
}
