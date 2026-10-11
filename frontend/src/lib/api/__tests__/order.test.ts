import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/lib/api/client", () => ({
  api: { get: vi.fn(), post: vi.fn() },
}));

import { api } from "@/lib/api/client";
import { orderApi } from "@/lib/api/order";

const commandId = "0199cafe-0000-7000-8000-000000000007";

describe("orderApi", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.get).mockResolvedValue({ data: {} });
    vi.mocked(api.post).mockResolvedValue({ data: {} });
  });

  it("gửi bộ lọc Buyer và cursor tới đúng endpoint", async () => {
    const signal = new AbortController().signal;
    await orderApi.listMine({ tab: "TO_RECEIVE", q: "điện thoại", cursor: "next", limit: 20 }, signal);
    expect(api.get).toHaveBeenCalledWith("/api/v1/orders", {
      params: { tab: "TO_RECEIVE", q: "điện thoại", cursor: "next", limit: 20 },
      signal,
    });
  });

  it("encode mã đơn khi đọc chi tiết", async () => {
    await orderApi.mine("12/34");
    expect(api.get).toHaveBeenCalledWith("/api/v1/orders/12%2F34", { signal: undefined });
  });

  it("gửi lý do huỷ cố định và Idempotency-Key", async () => {
    await orderApi.cancel("123", commandId);
    expect(api.post).toHaveBeenCalledWith(
      "/api/v1/orders/123/cancellation-requests",
      { reason_code: "USER_CANCEL" },
      { headers: { "Idempotency-Key": commandId } }
    );
  });

  it("gửi đúng full quantity scope cho yêu cầu trả hàng", async () => {
    const body = { lines: [{ order_line_id: "44", quantity: 2 }], reason_code: "DAMAGED", reason_details: "Vỡ màn hình" };
    await orderApi.requestReturn("123", body, commandId);
    expect(api.post).toHaveBeenCalledWith(
      "/api/v1/orders/123/return-requests",
      body,
      { headers: { "Idempotency-Key": commandId } }
    );
  });

  it("tạo payment attempt bất đồng bộ với return URL", async () => {
    await orderApi.retryPayment("123", "https://shop.test/orders/123/payment/complete", commandId);
    expect(api.post).toHaveBeenCalledWith(
      "/api/v1/orders/123/payment-attempts",
      { return_url: "https://shop.test/orders/123/payment/complete" },
      { headers: { "Idempotency-Key": commandId } }
    );
  });

  it("gửi bộ lọc admin tới namespace admin", async () => {
    const signal = new AbortController().signal;
    const params = {
      buyer_id: "0199cafe-0000-7000-8000-000000000010",
      order_status: ["PAID" as const],
      payment_status: ["PAID" as const],
      limit: 20,
    };
    await orderApi.listAdmin(params, signal);
    expect(api.get).toHaveBeenCalledWith("/api/v1/admin/orders", { params, signal });
  });

  it("gửi đúng hai lệnh vòng đời admin", async () => {
    await orderApi.cancelAsAdmin("123", commandId);
    expect(api.post).toHaveBeenCalledWith(
      "/api/v1/admin/orders/123/cancellation-requests",
      { reason_code: "ADMIN_FORCE_CANCEL" },
      { headers: { "Idempotency-Key": commandId } }
    );

    const body = { reason_code: "CUSTOMER_SUPPORT", reason_details: "Đã xác minh" };
    await orderApi.requestPreDispatchRefund("123", body, commandId);
    expect(api.post).toHaveBeenCalledWith(
      "/api/v1/admin/orders/123/pre-dispatch-refund-requests",
      body,
      { headers: { "Idempotency-Key": commandId } }
    );
  });
  it("xác nhận giao hàng là lệnh đồng bộ không dùng Idempotency-Key", async () => {
    const body = {
      lines: [
        { order_line_id: "44", delivered_at: "2026-10-11T03:00:00Z", delivery_reference: "VN123" },
      ],
    };
    await orderApi.confirmDelivery("12/34", body);
    expect(api.post).toHaveBeenCalledWith("/api/v1/admin/orders/12%2F34/delivery-confirmations", body);
  });

});
