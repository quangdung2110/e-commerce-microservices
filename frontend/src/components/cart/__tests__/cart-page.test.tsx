// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { CartPageContent } from "@/components/cart/cart-page-content";
import { ToastProvider } from "@/components/ui/toast";
import { cartApi, type Cart, type CartItem } from "@/lib/api/cart";
import { httpError, networkError, problem } from "@/lib/api/__tests__/helpers";
import { useCart } from "@/hooks/use-cart";
import { useAuthStore } from "@/store/useAuthStore";

vi.mock("@/lib/api/cart", () => ({ cartApi: { mine: vi.fn(), adjust: vi.fn(), remove: vi.fn() } }));

const line: CartItem = {
  sku_id: "s1", product_id: "p1", product_name: "Điện thoại", quantity: 2,
  image_url: "/media/placeholder/product.svg",
  unit_price_at_add: { amount_minor: 100000, currency: "VND" }, current_price: { amount_minor: 90000, currency: "VND" },
  is_available: true, added_at: "2026-09-30T00:00:00Z", updated_at: "2026-09-30T00:00:00Z",
};
function snapshot(items: CartItem[], freshness: Cart["catalog_freshness"] = "CURRENT"): Cart {
  return { items, total_unique_skus: items.length, total_units: items.reduce((sum, item) => sum + item.quantity, 0), catalog_freshness: freshness };
}
function login(id = "buyer-a") {
  const token = `e30.${btoa(JSON.stringify({ sub: id, roles: ["USER"] }))}.test`;
  useAuthStore.getState().setSession(token);
}
function BadgeProbe() {
  const cart = useCart();
  return <span data-testid="badge">{cart.data?.total_units ?? 0}</span>;
}
function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(<QueryClientProvider client={client}><ToastProvider><BadgeProbe /><CartPageContent /></ToastProvider></QueryClientProvider>);
  return client;
}
async function ready() {
  await screen.findByRole("button", { name: "Tăng số lượng Điện thoại" });
  await waitFor(() => expect((screen.getByRole("button", { name: "Tăng số lượng Điện thoại" }) as HTMLButtonElement).disabled).toBe(false));
}

beforeEach(() => {
  vi.resetAllMocks();
  login();
  vi.mocked(cartApi.mine).mockResolvedValue(snapshot([line]));
  vi.mocked(cartApi.adjust).mockResolvedValue({ sku_id: "s1", quantity: 3, created: false, updated_at: line.updated_at });
  vi.mocked(cartApi.remove).mockResolvedValue({ deleted_count: 1 });
});
afterEach(() => { cleanup(); vi.useRealTimers(); useAuthStore.getState().clear(); });

