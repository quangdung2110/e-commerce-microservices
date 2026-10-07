import type { Cart, CartItem, CartMoney } from "@/lib/api/cart";
import { formatVND } from "@/lib/format";

export function validCartMoney(money: CartMoney | null): money is CartMoney {
  return money !== null && Number.isSafeInteger(money.amount_minor) && money.amount_minor >= 0 && /^[A-Z]{3}$/.test(money.currency);
}

export function formatCartMoney(money: CartMoney): string {
  if (!validCartMoney(money)) return "Chưa xác minh giá";
  if (money.currency === "VND") return formatVND(money.amount_minor);
  const formatter = new Intl.NumberFormat("vi-VN", { style: "currency", currency: money.currency });
  const digits = formatter.resolvedOptions().maximumFractionDigits ?? 0;
  return formatter.format(money.amount_minor / 10 ** digits);
}

export function currentLineTotal(item: CartItem, freshness: Cart["catalog_freshness"]): CartMoney | null {
  if (freshness !== "CURRENT" || !validCartMoney(item.current_price)) return null;
  const amount = item.current_price.amount_minor * item.quantity;
  return Number.isSafeInteger(amount) ? { ...item.current_price, amount_minor: amount } : null;
}

/** Missing prices, degraded saleability and mixed currencies cannot yield a total. */
export function cartSubtotal(cart: Cart): CartMoney | null {
  if (cart.catalog_freshness !== "CURRENT") return null;
  const available = cart.items.filter((item) => item.is_available);
  if (!available.length) return { amount_minor: 0, currency: "VND" };
  let total = 0;
  let currency: string | undefined;
  for (const item of available) {
    const line = currentLineTotal(item, cart.catalog_freshness);
    if (!line || (currency && currency !== line.currency)) return null;
    currency = line.currency;
    total += line.amount_minor;
    if (!Number.isSafeInteger(total)) return null;
  }
  return { amount_minor: total, currency: currency! };
}

export function cartPriceChanges(cart: Cart): CartItem[] {
  if (cart.catalog_freshness !== "CURRENT") return [];
  return cart.items.filter((item) => item.is_available && validCartMoney(item.current_price)
    && validCartMoney(item.unit_price_at_add) && item.current_price.currency === item.unit_price_at_add.currency
    && item.current_price.amount_minor !== item.unit_price_at_add.amount_minor);
}
