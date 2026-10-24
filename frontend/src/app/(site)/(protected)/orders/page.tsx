import { OrdersPageContent } from "@/components/orders/orders-page-content";
import { buyerOrderQuery, parseBuyerOrderSearch } from "@/lib/buyer-order-filters";

export const metadata = {
  title: "Đơn hàng của tôi - TechNova",
  description: "Theo dõi trạng thái đơn hàng, thanh toán, trả hàng và hoàn tiền",
};

export default async function OrdersPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const search = parseBuyerOrderSearch(await searchParams);
  return <OrdersPageContent key={`${buyerOrderQuery(search.tab, search.q, search.filters, search.allHistory)}:${search.errors.join(";")}`} initialTab={search.tab} initialQuery={search.q} initialFilters={search.filters} allHistory={search.allHistory} filterErrors={search.errors} />;
}
