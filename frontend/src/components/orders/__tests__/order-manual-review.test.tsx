// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));
vi.mock("@/lib/api/order", () => ({
  orderApi: { mine: vi.fn(), refundCase: vi.fn(), operation: vi.fn(), cancel: vi.fn(), retryPayment: vi.fn() },
}));
vi.mock("@/lib/api/review", () => ({ reviewApi: { listPending: vi.fn() } }));

import { OrderDetailContent } from "@/components/orders/order-detail-content";
import { ToastProvider } from "@/components/ui/toast";
import { orderApi, type OrderDetail, type OrderOperation } from "@/lib/api/order";
import { MANUAL_REVIEW_MESSAGE } from "@/lib/order";
import { useAuthStore } from "@/store/useAuthStore";

// Payload thật của operation khi Payment từ chối hoàn tiền (mã cũ là INVALID_ORDER_TRANSITION).
function manualOperation(code: string): OrderOperation {
  return {
    operation_id: "11111111-1111-4111-8111-111111111111",
    operation_type: "BUYER_CANCELLATION",
    order_id: "770030",
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

const pendingOrder = {
  order_id: "770030",
  order_status: "PENDING_PAYMENT",
  payment_status: "PENDING",
  created_at: "2026-10-14T09:00:00Z",
  total_amount: { amount_minor: 80001, currency: "VND" },
  total_discount: { amount_minor: 0, currency: "VND" },
  final_amount: { amount_minor: 80001, currency: "VND" },
  available_actions: { can_cancel: true, can_return: false, can_retry_payment: false },
  refund_case: null,
  items: [
    {
      order_item_id: "10",
      warehouse_id: "WH-1",
      status: "PROCESSING",
      delivery_address: { recipient_name: "An", recipient_phone: "+84901234567", address_line: "1 Lê Lợi", province_code: "79", district_code: "760", ward_code: "26734" },
      lines: [{ order_line_id: "1", product_id: "p", sku_id: "s", quantity: 1, unit_price: { amount_minor: 80001, currency: "VND" }, fulfillment_status: "PROCESSING", snapshot_name: "Áo thun", snapshot_image_url: null, snapshot_attributes: {}, delivered_at: null }],
    },
  ],
} as unknown as OrderDetail;

function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><ToastProvider><OrderDetailContent orderId="770030" /></ToastProvider></QueryClientProvider>);
}

beforeEach(() => {
  vi.resetAllMocks();
  useAuthStore.getState().setSession(`e30.${btoa(JSON.stringify({ sub: "buyer-a", roles: ["USER"] }))}.test`);
  vi.mocked(orderApi.mine).mockResolvedValue(pendingOrder);
  vi.mocked(orderApi.cancel).mockResolvedValue({ operation_id: "11111111-1111-4111-8111-111111111111" } as never);
});
afterEach(cleanup);

describe("OrderDetailContent — operation MANUAL_REVIEW_REQUIRED", () => {
  it.each(["INVALID_ORDER_TRANSITION", "REFUND_MANUAL_REVIEW_REQUIRED"])(
    "mã %s: nói kết quả đang được kiểm tra, không mời gửi lại và khoá thao tác",
    async (code) => {
      vi.mocked(orderApi.operation).mockResolvedValue(manualOperation(code));
      setup();

      fireEvent.click(await screen.findByRole("button", { name: "Huỷ đơn hàng" }));
      fireEvent.click(await screen.findByRole("button", { name: "Xác nhận huỷ đơn" }));

      // Cả toast lẫn khung "Trạng thái thao tác" dùng câu về kiểm tra thủ công.
      await waitFor(() => expect(screen.getAllByText(MANUAL_REVIEW_MESSAGE).length).toBeGreaterThan(0));
      expect(screen.queryByText(/không cho phép thao tác này/)).toBeNull();
      expect(screen.queryByText(/Vui lòng thử lại/)).toBeNull();
      expect(screen.queryByRole("dialog")).toBeNull();
      expect((screen.getByRole("button", { name: "Huỷ đơn hàng" }) as HTMLButtonElement).disabled).toBe(true);
    }
  );
});
