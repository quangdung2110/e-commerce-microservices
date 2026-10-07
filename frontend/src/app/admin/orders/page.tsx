import { Suspense } from "react";

import { AdminOrderList } from "@/components/admin/orders/admin-order-list";
import { Skeleton } from "@/components/ui/skeleton";

export default function AdminOrdersPage() {
  return (
    <Suspense fallback={<AdminOrdersLoading />}>
      <AdminOrderList />
    </Suspense>
  );
}

function AdminOrdersLoading() {
  return (
    <div className="space-y-8" aria-busy="true" aria-label="Đang tải đơn hàng">
      <div className="space-y-3"><Skeleton className="h-10 w-56" /><Skeleton className="h-4 w-96 max-w-full" /></div>
      <Skeleton className="h-72 rounded-xl" />
      <Skeleton className="h-96 rounded-xl" />
    </div>
  );
}
