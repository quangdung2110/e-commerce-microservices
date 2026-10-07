import { Alert } from "@/components/ui/alert";
import type { Cart } from "@/lib/api/cart";
import { cartPriceChanges, formatCartMoney } from "@/lib/cart";

export function PriceChangeBanner({ cart }: { cart: Cart }) {
  const changes = cartPriceChanges(cart);
  if (!changes.length) return null;
  return <Alert variant="warning" title="Giá sản phẩm đã thay đổi">
    <p>Giá hiện tại khác giá lúc thêm vào giỏ. Tạm tính sử dụng giá hiện tại.</p>
    <ul className="mt-3 space-y-2">
      {changes.map((item) => <li key={item.sku_id} className="break-words">
        <span className="font-medium">{item.product_name}</span>: {item.current_price!.amount_minor > item.unit_price_at_add.amount_minor ? "tăng" : "giảm"} từ {formatCartMoney(item.unit_price_at_add)} thành {formatCartMoney(item.current_price!)}
      </li>)}
    </ul>
  </Alert>;
}
