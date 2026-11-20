// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));
vi.mock("@/lib/api/order", () => ({
  orderApi: { admin: vi.fn(), adminOperation: vi.fn(), cancelAsAdmin: vi.fn(), requestPreDispatchRefund: vi.fn(), confirmDelivery: vi.fn() },
}));

import { AdminOrderDetail } from "@/components/admin/orders/admin-order-detail";
import { ToastProvider } from "@/components/ui/toast";
import { orderApi, type OrderDetail, type OrderOperation } from "@/lib/api/order";
import { MANUAL_REVIEW_MESSAGE } from "@/lib/order";
import { useAuthStore } from "@/store/useAuthStore";

const order = {
  order_id: "770021",
  buyer_id: "bddfe10e-eff1-42eb-b195-5393de7d6af1",
  order_status: "PROCESSING",
  payment_status: "PAID",
  created_at: "2026-10-14T08:47:00Z",
  updated_at: "2026-10-14T08:47:00Z",
  total_amount: { amount_minor: 690000, currency: "VND" },
  total_discount: { amount_minor: 0, currency: "VND" },
  final_amount: { amount_minor: 690000, currency: "VND" },
  available_actions: { can_cancel: false, can_return: false, can_retry_payment: false },
  payment: { status: "PAID", payment_deadline_at: "2026-10-14T09:00:00Z", active_attempt_number: 1 },
  refund_case: null,
  items: [
    {
      order_item_id: "31",
      warehouse_id: "01a10849-2835-73a2-b703-61e20ce30c08",
      status: "PROCESSING",
      delivery_address: { recipient_name: "An", recipient_phone: "+84901234567", address_line: "1 Lê Lợi", province_code: "79", district_code: "760", ward_code: "26734" },
      lines: [{ order_line_id: "37", product_id: "p", sku_id: "s", quantity: 1, unit_price: { amount_minor: 690000, currency: "VND" }, fulfillment_status: "PROCESSING", snapshot_name: "Camera", snapshot_image_url: null, snapshot_attributes: {}, delivered_at: null }],
    },
  ],
} as unknown as OrderDetail;

// Payload thật của operation 770024: Payment từ chối hoàn tiền vì PI đã được hoàn bên ngoài.
function manualOperation(code: string): OrderOperation {
  return {
    operation_id: "01a139b4-9ff9-76a1-83d1-08920abdb803",
    operation_type: "ADMIN_PRE_DISPATCH_REFUND",
    order_id: "770021",
    status: "MANUAL_REVIEW_REQUIRED",
    result_reference: null,
    failure: {
      type: "https://errors.ecommerce.local/order/x",
      title: "t",
      status: 409,
      detail: "Refund failed; customer payout remediation required",
      instance: "/api/v1/order-operations",
      code,
      retryable: false,
    },
    created_at: "2026-10-14T09:18:21Z",
    updated_at: "2026-10-14T09:18:23Z",
    completed_at: "2026-10-14T09:18:23Z",
  } as unknown as OrderOperation;
}

function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><ToastProvider><AdminOrderDetail orderId="770021" /></ToastProvider></QueryClientProvider>);
}

beforeEach(() => {
  vi.resetAllMocks();
  useAuthStore.getState().setSession(`e30.${btoa(JSON.stringify({ sub: "admin-1", roles: ["USER", "ADMIN"] }))}.test`);
  vi.mocked(orderApi.admin).mockResolvedValue(order);
  vi.mocked(orderApi.requestPreDispatchRefund).mockResolvedValue({ operation_id: "01a139b4-9ff9-76a1-83d1-08920abdb803" } as never);
});
afterEach(cleanup);

describe("AdminOrderDetail — refund thất bại vào MANUAL_REVIEW_REQUIRED", () => {
  it.each(["INVALID_ORDER_TRANSITION", "REFUND_MANUAL_REVIEW_REQUIRED"])(
    "mã %s: dialog báo đang kiểm tra thủ công và không cho gửi lại",
    async (code) => {
      vi.mocked(orderApi.adminOperation).mockResolvedValue(manualOperation(code));
      setup();

      fireEvent.click(await screen.findByRole("button", { name: /Hoàn tiền trước xuất kho/ }));
      fireEvent.change(await screen.findByLabelText(/Mã lý do/), { target: { value: "QA_REFUND" } });
      fireEvent.change(screen.getByLabelText(/Gõ mã đơn/), { target: { value: "770021" } });
      fireEvent.click(screen.getByRole("button", { name: "Xác nhận" }));

      expect(await screen.findByText("Lệnh chưa hoàn tất")).toBeTruthy();
      expect(screen.getByText(MANUAL_REVIEW_MESSAGE)).toBeTruthy();
      expect(screen.queryByText(/không cho phép thao tác này/)).toBeNull();
      await waitFor(() => expect((screen.getByRole("button", { name: "Xác nhận" }) as HTMLButtonElement).disabled).toBe(true));
    }
  );
});
