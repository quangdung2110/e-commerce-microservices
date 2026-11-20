import type { OrderDetail, OrderLine, OrderOperation } from "@/lib/api/order";
import { problemMessage } from "@/lib/api/problem";

export const TERMINAL_OPERATION_STATUSES = new Set<OrderOperation["status"]>([
  "SUCCEEDED",
  "REJECTED",
  "FAILED",
  "MANUAL_REVIEW_REQUIRED",
]);

export function isTerminalOrderOperation(operation: OrderOperation): boolean {
  return TERMINAL_OPERATION_STATUSES.has(operation.status);
}

/**
 * `MANUAL_REVIEW_REQUIRED` nghĩa là kết quả đang chờ con người (ví dụ Finance xử lý hoàn tiền
 * thất bại), không phải lỗi để thử lại. Câu này thay mọi thông điệp theo `failure.code` để người
 * dùng không bị mời gửi lại lệnh.
 */
export const MANUAL_REVIEW_MESSAGE =
  "Yêu cầu đang được bộ phận vận hành kiểm tra thủ công. Bạn không cần gửi lại — hãy tải lại đơn hàng sau để xem kết quả.";

export function isManualReviewOperation(operation: OrderOperation | null | undefined): boolean {
  return operation?.status === "MANUAL_REVIEW_REQUIRED";
}

/**
 * Lệnh đã được server nhận nhưng kết quả cuối chưa rõ: poll hết hạn (operation còn chạy) hoặc
 * operation chờ kiểm tra thủ công. Giữ nguyên Idempotency-Key và khoá lệnh, không gửi ý định mới.
 */
export function isUnresolvedOrderResult(result: {
  stillProcessing: boolean;
  operation: OrderOperation | null;
}): boolean {
  return result.stillProcessing || isManualReviewOperation(result.operation);
}

/** Thông điệp cho operation đã kết thúc nhưng không `SUCCEEDED`. */
export function operationFailureMessage(
  operation: OrderOperation | null | undefined,
  fallback: string
): string {
  if (isManualReviewOperation(operation)) return MANUAL_REVIEW_MESSAGE;
  return operation?.failure ? problemMessage({ problem: operation.failure }) : fallback;
}

export function orderLines(order: OrderDetail): OrderLine[] {
  return order.items.flatMap((item) => item.lines);
}

export function paymentAttemptNumber(reference: string | null): number | null {
  if (!reference) return null;
  const match = /\/payment-attempts\/(\d+)(?:$|[?#])/.exec(reference);
  if (!match) return null;
  const value = Number(match[1]);
  return Number.isSafeInteger(value) && value > 0 ? value : null;
}

export function addressText(address: OrderDetail["items"][number]["delivery_address"]): string {
  return [
    address.address_line,
    address.ward_name ?? address.ward_code,
    address.district_name ?? address.district_code,
    address.province_name ?? address.province_code,
  ]
    .filter(Boolean)
    .join(", ");
}

export function selectedReturnAmount(lines: OrderLine[]): number | null {
  let total = 0;
  for (const line of lines) {
    const amount = line.unit_price.amount_minor * line.quantity;
    if (!Number.isSafeInteger(amount) || !Number.isSafeInteger(total + amount)) return null;
    total += amount;
  }
  return total;
}

const DELIVERY_CONFIRMABLE_STATUSES = new Set<OrderDetail["order_status"]>([
  "PAID",
  "PROCESSING",
  "FINANCIAL_RECONCILIATION_REQUIRED",
]);

/** Dòng đã được kho xuất (DISPATCHED) nhưng Admin chưa xác nhận đã giao (Order ADR-021). */
export function dispatchedLines(order: OrderDetail): OrderLine[] {
  return orderLines(order).filter((line) => line.fulfillment_status === "DISPATCHED");
}

export function canAdminConfirmDelivery(order: OrderDetail): boolean {
  return DELIVERY_CONFIRMABLE_STATUSES.has(order.order_status) && dispatchedLines(order).length > 0;
}

export function canAdminCancelOrder(order: OrderDetail): boolean {
  return order.order_status === "PENDING_PAYMENT";
}

export function canAdminRefundBeforeDispatch(order: OrderDetail): boolean {
  return (
    (order.order_status === "PAID" || order.order_status === "PROCESSING") &&
    order.refund_case === null &&
    orderLines(order).every((line) => line.fulfillment_status === "PROCESSING")
  );
}
