// @vitest-environment jsdom
import { createElement, type ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, renderHook, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));
vi.mock("@/lib/api/order", () => ({
  orderApi: { admin: vi.fn(), adminOperation: vi.fn(), cancelAsAdmin: vi.fn(), requestPreDispatchRefund: vi.fn(), confirmDelivery: vi.fn() },
}));
// Poll hết hạn ngay: lệnh đã được nhận nhưng operation vẫn RUNNING (kết quả cuối chưa rõ).
vi.mock("@/lib/api/operation", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api/operation")>()),
  pollUntil: vi.fn(),
}));

import { AdminOrderDetail } from "@/components/admin/orders/admin-order-detail";
import { ToastProvider } from "@/components/ui/toast";
import { useAdminOrderCommand } from "@/hooks/use-admin-orders";
import { PollTimeoutError, pollUntil } from "@/lib/api/operation";
import { orderApi, type OrderDetail, type OrderOperation } from "@/lib/api/order";
import { useAuthStore } from "@/store/useAuthStore";

const running = {
  operation_id: "22222222-2222-4222-8222-222222222222",
  operation_type: "ADMIN_CANCELLATION",
  order_id: "770028",
  status: "RUNNING",
  result_reference: null,
  failure: null,
  created_at: "2026-10-14T09:00:00Z",
  updated_at: "2026-10-14T09:00:01Z",
} as unknown as OrderOperation;

const pending = {
  order_id: "770028",
  buyer_id: "bddfe10e-eff1-42eb-b195-5393de7d6af1",
  order_status: "PENDING_PAYMENT",
  payment_status: "PENDING",
  created_at: "2026-10-14T09:00:00Z",
  updated_at: "2026-10-14T09:00:00Z",
  total_amount: { amount_minor: 80001, currency: "VND" },
  total_discount: { amount_minor: 0, currency: "VND" },
  final_amount: { amount_minor: 80001, currency: "VND" },
  available_actions: { can_cancel: true, can_return: false, can_retry_payment: false },
  payment: { status: "PENDING", payment_deadline_at: "2026-10-14T09:14:00Z", active_attempt_number: 1 },
  refund_case: null,
  items: [
    {
      order_item_id: "31",
      warehouse_id: "WH-1",
      status: "PROCESSING",
      delivery_address: { recipient_name: "An", recipient_phone: "+84901234567", address_line: "1 Lê Lợi", province_code: "79", district_code: "760", ward_code: "26734" },
      lines: [{ order_line_id: "37", product_id: "p", sku_id: "s", quantity: 1, unit_price: { amount_minor: 80001, currency: "VND" }, fulfillment_status: "PROCESSING", snapshot_name: "Áo", snapshot_image_url: null, snapshot_attributes: {}, delivered_at: null }],
    },
  ],
} as unknown as OrderDetail;

function client() {
  return new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
}

beforeEach(() => {
  vi.resetAllMocks();
  useAuthStore.getState().setSession(`e30.${btoa(JSON.stringify({ sub: "admin-1", roles: ["USER", "ADMIN"] }))}.test`);
  vi.mocked(orderApi.admin).mockResolvedValue(pending);
  vi.mocked(orderApi.cancelAsAdmin).mockResolvedValue({ operation_id: running.operation_id } as never);
  vi.mocked(pollUntil).mockRejectedValue(new PollTimeoutError(running));
});
afterEach(cleanup);

describe("Admin — lệnh đã nhận nhưng kết quả cuối chưa rõ (poll hết hạn)", () => {
  it("khoá các nút huỷ/hoàn tiền thay vì mời mở ý định mới", async () => {
    render(<QueryClientProvider client={client()}><ToastProvider><AdminOrderDetail orderId="770028" /></ToastProvider></QueryClientProvider>);

    fireEvent.click(await screen.findByRole("button", { name: "Huỷ đơn chưa thanh toán" }));
    fireEvent.change(await screen.findByLabelText(/Gõ mã đơn/), { target: { value: "770028" } });
    fireEvent.click(screen.getByRole("button", { name: "Xác nhận" }));

    expect(await screen.findByText("Lệnh vẫn đang chạy")).toBeTruthy();
    await waitFor(() => expect((screen.getByRole("button", { name: "Huỷ đơn chưa thanh toán" }) as HTMLButtonElement).disabled).toBe(true));
    expect((screen.getByRole("button", { name: /Hoàn tiền trước xuất kho/ }) as HTMLButtonElement).disabled).toBe(true);
    expect(orderApi.cancelAsAdmin).toHaveBeenCalledTimes(1);
  });

  it("gửi lại dùng đúng Idempotency-Key cũ cho tới khi có kết quả cuối", async () => {
    const wrapper = ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client: client() }, children);
    const { result } = renderHook(() => useAdminOrderCommand("770028"), { wrapper });

    await act(async () => { await result.current.mutateAsync({ kind: "cancel" }); });
    await waitFor(() => expect(result.current.data?.stillProcessing).toBe(true));
    await act(async () => { await result.current.mutateAsync({ kind: "cancel" }); });

    const keys = vi.mocked(orderApi.cancelAsAdmin).mock.calls.map((call) => call[1]);
    expect(keys).toHaveLength(2);
    expect(keys[1]).toBe(keys[0]);

    // Operation kết thúc rõ ràng (SUCCEEDED) → ý định kế tiếp mới được sinh key mới.
    vi.mocked(pollUntil).mockResolvedValue({ ...running, status: "SUCCEEDED" } as OrderOperation);
    await act(async () => { await result.current.mutateAsync({ kind: "cancel" }); });
    await act(async () => { await result.current.mutateAsync({ kind: "cancel" }); });
    const all = vi.mocked(orderApi.cancelAsAdmin).mock.calls.map((call) => call[1]);
    expect(all[2]).toBe(all[0]); // lần SUCCEEDED vẫn là replay của ý định chưa rõ
    expect(all[3]).not.toBe(all[0]);
  });
});
