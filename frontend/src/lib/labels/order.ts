import type { components } from "@/lib/api/generated/order";
import type { EnumLabel } from "@/lib/labels/types";

type Schemas = components["schemas"];

export const BUYER_TAB_LABELS: Record<Schemas["BuyerTab"], EnumLabel> = {
  TO_PAY: { text: "Chờ thanh toán", tone: "warning" },
  TO_SHIP: { text: "Chờ giao hàng", tone: "info" },
  TO_RECEIVE: { text: "Đang giao", tone: "info" },
  RETURN_REFUND: { text: "Trả hàng & hoàn tiền", tone: "warning" },
  COMPLETED: { text: "Hoàn thành", tone: "success" },
  CANCELLED: { text: "Đã huỷ", tone: "destructive" },
};

export const ORDER_UI_STATUS_LABELS: Record<Schemas["UiPrimaryStatus"], EnumLabel> = {
  AWAITING_PAYMENT: { text: "Chờ thanh toán", tone: "warning" },
  CANCELLING: { text: "Đang huỷ", tone: "info" },
  PREPARING: { text: "Đang chuẩn bị hàng", tone: "info" },
  IN_DELIVERY: { text: "Đang giao hàng", tone: "info" },
  COMPLETED: { text: "Hoàn thành", tone: "success" },
  CANCELLED: { text: "Đã huỷ", tone: "destructive" },
  RETURN_IN_PROGRESS: { text: "Đang xử lý trả hàng", tone: "warning" },
  REFUNDED: { text: "Đã hoàn tiền", tone: "success" },
  UNDER_REVIEW: { text: "Đang được kiểm tra", tone: "warning" },
};

export const ORDER_STATUS_LABELS: Record<Schemas["OrderStatus"], EnumLabel> = {
  PENDING_PAYMENT: { text: "Chờ thanh toán", tone: "warning" },
  CANCELLING: { text: "Đang huỷ", tone: "info" },
  PAID: { text: "Đã thanh toán", tone: "success" },
  PROCESSING: { text: "Đang xử lý", tone: "info" },
  COMPLETED: { text: "Hoàn thành", tone: "success" },
  CANCELLED: { text: "Đã huỷ", tone: "destructive" },
  RETURN_REQUESTED: { text: "Đã yêu cầu trả hàng", tone: "warning" },
  REFUNDED: { text: "Đã hoàn tiền", tone: "success" },
  FINANCIAL_RECONCILIATION_REQUIRED: { text: "Đang đối soát", tone: "warning" },
};

export const PAYMENT_STATUS_LABELS: Record<Schemas["PaymentStatus"], EnumLabel> = {
  PENDING: { text: "Chờ thanh toán", tone: "warning" },
  PAID: { text: "Đã thanh toán", tone: "success" },
  FAILED: { text: "Thanh toán thất bại", tone: "destructive" },
  REFUNDING: { text: "Đang hoàn tiền", tone: "info" },
  REFUNDED: { text: "Đã hoàn tiền", tone: "success" },
  REFUND_FAILED: { text: "Hoàn tiền cần kiểm tra", tone: "warning" },
};

export const FULFILLMENT_STATUS_LABELS: Record<Schemas["FulfillmentStatus"], EnumLabel> = {
  PROCESSING: { text: "Đang chuẩn bị", tone: "default" },
  DISPATCHED: { text: "Đã xuất kho", tone: "info" },
  DELIVERED: { text: "Đã giao", tone: "success" },
};

export const REFUND_STATUS_LABELS: Record<Schemas["RefundCaseStatus"], EnumLabel> = {
  INITIATED: { text: "Đã tiếp nhận", tone: "info" },
  WAITING_INVENTORY_SCOPE_INSPECTION: { text: "Chờ kiểm tra hàng trả", tone: "warning" },
  WAITING_INVENTORY_RELEASE: { text: "Chờ kho xử lý", tone: "warning" },
  WAITING_PAYMENT_REFUND_RESULT: { text: "Đang hoàn tiền", tone: "info" },
  MANUAL_VERIFICATION_REQUIRED: { text: "Cần kiểm tra thủ công", tone: "warning" },
  SUCCEEDED: { text: "Hoàn tiền thành công", tone: "success" },
  FAILED: { text: "Hoàn tiền thất bại", tone: "destructive" },
  EXTERNALLY_REMEDIATED: { text: "Đã hoàn tiền ngoài hệ thống", tone: "success" },
  CLOSED_INCOMPLETE: { text: "Đã đóng, chưa hoàn tiền", tone: "warning" },
};

export const OPERATION_STATUS_LABELS: Record<Schemas["OperationStatus"], EnumLabel> = {
  ACCEPTED: { text: "Đã tiếp nhận", tone: "info" },
  RUNNING: { text: "Đang xử lý", tone: "info" },
  SUCCEEDED: { text: "Hoàn tất", tone: "success" },
  REJECTED: { text: "Không thể thực hiện", tone: "destructive" },
  FAILED: { text: "Xử lý thất bại", tone: "destructive" },
  MANUAL_REVIEW_REQUIRED: { text: "Cần kiểm tra thủ công", tone: "warning" },
};

export const RETURN_REASONS = [
  { value: "DAMAGED", label: "Sản phẩm bị hư hỏng" },
  { value: "WRONG_ITEM", label: "Giao sai sản phẩm" },
  { value: "NOT_AS_DESCRIBED", label: "Sản phẩm không đúng mô tả" },
  { value: "MISSING_PARTS", label: "Thiếu phụ kiện hoặc bộ phận" },
  { value: "NO_LONGER_NEEDED", label: "Không còn nhu cầu" },
  { value: "OTHER", label: "Lý do khác" },
] as const;
