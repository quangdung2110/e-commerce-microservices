import { StockPositionDetail } from "@/components/admin/inventory/stock-position-detail";

export default async function AdminStockPositionPage({ params }: { params: Promise<{ warehouseId: string; skuId: string }> }) {
  const { warehouseId, skuId } = await params;
  return <StockPositionDetail warehouseId={warehouseId} skuId={skuId} />;
}
