import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import type { Cart, CartItem } from "@/lib/api/cart";
import { cartPriceChanges, cartSubtotal, currentLineTotal, formatCartMoney } from "@/lib/cart";
import { CartSummary } from "@/components/cart/cart-summary";
import { PriceChangeBanner } from "@/components/cart/price-change-banner";

const item: CartItem = {
  sku_id: "s1", product_id: "p1", product_name: "Điện thoại", quantity: 2,
  image_url: "/media/placeholder/product.svg",
  unit_price_at_add: { amount_minor: 100000, currency: "VND" }, current_price: { amount_minor: 90000, currency: "VND" },
  is_available: true, added_at: "2026-09-30T00:00:00Z", updated_at: "2026-09-30T00:00:00Z",
};
const cart: Cart = { items: [item], total_units: 2, total_unique_skus: 1, catalog_freshness: "CURRENT" };

describe("Cart v1 price semantics", () => {
  it("uses integer VND minor units without dividing by 100", () => {
    expect(cartSubtotal(cart)).toEqual({ amount_minor: 180000, currency: "VND" });
    expect(formatCartMoney(item.current_price!)).toContain("90.000");
  });
  it("never substitutes the historical price for a missing current price", () => {
    const missing = { ...item, current_price: null };
    expect(cartSubtotal({ ...cart, items: [missing] })).toBeNull();
    expect(currentLineTotal(missing, "CURRENT")).toBeNull();
    expect(cartPriceChanges({ ...cart, items: [missing] })).toEqual([]);
  });
  it("does not trust prices or saleability under DEGRADED even when a price is present", () => {
    expect(cartSubtotal({ ...cart, catalog_freshness: "DEGRADED" })).toBeNull();
    expect(cartPriceChanges({ ...cart, catalog_freshness: "DEGRADED" })).toEqual([]);
    expect(currentLineTotal(item, "DEGRADED")).toBeNull();
  });
  it("excludes discontinued items even with a null price", () => {
    expect(cartSubtotal({ ...cart, items: [item, { ...item, sku_id: "s2", is_available: false, current_price: null }] })).toEqual({ amount_minor: 180000, currency: "VND" });
    expect(cartSubtotal({ ...cart, items: [{ ...item, is_available: false }] })).toEqual({ amount_minor: 0, currency: "VND" });
  });
  it("rejects mixed currencies and unsafe arithmetic", () => {
    expect(cartSubtotal({ ...cart, items: [item, { ...item, sku_id: "s2", current_price: { amount_minor: 100, currency: "USD" } }] })).toBeNull();
    expect(currentLineTotal({ ...item, current_price: { amount_minor: Number.MAX_SAFE_INTEGER, currency: "VND" } }, "CURRENT")).toBeNull();
  });
  it("compares price amounts only when the currencies match", () => {
    expect(cartPriceChanges(cart)).toEqual([item]);
    expect(cartPriceChanges({ ...cart, items: [{ ...item, current_price: { amount_minor: 100, currency: "USD" } }] })).toEqual([]);
  });
  it("shows both prices and the direction without calling it a promotion", () => {
    const html = renderToStaticMarkup(<PriceChangeBanner cart={cart} />);
    expect(html).toContain("giảm từ");
    expect(html).toContain("100.000");
    expect(html).toContain("90.000");
    expect(html).not.toContain("khuyến mãi");
  });
  it("offers checkout now that step 6 exists, with backend-owned pricing", () => {
    const html = renderToStaticMarkup(<CartSummary cart={cart} stale={false} />);
    expect(html).toContain('href="/checkout"');
    expect(html).not.toContain("Tính năng thanh toán sẽ sớm được mở.");
  });
  it("hides a previously verified subtotal after a read error", () => {
    const html = renderToStaticMarkup(<CartSummary cart={cart} stale />);
    expect(html).toContain("Chưa tính được");
    expect(html).not.toContain("180.000");
    expect(html).not.toContain('href="/checkout"');
  });
  it("blocks checkout while a cart command has an unresolved result", () => {
    const html = renderToStaticMarkup(<CartSummary cart={cart} stale={false} locked />);
    expect(html).toContain("disabled");
    expect(html).not.toContain('href="/checkout"');
  });
});
