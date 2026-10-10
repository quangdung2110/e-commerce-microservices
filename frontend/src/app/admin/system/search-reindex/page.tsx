import { Suspense } from "react";
import { SearchReindex } from "@/components/admin/system/search-reindex";
import { Skeleton } from "@/components/ui/skeleton";
export default function Page() {
  return (
    <Suspense fallback={<Skeleton className="h-64 rounded-xl" />}>
      <SearchReindex />
    </Suspense>
  );
}
