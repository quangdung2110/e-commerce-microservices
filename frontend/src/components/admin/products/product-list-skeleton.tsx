import { Skeleton } from "@/components/ui/skeleton";

export function ProductListSkeleton() {
  return (
    <div className="space-y-8" aria-busy="true">
      <div className="space-y-2">
        <Skeleton className="h-11 w-48" />
        <Skeleton className="h-5 w-full max-w-xl" />
      </div>
      <div className="grid gap-4 rounded-xl border border-border bg-surface-2 p-4 sm:grid-cols-2">
        <Skeleton className="h-11 w-full" />
        <Skeleton className="h-11 w-full" />
      </div>
      <div className="space-y-3">
        <Skeleton className="h-12 w-full rounded-xl" />
        {Array.from({ length: 5 }, (_, index) => (
          <Skeleton key={index} className="h-14 w-full rounded-xl" />
        ))}
      </div>
    </div>
  );
}
