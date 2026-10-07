"use client";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { PackageSearch } from "lucide-react";
import { useCursorInfiniteList } from "@/hooks/use-cursor-list";
import { searchApi, type SearchPage, type SearchParams } from "@/lib/api/search";
import type { PublicApiFailure } from "@/lib/api/storefront-server";
import { ProductGrid, ProductGridSkeleton } from "./product-grid";
import { Button, buttonVariants } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { ErrorState } from "@/components/ui/error-state";
import { problemCode } from "@/lib/api/problem";

export function CatalogResults({ params, initial, failure, instanceKey }: { params: SearchParams; initial: SearchPage | null; failure: PublicApiFailure | null; instanceKey: string }) {
  const router = useRouter();
  const query = useCursorInfiniteList({
    queryKey: ["storefront-search", params, instanceKey],
    fetchPage: (cursor, signal) => searchApi.search({ ...params, cursor }, signal),
    initialPage: initial ?? undefined,
    enabled: !!initial, refetchOnMount: false, gcTime: 0,
  });
  const items = [...new Map(query.data?.pages.flatMap((page) => page.items).map((item) => [item.product_id, item]) ?? []).values()];
  const error = query.error ?? (!query.data ? failure : null);
  if (!query.data && query.isFetching) return <ProductGridSkeleton />;
  return <div className="min-w-0 space-y-6" aria-busy={query.isFetching}>
    {error && <ErrorState error={error} message={problemCode(error) === "INVALID_CURSOR" ? "Kết quả tìm kiếm đã hết hạn. Thử lại để tải danh sách mới." : undefined} onRetry={() => { if (problemCode(error) === "INVALID_CURSOR") router.refresh(); else if (query.isFetchNextPageError) void query.fetchNextPage(); else void query.refetch(); }} />}
    {query.data && <p className="text-caption text-muted-foreground" aria-live="polite">{query.data.pages[0].total_products.toLocaleString("vi-VN")} sản phẩm phù hợp</p>}
    {query.data && (items.length ? <ProductGrid items={items} /> : <EmptyState icon={PackageSearch} title="Chưa có kết quả phù hợp" description="Thử bỏ bớt bộ lọc hoặc xem cả sản phẩm tạm hết hàng." action={<Link href="/search?in_stock=false" className={buttonVariants({ variant: "outline" })}>Xem tất cả</Link>} />)}
    {query.hasNextPage && <div className="text-center"><Button variant="outline" disabled={query.isFetching} onClick={() => void query.fetchNextPage()}>{query.isFetchingNextPage ? "Đang tải…" : "Tải thêm"}</Button></div>}
  </div>;
}
