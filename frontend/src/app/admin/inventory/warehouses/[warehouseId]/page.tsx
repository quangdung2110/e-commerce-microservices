import { WarehouseDetail } from "@/components/admin/inventory/warehouse-detail";

export default async function AdminWarehouseDetailPage({ params }: { params: Promise<{ warehouseId: string }> }) {
  const { warehouseId } = await params;
  return <WarehouseDetail warehouseId={warehouseId} />;
}
