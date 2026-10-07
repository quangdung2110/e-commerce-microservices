import { AdminOrderDetail } from "@/components/admin/orders/admin-order-detail";

export default async function AdminOrderDetailPage({
  params,
}: {
  params: Promise<{ orderId: string }>;
}) {
  const { orderId } = await params;
  return <AdminOrderDetail orderId={orderId} />;
}
