import { PaymentCompleteContent } from "@/components/orders/payment-complete-content";

export const metadata = {
  title: "Kết quả thanh toán - TechNova",
  description: "Theo dõi kết quả thanh toán đơn hàng",
};

export default async function PaymentCompletePage({ params }: { params: Promise<{ orderId: string }> }) {
  const { orderId } = await params;
  return <PaymentCompleteContent orderId={orderId} />;
}
