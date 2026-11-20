import { Suspense } from "react";
import { InventoryOperations } from "@/components/admin/inventory/inventory-operations";
import { DetailSkeleton } from "@/components/admin/inventory/inventory-shared";
export default function Page() {
  return (
    <Suspense fallback={<DetailSkeleton />}>
      <InventoryOperations />
    </Suspense>
  );
}
