// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));
vi.mock("@/lib/api/order", () => ({
  orderApi: { mine: vi.fn(), refundCase: vi.fn(), operation: vi.fn(), cancel: vi.fn(), retryPayment: vi.fn() },
}));
vi.mock("@/lib/api/review", () => ({ reviewApi: { listPending: vi.fn() } }));

import { OrderDetailContent } from "@/components/orders/order-detail-content";
import { ToastProvider } from "@/components/ui/toast";
import { orderApi, type OrderDetail } from "@/lib/api/order";
import { reviewApi, type PendingEligibility } from "@/lib/api/review";
import { useAuthStore } from "@/store/useAuthStore";

function lineOf(id: string, productId: string, skuId: string, name: string) {
  return {
    order_line_id: id,
    product_id: productId,
    sku_id: skuId,
    quantity: 1,
    unit_price: { amount_minor: 100000, currency: "VND" },
    fulfillment_status: "DELIVERED",
    snapshot_name: name,
    snapshot_image_url: null,
    snapshot_attributes: {},
    delivered_at: "2026-10-05T00:00:00Z",
  };
}

function order(status: string): OrderDetail {
  return {
    order_id: "1000201",
    order_status: status,
    payment_status: "PAID",
    created_at: "2026-10-01T00:00:00Z",
    total_amount: { amount_minor: 200000, currency: "VND" },
    total_discount: { amount_minor: 0, currency: "VND" },
    final_amount: { amount_minor: 200000, currency: "VND" },
    available_actions: { can_cancel: false, can_return: false, can_retry_payment: false },
    refund_case: null,
    items: [
      {
        order_item_id: "10",
        warehouse_id: "WH-1",
        status: "DELIVERED",
        delivery_address: { recipient_name: "Nguyễn Văn A", recipient_phone: "+84901234567", address_line: "1 Lê Lợi", province_code: "79", district_code: "760", ward_code: "26734" },
        lines: [lineOf("1", "p-1", "sku-1", "Áo thun"), lineOf("2", "p-2", "sku-2", "Bình nước")],
      },
    ],
  } as unknown as OrderDetail;
}

const pending = (overrides: Partial<PendingEligibility>): PendingEligibility => ({
  eligibility_id: "e-1",
  order_id: "1000201",
  product_id: "p-1",
  sku_id: "sku-1",
  variant_name: null,
  product_name: "Áo thun",
  product_image_url: null,
  expires_at: "2026-11-05T00:00:00Z",
  ...overrides,
});

function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><ToastProvider><OrderDetailContent orderId="1000201" /></ToastProvider></QueryClientProvider>);
}

beforeEach(() => {
  vi.resetAllMocks();
  useAuthStore.getState().setSession(`e30.${btoa(JSON.stringify({ sub: "buyer-a", roles: ["USER"] }))}.test`);
});
afterEach(cleanup);

describe("OrderDetailContent — đánh giá sau mua", () => {
  it("đơn hoàn thành: chỉ dòng còn entitlement của đúng đơn này có nút đánh giá", async () => {
    vi.mocked(orderApi.mine).mockResolvedValue(order("COMPLETED"));
    vi.mocked(reviewApi.listPending).mockResolvedValue({
      items: [
        pending({ eligibility_id: "e-1" }),
        // Cùng SKU nhưng thuộc đơn khác: không được gắn vào đơn này.
        pending({ eligibility_id: "e-other", order_id: "999", sku_id: "sku-2", product_id: "p-2" }),
      ],
      next_cursor: null,
    });
    setup();

    const links = await screen.findAllByRole("link", { name: "Đánh giá sản phẩm" });
    expect(links).toHaveLength(1);
    expect(links[0].getAttribute("href")).toBe("/reviews/new?eligibility=e-1");
    expect(await screen.findByText(/1 sản phẩm trong đơn này đang chờ đánh giá/)).toBeTruthy();
  });

  it("đơn chưa hoàn thành: không hỏi danh sách chờ đánh giá", async () => {
    vi.mocked(orderApi.mine).mockResolvedValue(order("PROCESSING"));
    setup();

    await screen.findByText("Chi tiết đơn hàng");
    expect(reviewApi.listPending).not.toHaveBeenCalled();
    expect(screen.queryByRole("link", { name: "Đánh giá sản phẩm" })).toBeNull();
  });

  it("danh sách chờ đánh giá lỗi không làm hỏng chi tiết đơn", async () => {
    vi.mocked(orderApi.mine).mockResolvedValue(order("COMPLETED"));
    vi.mocked(reviewApi.listPending).mockRejectedValue(new Error("down"));
    setup();

    expect(await screen.findByText("Áo thun")).toBeTruthy();
    expect(screen.queryByRole("link", { name: "Đánh giá sản phẩm" })).toBeNull();
  });
});
