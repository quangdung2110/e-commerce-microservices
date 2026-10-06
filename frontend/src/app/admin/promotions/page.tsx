import { Suspense } from "react";

import { CampaignList } from "@/components/admin/promotions/campaign-list";
import { Skeleton } from "@/components/ui/skeleton";

export default function AdminPromotionsPage() {
  return (
    <Suspense fallback={<PromotionsLoading />}>
      <CampaignList />
    </Suspense>
  );
}

function PromotionsLoading() {
  return (
    <div className="space-y-8" aria-busy="true" aria-label="Đang tải campaign">
      <div className="space-y-3"><Skeleton className="h-10 w-56" /><Skeleton className="h-4 w-96 max-w-full" /></div>
      <Skeleton className="h-24 rounded-xl" />
      <Skeleton className="h-96 rounded-xl" />
    </div>
  );
}