describe("Cart screen", () => {
  it.each(["CURRENT", "DEGRADED"] as const)("renders the Product display label on %s reads instead of internal attribute codes", async (freshness) => {
    vi.mocked(cartApi.mine).mockResolvedValue(snapshot([{ ...line, variant_name: "Nhãn mới: Đen", variant_attributes: { "qa-axis": "black" } }], freshness));
    setup();
    expect(await screen.findByText("Nhãn mới: Đen")).toBeTruthy();
    expect(screen.queryByText(/qa-axis/)).toBeNull();
  });
  it("keeps legacy cart values readable without exposing attribute codes", async () => {
    vi.mocked(cartApi.mine).mockResolvedValue(snapshot([{ ...line, variant_attributes: { "qa-axis": "128GB", "color-axis": "Bạc" } }]));
    setup();
    expect(await screen.findByText("128GB · Bạc")).toBeTruthy();
    expect(screen.queryByText(/qa-axis|color-axis/)).toBeNull();
  });
  it("offers recovery from the initial read failure without showing an empty cart", async () => {
    vi.mocked(cartApi.mine).mockRejectedValueOnce(httpError(503, problem("CART_STORE_UNAVAILABLE", 503)));
    setup();
    expect(await screen.findByRole("button", { name: "Thử tải lại" })).toBeTruthy();
    expect(screen.queryByText("Giỏ hàng đang trống")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Thử tải lại" }));
    await ready();
  });
  it("keeps a stale snapshot visible after a refetch error and disables edits until recovery", async () => {
    const client = setup(); await ready();
    vi.mocked(cartApi.mine).mockRejectedValueOnce(httpError(503, problem("CART_STORE_UNAVAILABLE", 503)));
    await act(async () => { await client.invalidateQueries({ queryKey: ["cart"] }); });
    expect(await screen.findByText("Đang hiển thị lần tải thành công trước. Tải lại giỏ hàng trước khi tiếp tục thay đổi.")).toBeTruthy();
    expect(screen.getByRole("article", { name: "Điện thoại" })).toBeTruthy();
    expect(screen.getAllByText("Chưa tính được")).toHaveLength(2);
    expect((screen.getByRole("button", { name: "Tăng số lượng Điện thoại" }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.queryByRole("button", { name: "Tải lại giá và giỏ hàng" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Thử tải lại" }));
    await ready();
  });
  it("reads a flat array and shares the header counter", async () => {
    setup(); await ready();
    expect(screen.getByTestId("badge").textContent).toBe("2");
    expect(screen.getByRole("article", { name: "Điện thoại" })).toBeTruthy();
    expect(cartApi.mine).toHaveBeenCalledTimes(1);
  });
  it("sends a signed delta and reads back the authoritative quantity", async () => {
    setup(); await ready();
    vi.mocked(cartApi.mine).mockResolvedValue(snapshot([{ ...line, quantity: 3 }]));
    fireEvent.click(screen.getByRole("button", { name: "Tăng số lượng Điện thoại" }));
    await waitFor(() => expect(screen.getByTestId("badge").textContent).toBe("3"));
    expect(cartApi.adjust).toHaveBeenCalledWith({ sku_id: "s1", delta: 1 }, expect.any(String));
    vi.mocked(cartApi.mine).mockResolvedValue(snapshot([line]));
    fireEvent.click(screen.getByRole("button", { name: "Giảm số lượng Điện thoại" }));
    await waitFor(() => expect(cartApi.adjust).toHaveBeenLastCalledWith({ sku_id: "s1", delta: -1 }, expect.any(String)));
  });
  it("deletes a single SKU and displays the empty state after a fresh read", async () => {
    setup(); await ready();
    vi.mocked(cartApi.mine).mockResolvedValue(snapshot([]));
    fireEvent.click(screen.getByRole("button", { name: "Xóa Điện thoại khỏi giỏ" }));
    await screen.findByText("Giỏ hàng đang trống");
    expect(cartApi.remove).toHaveBeenCalledWith({ sku_ids: ["s1"] }, expect.any(String));
    expect(screen.getByTestId("badge").textContent).toBe("0");
  });
  it("batch deletion sends exactly the selected unique SKU IDs", async () => {
    vi.mocked(cartApi.mine).mockResolvedValue(snapshot([line, { ...line, sku_id: "s2", product_name: "Tai nghe" }]));
    setup(); await ready();
    fireEvent.click(screen.getByRole("checkbox", { name: "Chọn Tai nghe" }));
    fireEvent.click(screen.getByRole("button", { name: "Xóa đã chọn (1)" }));
    await waitFor(() => expect(cartApi.remove).toHaveBeenCalledWith({ sku_ids: ["s2"] }, expect.any(String)));
  });
  it("locks all edits after a lost response, preserves payload and key, and does not optimistically increment", async () => {
    vi.mocked(cartApi.adjust).mockRejectedValueOnce(networkError());
    setup(); await ready();
    fireEvent.click(screen.getByRole("button", { name: "Tăng số lượng Điện thoại" }));
    const retry = await screen.findByRole("button", { name: "Thử lại thao tác" });
    expect((screen.getByRole("button", { name: "Giảm số lượng Điện thoại" }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole("button", { name: "Xóa Điện thoại khỏi giỏ" }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByTestId("badge").textContent).toBe("2");
    fireEvent.click(retry);
    await waitFor(() => expect(cartApi.adjust).toHaveBeenCalledTimes(2));
    expect(vi.mocked(cartApi.adjust).mock.calls[1]).toEqual(vi.mocked(cartApi.adjust).mock.calls[0]);
  });
  it("keeps the delete payload and key after a timeout even if the next read is empty", async () => {
    vi.mocked(cartApi.remove).mockRejectedValueOnce(networkError());
    setup(); await ready();
    vi.mocked(cartApi.mine).mockResolvedValue(snapshot([]));
    fireEvent.click(screen.getByRole("button", { name: "Xóa Điện thoại khỏi giỏ" }));
    await screen.findByText("Giỏ hàng đang trống");
    fireEvent.click(await screen.findByRole("button", { name: "Thử lại thao tác" }));
    await waitFor(() => expect(cartApi.remove).toHaveBeenCalledTimes(2));
    expect(vi.mocked(cartApi.remove).mock.calls[1]).toEqual(vi.mocked(cartApi.remove).mock.calls[0]);
  });
  it("honors Retry-After and displays no raw problem detail", async () => {
    vi.mocked(cartApi.adjust).mockRejectedValueOnce(httpError(429, problem("RATE_LIMITED", 429, { retryable: true }), { "retry-after": "2" }));
    setup(); await ready();
    fireEvent.click(screen.getByRole("button", { name: "Tăng số lượng Điện thoại" }));
    const retry = await screen.findByRole("button", { name: /Thử lại sau/ });
    expect((retry as HTMLButtonElement).disabled).toBe(true);
    expect(screen.queryByText("English detail for developers")).toBeNull();
    await waitFor(() => expect(screen.getByRole("button", { name: "Thử lại thao tác" })).toBeTruthy(), { timeout: 3500 });
  });
  it("keeps data after a definitive failure and allows the next intent with a new key", async () => {
    vi.mocked(cartApi.adjust).mockRejectedValueOnce(httpError(422, problem("PRODUCT_NOT_AVAILABLE", 422, { retryable: false })));
    setup(); await ready();
    fireEvent.click(screen.getByRole("button", { name: "Tăng số lượng Điện thoại" }));
    await screen.findByText("Sản phẩm này hiện không còn bán.");
    await ready();
    fireEvent.click(screen.getByRole("button", { name: "Giảm số lượng Điện thoại" }));
    await waitFor(() => expect(cartApi.adjust).toHaveBeenCalledTimes(2));
    expect(vi.mocked(cartApi.adjust).mock.calls[0][1]).not.toBe(vi.mocked(cartApi.adjust).mock.calls[1][1]);
  });
  it("renders DEGRADED/null prices without a fake zero subtotal and still allows removal", async () => {
    vi.mocked(cartApi.mine).mockResolvedValue(snapshot([{ ...line, current_price: null }], "DEGRADED"));
    setup(); await ready();
    expect(screen.getByText("Chưa xác minh giá")).toBeTruthy();
    expect(screen.getAllByText("Chưa tính được")).toHaveLength(2);
    expect(screen.queryByText("Giá sản phẩm đã thay đổi")).toBeNull();
    expect((screen.getByRole("button", { name: "Xóa Điện thoại khỏi giỏ" }) as HTMLButtonElement).disabled).toBe(false);
    expect(screen.queryByText("Hết hàng")).toBeNull();
  });
  it("prevents going below 1 or above 100 and only labels CURRENT inactive items discontinued", async () => {
    vi.mocked(cartApi.mine).mockResolvedValue(snapshot([{ ...line, quantity: 1, is_available: false, current_price: null }, { ...line, sku_id: "s2", product_name: "Tai nghe", quantity: 100 }]));
    setup(); await screen.findByText("Ngưng bán");
    expect((screen.getByRole("button", { name: "Giảm số lượng Điện thoại" }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole("button", { name: "Tăng số lượng Điện thoại" }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole("button", { name: "Tăng số lượng Tai nghe" }) as HTMLButtonElement).disabled).toBe(true);
  });
  it("does not fetch a private cart while auth is loading", async () => {
    useAuthStore.setState({ status: "loading", claims: null });
    setup();
    await act(async () => { await Promise.resolve(); });
    expect(cartApi.mine).not.toHaveBeenCalled();
  });
  it("a new account cannot see the old account cart or inherit an unresolved command", async () => {
    vi.mocked(cartApi.adjust).mockRejectedValueOnce(networkError());
    setup(); await ready();
    fireEvent.click(screen.getByRole("button", { name: "Tăng số lượng Điện thoại" }));
    await screen.findByRole("button", { name: "Thử lại thao tác" });
    vi.mocked(cartApi.mine).mockResolvedValue(snapshot([]));
    act(() => login("buyer-b"));
    await screen.findByText("Giỏ hàng đang trống");
    expect(screen.queryByRole("article", { name: "Điện thoại" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Thử lại thao tác" })).toBeNull();
  });
});
