// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, renderHook, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";

const { replace } = vi.hoisted(() => ({ replace: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn(), replace }), usePathname: () => "/orders" }));
vi.mock("@/lib/api/order", () => ({ orderApi: { mine: vi.fn(), listMine: vi.fn(), refundCase: vi.fn(), operation: vi.fn(), cancel: vi.fn(), retryPayment: vi.fn() } }));
vi.mock("@/lib/api/review", () => ({ reviewApi: { listPending: vi.fn() } }));

import { OrderDetailContent } from "@/components/orders/order-detail-content";
import { RefundCaseCard } from "@/components/orders/refund-case-card";
import { OrdersPageContent } from "@/components/orders/orders-page-content";
import { OrderHistory } from "@/components/orders/order-history";
import { ToastProvider } from "@/components/ui/toast";
import { useMyOrders } from "@/hooks/use-orders";
import { orderApi, type OrderDetail } from "@/lib/api/order";
import { ALL_ORDER_HISTORY_FROM, parseBuyerOrderSearch, type BuyerOrderFilters } from "@/lib/buyer-order-filters";
import { useAuthStore } from "@/store/useAuthStore";

const money = (amount_minor: number) => ({ amount_minor, currency: "VND" as const });
const line = { order_line_id: "1", product_id: "p1", sku_id: "sku1", quantity: 2, unit_price: money(100000), fulfillment_status: "PROCESSING" as const, snapshot_name: "Áo xanh", snapshot_image_url: null, snapshot_attributes: { color: "Xanh" } };
const order = {
  order_id: "1000201", order_status: "PENDING_PAYMENT", payment_status: "PENDING", created_at: "2026-10-20T01:00:00Z",
  total_amount: money(200000), total_discount: money(20000), final_amount: money(180000),
  payment: { status: "PENDING", active_attempt_number: 3, active_payment_transaction_id: "tx3", payment_deadline_at: "2099-10-20T01:15:00Z" },
  available_actions: { can_cancel: true, can_return: false, can_retry_payment: false }, refund_case: null,
  items: [{ order_item_id: "10", warehouse_id: "wh1", status: "PROCESSING", delivery_address: { recipient_name: "An", recipient_phone: "+84901234567", address_line: "1 Lê Lợi", province_code: "79", district_code: "760", ward_code: "26734" }, lines: [line] }],
} as unknown as OrderDetail;
const clients: QueryClient[] = [];
function providers() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  clients.push(client);
  return function Providers({ children }: { children: ReactNode }) { return <QueryClientProvider client={client}><ToastProvider>{children}</ToastProvider></QueryClientProvider>; };
}
beforeEach(() => {
  vi.resetAllMocks();
  useAuthStore.getState().setSession(`e30.${btoa(JSON.stringify({ sub: "buyer", roles: ["USER"] }))}.test`);
  vi.mocked(orderApi.mine).mockResolvedValue(order);
  vi.mocked(orderApi.listMine).mockResolvedValue({ items: [], total_orders: 0, next_cursor: null });
});
afterEach(() => { cleanup(); for (const client of clients.splice(0)) client.clear(); useAuthStore.getState().clear(); });

describe("F01 continue existing payment", () => {
  it("opens the active attempt and never requests another attempt", async () => {
    render(<OrderDetailContent orderId="1000201" />, { wrapper: providers() });
    expect((await screen.findByRole("link", { name: "Tiếp tục thanh toán" })).getAttribute("href")).toBe("/orders/1000201/payment?attempt=3");
    expect(orderApi.retryPayment).not.toHaveBeenCalled();
  });
  it.each([
    { order_status: "CANCELLED" }, { payment_status: "PAID" },
    { payment: { ...order.payment, payment_deadline_at: "2020-01-01T00:00:00Z" } },
    { payment: { ...order.payment, active_attempt_number: null } },
  ])("does not offer resume for an unusable order: %j", async patch => {
    vi.mocked(orderApi.mine).mockResolvedValue({ ...order, ...patch } as OrderDetail);
    render(<OrderDetailContent orderId="1000201" />, { wrapper: providers() });
    await screen.findByText("Chi tiết đơn hàng");
    expect(screen.queryByRole("link", { name: "Tiếp tục thanh toán" })).toBeNull();
  });
});

