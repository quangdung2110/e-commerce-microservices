import { CheckoutPageContent } from "@/components/checkout/checkout-page-content";
import { checkoutToken } from "@/lib/checkout";

export const metadata = {
  title: "Đặt hàng - TechNova",
  robots: { index: false, follow: false },
};

export default async function CheckoutPage({ searchParams }: {
  searchParams: Promise<{ submission?: string | string[] }>;
}) {
  return <CheckoutPageContent initialToken={checkoutToken((await searchParams).submission)} />;
}
