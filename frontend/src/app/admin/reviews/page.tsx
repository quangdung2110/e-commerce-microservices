import { Suspense } from "react";
import { ModerationQueue } from "@/components/admin/reviews/moderation-queue";
import { Skeleton } from "@/components/ui/skeleton";
export default function Page() {
  return (
    <Suspense fallback={<Skeleton className="h-64 rounded-xl" />}>
      <ModerationQueue />
    </Suspense>
  );
}
