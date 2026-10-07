import { OrdersPageContent } from "@/components/orders/orders-page-content";
import type { BuyerTab } from "@/lib/api/order";

export const metadata = {
  title: "Đơn hàng của tôi - TechNova",
  description: "Theo dõi trạng thái đơn hàng, thanh toán, trả hàng và hoàn tiền",
};

const TABS = new Set<BuyerTab>(["TO_PAY", "TO_SHIP", "TO_RECEIVE", "RETURN_REFUND", "COMPLETED", "CANCELLED"]);

export default async function OrdersPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const params = await searchParams;
  const rawTab = typeof params.tab === "string" ? params.tab : undefined;
  const tab = rawTab && TABS.has(rawTab as BuyerTab) ? rawTab as BuyerTab : undefined;
  const query = typeof params.q === "string" ? params.q.slice(0, 200).trim() : "";
  return <OrdersPageContent key={`${tab ?? "ALL"}:${query}`} initialTab={tab} initialQuery={query} />;
}
