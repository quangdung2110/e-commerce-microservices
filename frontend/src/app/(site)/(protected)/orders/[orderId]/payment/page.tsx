import { PaymentContent } from "@/components/orders/payment-content";

export const metadata = {
  title: "Thanh toán đơn hàng - TechNova",
  description: "Thanh toán an toàn qua Stripe",
};

export default async function PaymentPage({ params, searchParams }: { params: Promise<{ orderId: string }>; searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const [{ orderId }, query] = await Promise.all([params, searchParams]);
  const rawAttempt = typeof query.attempt === "string" ? Number(query.attempt) : NaN;
  const attempt = Number.isSafeInteger(rawAttempt) && rawAttempt > 0 ? rawAttempt : null;
  return <PaymentContent orderId={orderId} requestedAttempt={attempt} />;
}
