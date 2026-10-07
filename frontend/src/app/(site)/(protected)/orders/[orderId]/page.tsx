import { OrderDetailContent } from "@/components/orders/order-detail-content";

export const metadata = {
  title: "Chi tiết đơn hàng - TechNova",
  description: "Theo dõi sản phẩm, thanh toán, giao hàng và hoàn tiền",
};

export default async function OrderDetailPage({ params }: { params: Promise<{ orderId: string }> }) {
  const { orderId } = await params;
  return <OrderDetailContent orderId={orderId} />;
}
