import { StockMovementDetail } from "@/components/admin/inventory/stock-movement-detail";

export default async function AdminStockMovementPage({ params }: { params: Promise<{ movementId: string }> }) {
  const { movementId } = await params;
  return <StockMovementDetail movementId={movementId} />;
}
