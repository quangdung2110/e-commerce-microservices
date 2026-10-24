// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import ProductPage from "@/app/(site)/products/[slug]/page";
import { storefrontServer } from "@/lib/api/storefront-server";
import { ToastProvider } from "@/components/ui/toast";
import { cartApi } from "@/lib/api/cart";
import { useAuthStore } from "@/store/useAuthStore";
import type { ProductDetail } from "@/lib/api/product-detail";

vi.mock("@/lib/api/storefront-server", () => ({ storefrontServer: { product: vi.fn() }, publicFailure: vi.fn() }));
vi.mock("next/navigation", () => ({ notFound: vi.fn(() => { throw new Error("not found"); }) }));
vi.mock("@/lib/api/cart", () => ({ cartApi: { adjust: vi.fn() } }));

const product: ProductDetail = {
  product_id: "p1", slug: "phone", name: "Điện thoại", description: null, catalog_revision: 1,
  category: { category_id: "c", name: "Danh mục", slug: "phones", path: [] }, attributes: [], media: [],
  variant_axes: [{ code: "color", name: "Màu", value_type: "TEXT", options: [{ value: "Xanh", label: null }, { value: "Bạc", label: null }] }],
  skus: ["Xanh", "Bạc"].map((color, index) => ({ sku_id: `sku-${index}`, sku_code: color,
    variant_attributes: { color }, thumbnail_url: "/placeholder.svg", base_price_vnd: 100,
    selling_price_vnd: 100, availability_status: "IN_STOCK" })),
  review_section: { status: "AVAILABLE", summary: { average_rating: 0, total_reviews: 0, total_with_media: 0 }, preview: [] },
};

async function openPage(query: Record<string, string> = {}) {
  const element = await ProductPage({ params: Promise.resolve({ slug: "phone" }), searchParams: Promise.resolve(query) });
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { mutations: { retry: false } } })}><ToastProvider>{element}</ToastProvider></QueryClientProvider>);
}
beforeEach(() => {
  vi.resetAllMocks(); useAuthStore.getState().clear();
  vi.mocked(storefrontServer.product).mockResolvedValue(product);
  vi.mocked(cartApi.adjust).mockResolvedValue({ sku_id: "sku-1", quantity: 3, created: true, updated_at: "2026-10-16T00:00:00Z" });
});
afterEach(() => { cleanup(); useAuthStore.getState().clear(); });

describe("PDP login return intent", () => {
  it("preserves the selected SKU and quantity through the actual page searchParams boundary, then adds that delta once", async () => {
    const initial = await openPage();
    fireEvent.click(screen.getByRole("button", { name: "Bạc" }));
    fireEvent.change(screen.getByRole("spinbutton", { name: "Số lượng" }), { target: { value: "3" } });
    const login = new URL(screen.getByRole("link", { name: "Đăng nhập để thêm vào giỏ" }).getAttribute("href")!, "http://localhost");
    const returned = new URL(login.searchParams.get("next")!, "http://localhost");
    expect(returned.searchParams.get("sku")).toBe("sku-1");
    expect(returned.searchParams.get("quantity")).toBe("3");
    expect(cartApi.adjust).not.toHaveBeenCalled();
    initial.unmount();
    useAuthStore.getState().setSession(`e30.${btoa(JSON.stringify({ sub: "buyer", roles: ["USER"] }))}.test`);
    await openPage(Object.fromEntries(returned.searchParams));
    expect((screen.getByRole("spinbutton", { name: "Số lượng" }) as HTMLInputElement).value).toBe("3");
    expect(screen.getByRole("button", { name: "Bạc", pressed: true })).toBeTruthy();
    expect(cartApi.adjust).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Thêm vào giỏ hàng" }));
    await waitFor(() => expect(cartApi.adjust).toHaveBeenCalledWith({ sku_id: "sku-1", delta: 3 }, expect.any(String)));
    expect(cartApi.adjust).toHaveBeenCalledTimes(1);
  });

  it.each([undefined, "0", "-1", "101", "1.5", "1e2", "NaN", "", "3x"])("uses quantity1 for an invalid return parameter %s", async (quantity) => {
    await openPage(quantity === undefined ? {} : { quantity });
    expect((screen.getByRole("spinbutton", { name: "Số lượng" }) as HTMLInputElement).value).toBe("1");
  });

  it.each(["1", "100"])("keeps a valid boundary quantity %s", async (quantity) => {
    await openPage({ sku: "sku-1", quantity });
    expect((screen.getByRole("spinbutton", { name: "Số lượng" }) as HTMLInputElement).value).toBe(quantity);
  });

  it("resets quantity when the buyer makes a new SKU selection", async () => {
    await openPage({ sku: "sku-1", quantity: "3" });
    fireEvent.click(screen.getByRole("button", { name: "Xanh" }));
    expect((screen.getByRole("spinbutton", { name: "Số lượng" }) as HTMLInputElement).value).toBe("1");
  });
});
