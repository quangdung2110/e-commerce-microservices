import { ReturnRequestContent } from "@/components/orders/return-request-content";

export const metadata = {
  title: "Yêu cầu trả hàng - TechNova",
  description: "Chọn sản phẩm và gửi yêu cầu trả hàng",
};

export default async function ReturnRequestPage({ params }: { params: Promise<{ orderId: string }> }) {
  const { orderId } = await params;
  return <ReturnRequestContent orderId={orderId} />;
}
