import { describe, expect, it } from "vitest";

import type { OrderDetail, OrderLine, OrderOperation } from "@/lib/api/order";
import { problemMessage } from "@/lib/api/problem";
import { addressText, canAdminCancelOrder, canAdminConfirmDelivery, canAdminRefundBeforeDispatch, dispatchedLines, isManualReviewOperation, isTerminalOrderOperation, MANUAL_REVIEW_MESSAGE, operationFailureMessage, paymentAttemptNumber, selectedReturnAmount } from "@/lib/order";

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

  it("MANUAL_REVIEW_REQUIRED luôn dùng câu kiểm tra thủ công, bất kể failure.code", () => {
    const base = { operation_id: "op", operation_type: "ADMIN_PRE_DISPATCH_REFUND", order_id: "1", result_reference: null, created_at: "", updated_at: "" } as const;
    const problem = (code: string) => ({ type: "t", title: "t", status: 409, detail: "d", instance: "/", code, retryable: false });
    for (const code of ["INVALID_ORDER_TRANSITION", "REFUND_MANUAL_REVIEW_REQUIRED", "DEPENDENCY_UNAVAILABLE"]) {
      const operation = { ...base, status: "MANUAL_REVIEW_REQUIRED", failure: problem(code) } as OrderOperation;
      expect(isManualReviewOperation(operation)).toBe(true);
      expect(operationFailureMessage(operation, "fallback")).toBe(MANUAL_REVIEW_MESSAGE);
    }
    const noFailure = { ...base, status: "MANUAL_REVIEW_REQUIRED", failure: null } as OrderOperation;
    expect(operationFailureMessage(noFailure, "fallback")).toBe(MANUAL_REVIEW_MESSAGE);
  });

  it("operation REJECTED/FAILED vẫn dịch theo failure.code, không có failure thì dùng fallback", () => {
    const base = { operation_id: "op", operation_type: "ADMIN_PRE_DISPATCH_REFUND", order_id: "1", result_reference: null, created_at: "", updated_at: "" } as const;
    const rejected = {
      ...base,
      status: "REJECTED",
      failure: { type: "t", title: "t", status: 409, detail: "d", instance: "/", code: "ADMIN_REFUND_REQUIRES_ALL_UNDISPATCHED", retryable: false },
    } as OrderOperation;
    expect(isManualReviewOperation(rejected)).toBe(false);
    expect(operationFailureMessage(rejected, "fallback")).toBe("Không thể hoàn tiền tự động vì đã có sản phẩm rời kho.");
    expect(operationFailureMessage({ ...base, status: "FAILED", failure: null } as OrderOperation, "fallback")).toBe("fallback");
    expect(operationFailureMessage(null, "fallback")).toBe("fallback");
  });

  it("có câu riêng cho mã REFUND_MANUAL_REVIEW_REQUIRED, không mời gửi lại", () => {
    const message = problemMessage({ problem: { code: "REFUND_MANUAL_REVIEW_REQUIRED", status: 409 } });
    expect(message).toMatch(/không cần gửi lại/);
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
  it("chỉ cho xác nhận giao khi có dòng đã xuất kho và đơn ở trạng thái cho phép (ADR-021)", () => {
    const line = (status: string, id: string) => ({ order_line_id: id, fulfillment_status: status });
    const order = (status: string, lines: Array<{ order_line_id: string; fulfillment_status: string }>) =>
      ({ order_status: status, items: [{ lines }] }) as unknown as OrderDetail;

    expect(canAdminConfirmDelivery(order("PROCESSING", [line("PROCESSING", "1")]))).toBe(false);
    expect(canAdminConfirmDelivery(order("PROCESSING", [line("DELIVERED", "1")]))).toBe(false);
    expect(canAdminConfirmDelivery(order("PROCESSING", [line("DISPATCHED", "1"), line("PROCESSING", "2")]))).toBe(true);
    expect(canAdminConfirmDelivery(order("PAID", [line("DISPATCHED", "1")]))).toBe(true);
    expect(canAdminConfirmDelivery(order("FINANCIAL_RECONCILIATION_REQUIRED", [line("DISPATCHED", "1")]))).toBe(true);
    expect(canAdminConfirmDelivery(order("COMPLETED", [line("DISPATCHED", "1")]))).toBe(false);
    expect(canAdminConfirmDelivery(order("RETURN_REQUESTED", [line("DISPATCHED", "1")]))).toBe(false);

    const mixed = order("PROCESSING", [line("DISPATCHED", "1"), line("DELIVERED", "2"), line("DISPATCHED", "3")]);
    expect(dispatchedLines(mixed).map((l) => l.order_line_id)).toEqual(["1", "3"]);
  });

});
