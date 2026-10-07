import { Suspense } from "react";

import { CampaignDetail } from "@/components/admin/promotions/campaign-detail";
import { Skeleton } from "@/components/ui/skeleton";

export default async function AdminCampaignPage({
  params,
}: {
  params: Promise<{ campaignId: string }>;
}) {
  const { campaignId } = await params;
  return (
    <Suspense fallback={<Skeleton className="h-96 rounded-xl" aria-busy="true" />}>
      <CampaignDetail campaignId={campaignId} />
    </Suspense>
  );
}
