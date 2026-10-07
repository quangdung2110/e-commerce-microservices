import Link from "next/link";
import { ShoppingCart } from "lucide-react";
import { buttonVariants } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";

export function EmptyCart() {
  return <EmptyState icon={ShoppingCart} title="Giỏ hàng đang trống" description="Khám phá sản phẩm và chọn biến thể bạn muốn mua."
    action={<Link href="/search?in_stock=false" className={buttonVariants({ variant: "outline", className: "mt-3" })}>Khám phá sản phẩm</Link>} />;
}
