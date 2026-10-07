"use client";

import { useQuery } from "@tanstack/react-query";
import { Boxes } from "lucide-react";

import { DetailSkeleton } from "@/components/admin/inventory/inventory-shared";
import { PageHeader } from "@/components/admin/page-header";
import { IdChip } from "@/components/admin/id-chip";
import { ErrorState } from "@/components/ui/error-state";
import { inventoryAdminApi } from "@/lib/api/inventory-admin";
import { inventoryErrorMessage } from "@/lib/inventory";

const BUCKETS = [
  ["on_hand", "Tồn thực tế", "Tổng số đơn vị đang nằm tại kho."],
  ["available_to_sell", "Có thể bán", "Số lượng đủ điều kiện cho reservation mới."],
  ["reserved", "Đã giữ", "Đang giữ cho đơn chưa hoàn tất thanh toán."],
  ["committed", "Đã cam kết", "Đã thanh toán và chưa rời kho."],
  ["quarantine", "Cách ly", "Hàng trả chưa được phép bán."],
] as const;

export function StockPositionDetail({ warehouseId, skuId }: { warehouseId: string; skuId: string }) {
  const query = useQuery({
    queryKey: ["inventory", "stock-position", warehouseId, skuId],
    queryFn: ({ signal }) => inventoryAdminApi.getStockPosition(warehouseId, skuId, signal),
  });

  if (query.isPending) return <DetailSkeleton />;
  if (query.isError) {
    return <ErrorState error={query.error} message={inventoryErrorMessage(query.error)} onRetry={() => void query.refetch()} />;
  }

  const totalBuckets = query.data.available_to_sell + query.data.reserved + query.data.committed + query.data.quarantine;
  const invariantHolds = totalBuckets === query.data.on_hand;

  return (
    <div className="space-y-8">
      <PageHeader title="Vị trí tồn" description="Số liệu authoritative của một SKU tại một kho. Search và PDP không được dùng các bucket này." />

      <div className="flex flex-wrap gap-4 rounded-xl border border-border bg-surface-2 p-4">
        <div className="space-y-1"><p className="text-caption text-muted-foreground">Kho</p><IdChip value={query.data.warehouse_id} /></div>
        <div className="space-y-1"><p className="text-caption text-muted-foreground">SKU</p><IdChip value={query.data.sku_id} /></div>
        <div className="space-y-1"><p className="text-caption text-muted-foreground">Revision</p><p className="font-mono text-[14px]">{query.data.revision}</p></div>
      </div>

      <section aria-labelledby="stock-buckets" className="space-y-5">
        <div className="space-y-1">
          <h2 id="stock-buckets" className="text-headline-5 font-serif font-normal">Các bucket tồn</h2>
          <p className="text-caption text-muted-foreground">Có thể bán + đã giữ + đã cam kết + cách ly phải bằng tồn thực tế.</p>
        </div>
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-5">
          {BUCKETS.map(([key, label, description]) => (
            <div key={key} className="rounded-xl border border-border bg-surface-overlay p-6">
              <p className="text-caption text-muted-foreground">{label}</p>
              <p className="mt-2 font-serif text-headline-4 font-normal tabular-nums">{query.data[key].toLocaleString("vi-VN")}</p>
              <p className="mt-3 text-caption text-muted-foreground">{description}</p>
            </div>
          ))}
        </div>
      </section>

      <div className={`flex items-start gap-3 rounded-xl border p-5 ${invariantHolds ? "border-success-border bg-success-surface text-success" : "border-danger-border bg-danger-surface text-danger"}`}>
        <Boxes className="mt-0.5 size-5 shrink-0" aria-hidden />
        <div className="space-y-1">
          <p className="font-medium">{invariantHolds ? "Các bucket đang cân bằng" : "Phát hiện bucket không cân bằng"}</p>
          <p className="text-[14px]">{totalBuckets.toLocaleString("vi-VN")} đơn vị trong các bucket so với {query.data.on_hand.toLocaleString("vi-VN")} đơn vị tồn thực tế.</p>
        </div>
      </div>
    </div>
  );
}