describe("F02 buyer order filters", () => {
  it("sends all filters and resets the cursor when the time range changes", async () => {
    const first: BuyerOrderFilters = { created_from: "2024-01-01T00:00:00Z", created_to: "2025-01-01T00:00:00Z", order_status: ["COMPLETED"], payment_status: ["PAID"], min_amount_minor: 0, max_amount_minor: 500000 };
    vi.mocked(orderApi.listMine).mockResolvedValue({ items: [], total_orders: 1, next_cursor: "page2" });
    const { result, rerender } = renderHook(({ filters }) => useMyOrders(undefined, "Áo", filters), { initialProps: { filters: first }, wrapper: providers() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(orderApi.listMine).toHaveBeenLastCalledWith({ ...first, q: "Áo", limit: 20 }, expect.any(AbortSignal));
    await act(async () => { await result.current.fetchNextPage(); });
    expect(orderApi.listMine).toHaveBeenLastCalledWith({ ...first, q: "Áo", limit: 20, cursor: "page2" }, expect.any(AbortSignal));
    rerender({ filters: { ...first, created_from: "2023-01-01T00:00:00Z" } });
    await waitFor(() => expect(orderApi.listMine).toHaveBeenLastCalledWith({ ...first, created_from: "2023-01-01T00:00:00Z", q: "Áo", limit: 20 }, expect.any(AbortSignal)));
  });
  it("offers all history and keeps filters when changing tabs", async () => {
    render(<OrdersPageContent initialQuery="Áo" initialFilters={{ order_status: ["COMPLETED"], min_amount_minor: 0 }} />, { wrapper: providers() });
    fireEvent.change(screen.getByRole("combobox", { name: "Thời gian đặt hàng" }), { target: { value: "all" } });
    fireEvent.click(screen.getByRole("button", { name: "Áp dụng bộ lọc" }));
    const allQuery = new URLSearchParams(vi.mocked(replace).mock.calls.at(-1)?.[0].split("?")[1]);
    expect(allQuery.get("history")).toBe("all");
    expect(allQuery.get("order_status")).toBe("COMPLETED");
    expect(parseBuyerOrderSearch(Object.fromEntries(allQuery)).filters.created_from).toBe(ALL_ORDER_HISTORY_FROM);
    fireEvent.click(screen.getByRole("tab", { name: "Chờ thanh toán" }));
    const tabQuery = new URLSearchParams(vi.mocked(replace).mock.calls.at(-1)?.[0].split("?")[1]);
    expect(tabQuery.get("tab")).toBe("TO_PAY");
    expect(tabQuery.get("order_status")).toBe("COMPLETED");
    expect(tabQuery.get("q")).toBe("Áo");
  });
  it("does not fetch a silently broadened query for an invalid URL and has a reset link", () => {
    render(<OrdersPageContent initialQuery="" filterErrors={["Số tiền tối đa phải lớn hơn hoặc bằng số tiền tối thiểu."]} />, { wrapper: providers() });
    expect(orderApi.listMine).not.toHaveBeenCalled();
    expect(screen.getByRole("alert").textContent).toContain("Số tiền tối đa");
    expect(screen.getByRole("link", { name: /Xóa bộ lọc/ }).getAttribute("href")).toBe("/orders?history=all");
  });
});

it("F05 shows only selected returned products and uses backend discount/net snapshots", async () => {
  vi.mocked(orderApi.refundCase).mockResolvedValue({ status: "SUCCEEDED", amount: money(180000), started_at: "2026-10-20T02:00:00Z", updated_at: "2026-10-20T02:10:00Z", scope_finalization: { status: "COMPLETED" },
    lines: [{ order_line_id: "1", expected_quantity: 2, unit_price_snapshot: money(100000), discount_share_snapshot: money(20000), net_amount_snapshot: money(180000) }] } as never);
  render(<RefundCaseCard orderId="1000201" orderLines={[line, { ...line, order_line_id: "2", snapshot_name: "Sản phẩm không trả" }]} />, { wrapper: providers() });
  const details = await screen.findByRole("region", { name: "Chi tiết sản phẩm trả và tiền hoàn" });
  expect(within(details).getByText("Áo xanh")).toBeTruthy();
  expect(within(details).getByText("Số lượng trả: 2")).toBeTruthy();
  expect(details.textContent).toMatch(/20\.000/);
  expect(details.textContent).toMatch(/180\.000/);
  expect(details.textContent).not.toContain("Sản phẩm không trả");
});

it("F06 shows cancellation/return reasons and only the supplied lifecycle milestones", () => {
  render(<OrderHistory order={{ ...order, cancel_reason: "PAYMENT_TIMEOUT", return_reason: "DAMAGED", paid_at: "2026-10-20T01:01:00Z", cancelled_at: "2026-10-20T01:15:00Z" }} />);
  expect(screen.getByText(/Đơn hàng đã hết hạn thanh toán/)).toBeTruthy();
  expect(screen.getByText(/Sản phẩm bị hư hỏng/)).toBeTruthy();
  expect(document.querySelector('time[datetime="2026-10-20T01:15:00Z"]')).toBeTruthy();
  expect(screen.queryByText("Hoàn tất xử lý hoàn tiền")).toBeNull();
});
