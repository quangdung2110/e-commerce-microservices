import { Suspense } from "react";
import { ReturnLookup } from "@/components/admin/inventory/return-lookup";
import { DetailSkeleton } from "@/components/admin/inventory/inventory-shared";
export default function Page() {
  return (
    <Suspense fallback={<DetailSkeleton />}>
      <ReturnLookup />
    </Suspense>
  );
}
