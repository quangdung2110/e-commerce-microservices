import { ReservationDetail } from "@/components/admin/inventory/reservation-detail";

export default async function AdminReservationDetailPage({
  params,
}: {
  params: Promise<{ orderId: string }>;
}) {
  const { orderId } = await params;
  return <ReservationDetail orderId={orderId} />;
}
