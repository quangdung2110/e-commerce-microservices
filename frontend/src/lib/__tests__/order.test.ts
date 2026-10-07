import { describe, expect, it } from "vitest";

import type { OrderDetail, OrderLine } from "@/lib/api/order";
import { addressText, canAdminCancelOrder, canAdminRefundBeforeDispatch, isTerminalOrderOperation, paymentAttemptNumber, selectedReturnAmount } from "@/lib/order";

describe("order helpers", () => {
  it("chỉ nhận attempt number từ result reference đúng dạng", () => {
    expect(paymentAttemptNumber("/api/v1/orders/12/payment-attempts/3")).toBe(3);
    expect(paymentAttemptNumber("/api/v1/orders/12/payment-attempts/0")).toBeNull();
    expect(paymentAttemptNumber("/api/v1/orders/12")).toBeNull();
    expect(paymentAttemptNumber(null)).toBeNull();
  });

  it("phân biệt operation đang chạy và kết quả cuối", () => {
    const operation = { operation_id: "op", operation_type: "BUYER_CANCELLATION", order_id: "1", result_reference: null, failure: null, created_at: "", updated_at: "", status: "RUNNING" } as const;
    expect(isTerminalOrderOperation(operation)).toBe(false);
    expect(isTerminalOrderOperation({ ...operation, status: "SUCCEEDED" })).toBe(true);
    expect(isTerminalOrderOperation({ ...operation, status: "MANUAL_REVIEW_REQUIRED" })).toBe(true);
  });

  it("ưu tiên tên địa giới snapshot và fallback về code", () => {
    const address = { recipient_name: "An", recipient_phone: "+84900000000", address_line: "1 Lê Lợi", province_code: "79", district_code: "760", ward_code: "26734", province_name: "TP. Hồ Chí Minh", district_name: "Quận 1", ward_name: null } satisfies OrderDetail["items"][number]["delivery_address"];
    expect(addressText(address)).toBe("1 Lê Lợi, 26734, Quận 1, TP. Hồ Chí Minh");
  });

  it("tính số tiền dòng đã chọn bằng VND integer và từ chối phép tính không an toàn", () => {
    const line = { quantity: 2, unit_price: { amount_minor: 150_000, currency: "VND" } } as OrderLine;
    expect(selectedReturnAmount([line])).toBe(300_000);
    expect(selectedReturnAmount([{ ...line, quantity: Number.MAX_SAFE_INTEGER }])).toBeNull();
  });

  it("chặn đúng hành động admin theo trạng thái và fact xuất kho", () => {
    const base = {
      order_status: "PENDING_PAYMENT",
      refund_case: null,
      items: [{ lines: [{ fulfillment_status: "PROCESSING" }] }],
    } as unknown as OrderDetail;
    expect(canAdminCancelOrder(base)).toBe(true);
    expect(canAdminRefundBeforeDispatch(base)).toBe(false);

    const paid = { ...base, order_status: "PAID" } as OrderDetail;
    expect(canAdminCancelOrder(paid)).toBe(false);
    expect(canAdminRefundBeforeDispatch(paid)).toBe(true);

    const dispatched = {
      ...paid,
      items: [{ lines: [{ fulfillment_status: "DISPATCHED" }] }],
    } as unknown as OrderDetail;
    expect(canAdminRefundBeforeDispatch(dispatched)).toBe(false);
  });
});
