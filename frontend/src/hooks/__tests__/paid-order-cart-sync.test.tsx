// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, renderHook } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useOrder, orderKeys } from "@/hooks/use-orders";
import { useCart, cartKeys } from "@/hooks/use-cart";
import { cartApi, type Cart } from "@/lib/api/cart";
import { orderApi, type OrderDetail } from "@/lib/api/order";
import { useAuthStore } from "@/store/useAuthStore";

vi.mock("@/lib/api/cart", () => ({ cartApi: { mine: vi.fn() } }));
vi.mock("@/lib/api/order", () => ({ orderApi: { mine: vi.fn() } }));

const purchased = { sku_id: "paid-sku", quantity: 3 };
const other = { sku_id: "unbought-sku", quantity: 2 };
const cart = (items: typeof purchased[]) => ({ items, total_units: items.reduce((n, x) => n + x.quantity, 0), total_unique_skus: items.length, catalog_freshness: "CURRENT" }) as Cart;
const order = (payment_status: string) => ({ order_id: "order-1", buyer_id: "buyer-a", payment_status, items: [{ lines: [{ sku_id: "paid-sku" }] }] }) as OrderDetail;
const login = (id = "buyer-a") => useAuthStore.getState().setSession(`e30.${btoa(JSON.stringify({ sub: id, roles: ["USER"] }))}.test`);
let client: QueryClient;
function setup(paymentStatus = "PENDING") {
  vi.mocked(orderApi.mine).mockResolvedValue(order(paymentStatus));
  return renderHook(() => ({ order: useOrder("order-1"), cart: useCart() }), {
    wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>,
  });
}
async function tick(ms = 1) { await act(async () => { await vi.advanceTimersByTimeAsync(ms); }); }
async function paid() { act(() => client.setQueryData(orderKeys.detail("buyer-a", "order-1"), order("PAID"))); await tick(); }
beforeEach(() => {
  vi.useFakeTimers(); vi.resetAllMocks(); login();
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  vi.mocked(cartApi.mine).mockResolvedValue(cart([purchased, other]));
});
afterEach(() => { cleanup(); client.clear(); useAuthStore.getState().clear(); vi.useRealTimers(); });

describe("paid order shared cart", () => {
  it("waits for backend PAID proof instead of trusting the payment redirect", async () => {
    setup(); await tick();
    expect(cartApi.mine).toHaveBeenCalledTimes(1);
    await tick(10_000);
    expect(cartApi.mine).toHaveBeenCalledTimes(1);
    expect(client.getQueryData<Cart>(cartKeys.mine("buyer-a"))?.total_units).toBe(5);
  });
  it("updates the badge on PAID and retries consumer lag while preserving unbought SKUs", async () => {
    const screen = setup(); await tick(); await paid();
    expect(cartApi.mine).toHaveBeenCalledTimes(2);
    expect(screen.result.current.cart.data?.total_units).toBe(5);
    vi.mocked(cartApi.mine).mockResolvedValue(cart([other]));
    await tick(2_010);
    expect(screen.result.current.cart.data?.total_units).toBe(2);
    expect(client.getQueryData<Cart>(cartKeys.mine("buyer-a"))?.items).toEqual([other]);
    const calls = vi.mocked(cartApi.mine).mock.calls.length;
    await tick(10_000); expect(cartApi.mine).toHaveBeenCalledTimes(calls);
  });
  it("refreshes an already PAID order on mount without requiring the cart page", async () => {
    client.setQueryData(cartKeys.mine("buyer-a"), cart([purchased]));
    vi.mocked(cartApi.mine).mockResolvedValue(cart([]));
    const screen = setup("PAID"); await tick(10);
    expect(screen.result.current.cart.data?.total_units).toBe(0);
  });
  it("retains the cached count on a read error and recovers on a later read", async () => {
    setup(); await tick();
    vi.mocked(cartApi.mine).mockRejectedValueOnce(new Error("503")); await paid();
    expect(client.getQueryData<Cart>(cartKeys.mine("buyer-a"))?.total_units).toBe(5);
    vi.mocked(cartApi.mine).mockResolvedValue(cart([other])); await tick(2_010);
    expect(client.getQueryData<Cart>(cartKeys.mine("buyer-a"))?.total_units).toBe(2);
  });
  it("stops reconciliation when the signed-in account changes", async () => {
    const screen = setup(); await tick(); await paid();
    vi.mocked(cartApi.mine).mockResolvedValue(cart([other]));
    act(() => login("buyer-b")); await tick(10);
    const calls = vi.mocked(cartApi.mine).mock.calls.length;
    await tick(10_000); expect(cartApi.mine).toHaveBeenCalledTimes(calls);
    expect(screen.result.current.cart.data?.total_units).toBe(2);
  });
  it("bounds retry reads when the consumer never converges", async () => {
    setup(); await tick(); await paid(); await tick(35_000);
    expect(cartApi.mine).toHaveBeenCalledTimes(16); // Initial cart plus at most 15 reconciliation reads.
    await tick(60_000); expect(cartApi.mine).toHaveBeenCalledTimes(16);
  });
  it("does not store a late cart response under the previous account after switching", async () => {
    setup(); await tick();
    let resolveRead!: (value: Cart) => void;
    vi.mocked(cartApi.mine).mockReturnValueOnce(new Promise((resolve) => { resolveRead = resolve; }));
    await paid();
    vi.mocked(cartApi.mine).mockResolvedValue(cart([other]));
    act(() => login("buyer-b")); await tick();
    await act(async () => resolveRead(cart([]))); await tick();
    expect(client.getQueryData<Cart>(cartKeys.mine("buyer-a"))?.total_units).toBe(5);
    expect(client.getQueryData<Cart>(cartKeys.mine("buyer-b"))?.total_units).toBe(2);
  });
});
