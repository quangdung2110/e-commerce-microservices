import { Skeleton } from "@/components/ui/skeleton";

export function CatalogDetailLoading() {
  return (
    <div className="space-y-8" aria-busy="true" aria-label="Đang tải chi tiết catalog">
      <div className="space-y-3">
        <Skeleton className="h-10 w-72 max-w-full" />
        <Skeleton className="h-4 w-56 max-w-full" />
      </div>
      <Skeleton className="h-11 w-full" />
      <Skeleton className="h-[480px] w-full rounded-xl" />
    </div>
  );
}
