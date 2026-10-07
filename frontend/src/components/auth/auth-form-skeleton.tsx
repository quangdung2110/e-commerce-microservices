import { Skeleton } from "@/components/ui/skeleton";

/**
 * Giữ chỗ cho form xác thực trong lúc Suspense chờ. Hình dạng bám đúng bố cục
 * thật (tiêu đề, N field, nút full width) để không có cú nhảy layout khi form
 * xuất hiện.
 */
export function AuthFormSkeleton({ fields = 2 }: { fields?: number }) {
  return (
    <div className="space-y-8">
      <div className="space-y-2">
        <Skeleton className="h-8 w-40" />
        <Skeleton className="h-5 w-64" />
      </div>

      <div className="space-y-5">
        {Array.from({ length: fields }).map((_, index) => (
          <div key={index} className="space-y-2">
            <Skeleton className="h-4 w-24" />
            <Skeleton className="h-11 w-full rounded-lg" />
          </div>
        ))}
        <Skeleton className="h-12 w-full rounded-lg" />
      </div>
    </div>
  );
}
